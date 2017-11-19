/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental.sync

import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.internal.*
import kotlinx.coroutines.experimental.intrinsics.startCoroutineUndispatched
import kotlinx.coroutines.experimental.selects.ALREADY_SELECTED
import kotlinx.coroutines.experimental.selects.SelectInstance
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.coroutines.experimental.startCoroutine

internal actual fun MutexNewImpl(locked: Boolean): Mutex = MutexImpl(locked)

internal class MutexImpl(locked: Boolean) : Mutex {
    // State is: Empty | LockedQueue | OpDescriptor
    @Volatile
    private var _state: Any? = if (locked) EmptyLocked else EmptyUnlocked // shared objects while we have no waiters

    // resumeNext is: RESUME_QUIESCENT | RESUME_ACTIVE | ResumeReq
    @Volatile
    private var resumeNext: Any = RESUME_QUIESCENT

    private companion object {
        @JvmField
        val STATE: AtomicReferenceFieldUpdater<MutexImpl, Any?> =
                AtomicReferenceFieldUpdater.newUpdater(MutexImpl::class.java, Any::class.java, "_state")

        @JvmField
        val RESUME_NEXT: AtomicReferenceFieldUpdater<MutexImpl, Any> =
                AtomicReferenceFieldUpdater.newUpdater(MutexImpl::class.java, Any::class.java, "resumeNext")

        @JvmField
        val LOCK_FAIL = Symbol("LOCK_FAIL")

        @JvmField
        val ENQUEUE_FAIL = Symbol("ENQUEUE_FAIL")

        @JvmField
        val UNLOCK_FAIL = Symbol("UNLOCK_FAIL")

        @JvmField
        val SELECT_SUCCESS = Symbol("SELECT_SUCCESS")

        @JvmField
        val LOCKED = Symbol("LOCKED")

        @JvmField
        val UNLOCKED = Symbol("UNLOCKED")

        @JvmField
        val EmptyLocked = Empty(LOCKED)

        @JvmField
        val EmptyUnlocked = Empty(UNLOCKED)

        @JvmField
        val RESUME_QUIESCENT = Symbol("RESUME_QUIESCENT")

        @JvmField
        val RESUME_ACTIVE = Symbol("RESUME_ACTIVE")
    }

    public override val isLocked: Boolean
        get() {
            while (true) { // lock-free loop on state
                val state = this._state
                when (state) {
                    is Empty -> return state.locked !== UNLOCKED
                    is LockedQueue -> return true
                    is OpDescriptor -> state.perform(this) // help
                    else -> error("Illegal state $state")
                }
            }
        }

    // for tests
    internal val isLockedEmptyQueueState: Boolean
        get() {
            val state = this._state
            return state is LockedQueue && state.isEmpty
        }

    public override fun tryLock(owner: Any?): Boolean {
        while (true) { // lock-free loop on state
            val state = this._state
            when (state) {
                is Empty -> {
                    if (state.locked !== UNLOCKED) return false
                    val update = if (owner == null) EmptyLocked else Empty(owner)
                    if (STATE.compareAndSet(this, state, update)) return true
                }
                is LockedQueue -> {
                    check(state.owner !== owner) { "Already locked by $owner" }
                    return false
                }
                is OpDescriptor -> state.perform(this) // help
                else -> error("Illegal state $state")
            }
        }
    }

    public override suspend fun lock(owner: Any?) {
        // fast-path -- try lock
        if (tryLock(owner)) return
        // slow-path -- suspend
        return lockSuspend(owner)
    }

    private suspend fun lockSuspend(owner: Any?) = suspendAtomicCancellableCoroutine<Unit>(holdCancellability = true) sc@ { cont ->
        val waiter = LockCont(owner, cont)
        while (true) { // lock-free loop on state
            val state = this._state
            when (state) {
                is Empty -> {
                    if (state.locked !== UNLOCKED) {  // try upgrade to queue & retry
                        STATE.compareAndSet(this, state, LockedQueue(state.locked))
                    } else {
                        // try lock
                        val update = if (owner == null) EmptyLocked else Empty(owner)
                        if (STATE.compareAndSet(this, state, update)) { // locked
                            cont.resume(Unit)
                            return@sc
                        }
                    }
                }
                is LockedQueue -> {
                    val curOwner = state.owner
                    check(curOwner !== owner) { "Already locked by $owner" }
                    if (state.addLastIf(waiter, { this._state === state })) {
                        // added to waiter list!
                        cont.initCancellability() // make it properly cancellable
                        cont.removeOnCancel(waiter)
                        return@sc
                    }
                }
                is OpDescriptor -> state.perform(this) // help
                else -> error("Illegal state $state")
            }
        }
    }

    override fun <R> registerSelectLock(select: SelectInstance<R>, owner: Any?, block: suspend () -> R) {
        while (true) { // lock-free loop on state
            if (select.isSelected) return
            val state = this._state
            when (state) {
                is Empty -> {
                    if (state.locked !== UNLOCKED) { // try upgrade to queue & retry
                        STATE.compareAndSet(this, state, LockedQueue(state.locked))
                    } else {
                        // try lock
                        val failure = select.performAtomicTrySelect(TryLockDesc(this, owner))
                        when {
                            failure == null -> { // success
                                block.startCoroutineUndispatched(select.completion)
                                return
                            }
                            failure === ALREADY_SELECTED -> return // already selected -- bail out
                            failure === LOCK_FAIL -> {
                            } // retry
                            else -> error("performAtomicTrySelect(TryLockDesc) returned $failure")
                        }
                    }
                }
                is LockedQueue -> {
                    check(state.owner !== owner) { "Already locked by $owner" }
                    val enqueueOp = TryEnqueueLockDesc(this, owner, state, select, block)
                    val failure = select.performAtomicIfNotSelected(enqueueOp)
                    when {
                        failure == null -> { // successfully enqueued
                            select.disposeOnSelect(enqueueOp.node)
                            return
                        }
                        failure === ALREADY_SELECTED -> return // already selected -- bail out
                        failure === ENQUEUE_FAIL -> {
                        } // retry
                        else -> error("performAtomicIfNotSelected(TryEnqueueLockDesc) returned $failure")
                    }
                }
                is OpDescriptor -> state.perform(this) // help
                else -> error("Illegal state $state")
            }
        }
    }

    private class TryLockDesc(
            @JvmField val mutex: MutexImpl,
            @JvmField val owner: Any?
    ) : AtomicDesc() {
        // This is Harris's RDCSS (Restricted Double-Compare Single Swap) operation
        private inner class PrepareOp(private val op: AtomicOp<*>) : OpDescriptor() {
            override fun perform(affected: Any?): Any? {
                val update: Any = if (op.isDecided) EmptyUnlocked else op // restore if was already decided
                STATE.compareAndSet(affected as MutexImpl, this, update)
                return null // ok
            }
        }

        override fun prepare(op: AtomicOp<*>): Any? {
            val prepare = PrepareOp(op)
            if (!STATE.compareAndSet(mutex, EmptyUnlocked, prepare)) return LOCK_FAIL
            return prepare.perform(mutex)
        }

        override fun complete(op: AtomicOp<*>, failure: Any?) {
            val update = if (failure != null) EmptyUnlocked else {
                if (owner == null) EmptyLocked else Empty(owner)
            }
            STATE.compareAndSet(mutex, op, update)
        }
    }

    private class TryEnqueueLockDesc<R>(
            @JvmField val mutex: MutexImpl,
            owner: Any?,
            queue: LockedQueue,
            select: SelectInstance<R>,
            block: suspend () -> R
    ) : AddLastDesc<LockSelect<R>>(queue, LockSelect(owner, select, block)) {
        override fun onPrepare(affected: LockFreeLinkedListNode, next: LockFreeLinkedListNode): Any? {
            if (mutex._state !== queue) return ENQUEUE_FAIL
            return super.onPrepare(affected, next)
        }
    }

    public override fun unlock(owner: Any?) {
        while (true) { // lock-free loop on state
            val state = this._state
            when (state) {
                is Empty -> {
                    if (owner == null)
                        check(state.locked !== UNLOCKED) { "Mutex is not locked" }
                    else
                        check(state.locked === owner) { "Mutex is locked by ${state.locked} but expected $owner" }
                    if (STATE.compareAndSet(this, state, EmptyUnlocked)) return
                }
                is OpDescriptor -> state.perform(this)
                is LockedQueue -> {
                    if (owner != null)
                        check(state.owner === owner) { "Mutex is locked by ${state.owner} but expected $owner" }
                    val waiter = state.removeFirstOrNull()
                    if (waiter == null) {
                        val op = UnlockOp(state)
                        if (STATE.compareAndSet(this, state, op) && op.perform(this) == null) return
                    } else {
                        val token = (waiter as LockWaiter).tryResumeLockWaiter()
                        if (token != null) {
                            // successfully resumed waiter that now is holding the lock
                            // we must immediately transfer ownership to the next waiter, because this coroutine
                            // might try to lock it again after unlock returns do to StackOverflow avoidance code
                            // and its attempts to take a lock must be queued.
                            state.owner = waiter.owner ?: LOCKED
                            // StackOverflow avoidance code
                            if (startResumeNext(waiter, token)) {
                                waiter.completeResumeLockWaiter(token)
                                finishResumeNext()
                            }
                            return
                        }
                    }
                }
                else -> error("Illegal state $state")
            }
        }
    }

    private class ResumeReq(
            @JvmField val waiter: LockWaiter,
            @JvmField val token: Any
    )

    private fun startResumeNext(waiter: LockWaiter, token: Any): Boolean {
        while (true) { // lock-free loop on resumeNext
            val resumeNext = this.resumeNext
            when {
                resumeNext === RESUME_QUIESCENT -> {
                    // this is never concurrent, because only one thread is holding mutex and trying to resume
                    // next waiter, so no need to CAS here
                    this.resumeNext = RESUME_ACTIVE
                    return true
                }
                resumeNext === RESUME_ACTIVE ->
                    if (RESUME_NEXT.compareAndSet(this, resumeNext, ResumeReq(waiter, token))) return false
                else -> error("Cannot happen")
            }
        }
    }

    private fun finishResumeNext() {
        while (true) { // lock-free loop on resumeNext, also a resumption loop to fulfill requests of inner resume invokes
            val resumeNext = this.resumeNext
            when {
                resumeNext === RESUME_ACTIVE ->
                    if (RESUME_NEXT.compareAndSet(this, resumeNext, RESUME_QUIESCENT)) return
                resumeNext is ResumeReq -> {
                    // this is never concurrently, only one thread is finishing, so no need to CAS here
                    this.resumeNext = RESUME_ACTIVE
                    resumeNext.waiter.completeResumeLockWaiter(resumeNext.token)
                }
                else -> error("Cannot happen")
            }
        }
    }

    override fun toString(): String {
        while (true) {
            val state = this._state
            when (state) {
                is Empty -> return "Mutex[${state.locked}]"
                is OpDescriptor -> state.perform(this)
                is LockedQueue -> return "Mutex[${state.owner}]"
                else -> error("Illegal state $state")
            }
        }
    }

    private class Empty(
            @JvmField val locked: Any
    ) {
        override fun toString(): String = "Empty[$locked]"
    }

    private class LockedQueue(
            @JvmField var owner: Any
    ) : LockFreeLinkedListHead() {
        override fun toString(): String = "LockedQueue[$owner]"
    }

    private abstract class LockWaiter(
            @JvmField val owner: Any?
    ) : LockFreeLinkedListNode(), DisposableHandle {
        final override fun dispose() {
            remove()
        }

        abstract fun tryResumeLockWaiter(): Any?
        abstract fun completeResumeLockWaiter(token: Any)
    }

    private class LockCont(
            owner: Any?,
            @JvmField val cont: CancellableContinuation<Unit>
    ) : LockWaiter(owner) {
        override fun tryResumeLockWaiter() = cont.tryResume(Unit)
        override fun completeResumeLockWaiter(token: Any) = cont.completeResume(token)
        override fun toString(): String = "LockCont[$owner, $cont]"
    }

    private class LockSelect<R>(
            owner: Any?,
            @JvmField val select: SelectInstance<R>,
            @JvmField val block: suspend () -> R
    ) : LockWaiter(owner) {
        override fun tryResumeLockWaiter(): Any? = if (select.trySelect(null)) SELECT_SUCCESS else null
        override fun completeResumeLockWaiter(token: Any) {
            check(token === SELECT_SUCCESS)
            block.startCoroutine(select.completion)
        }

        override fun toString(): String = "LockSelect[$owner, $select]"
    }

    // atomic unlock operation that checks that waiters queue is empty
    private class UnlockOp(
            @JvmField val queue: LockedQueue
    ) : OpDescriptor() {
        override fun perform(affected: Any?): Any? {
            /*
               Note: queue cannot change while this UnlockOp is in progress, so all concurrent attempts to
               make a decision will reach it consistently. It does not matter what is a proposed
               decision when this UnlockOp is no longer active, because in this case the following CAS
               will fail anyway.
             */
            val success = queue.isEmpty
            val update: Any = if (success) EmptyUnlocked else queue
            STATE.compareAndSet(affected as MutexImpl, this@UnlockOp, update)
            /*
                `perform` invocation from the original `unlock` invocation may be coming too late, when
                some other thread had already helped to complete it (either successfully or not).
                That operation was unsuccessful if `state` was restored to this `queue` reference and
                that is what is being checked below.
             */
            return if (affected._state === queue) UNLOCK_FAIL else null
        }
    }
}
