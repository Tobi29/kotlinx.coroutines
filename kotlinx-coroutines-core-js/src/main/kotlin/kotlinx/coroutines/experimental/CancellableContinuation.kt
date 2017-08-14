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

package kotlinx.coroutines.experimental

import kotlinx.coroutines.experimental.internal.LockFreeLinkedListNode
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn
import kotlin.coroutines.experimental.suspendCoroutine

// --------------- cancellable continuations ---------------

@PublishedApi
impl internal inline suspend fun <T> suspendCancellableCoroutineImpl(
    holdCancellability: Boolean,
    crossinline block: (CancellableContinuation<T>) -> Unit
): T =
    suspendCoroutineOrReturn { cont ->
        val cancellable = CancellableContinuationImpl(cont, resumeMode = MODE_CANCELLABLE)
        if (!holdCancellability) cancellable.initCancellability()
        block(cancellable)
        cancellable.getResult()
    }

@PublishedApi
impl internal inline suspend fun <T> suspendAtomicCancellableCoroutineImpl(
    holdCancellability: Boolean,
    crossinline block: (CancellableContinuation<T>) -> Unit
): T =
    suspendCoroutineOrReturn { cont ->
        val cancellable = CancellableContinuationImpl(cont, resumeMode = MODE_ATOMIC_DEFAULT)
        if (!holdCancellability) cancellable.initCancellability()
        block(cancellable)
        cancellable.getResult()
    }

impl public fun CancellableContinuation<*>.removeOnCancel(node: LockFreeLinkedListNode): DisposableHandle =
    invokeOnCompletion(RemoveOnCancel(this, node))

// --------------- implementation details ---------------

private class RemoveOnCancel(
    cont: CancellableContinuation<*>,
    val node: LockFreeLinkedListNode
) : JobNode<CancellableContinuation<*>>(cont)  {
    override fun invoke(reason: Throwable?) {
        if (job.isCancelled)
            node.remove()
    }
    override fun toString() = "RemoveOnCancel[$node]"
}

@PublishedApi
internal class CancellableContinuationImpl<in T>(
    private val delegate: Continuation<T>,
    resumeMode: Int
) : AbstractContinuation<T>(true, resumeMode), CancellableContinuation<T> {
    @Volatile // just in case -- we don't want an extra data race, even benign one
    private var _context: CoroutineContext? = null // created on first need

    public override val context: CoroutineContext
        get() = _context ?: (delegate.context + this).also { _context = it }

    override fun initCancellability() {
        initParentJob(delegate.context[Job])
    }

    @PublishedApi
    internal fun getResult(): Any? {
        if (trySuspend()) return COROUTINE_SUSPENDED
        // otherwise, afterCompletion was already invoked & invoked tryResume, and the result is in the state
        val state = this.state
        if (state is CompletedExceptionally) throw state.exception
        return getSuccessfulResult(state)
    }

    override fun afterCompletion(state: Any?, mode: Int) {
        if (tryResume()) return // completed before getResult invocation -- bail out
        // otherwise, getResult has already commenced, i.e. completed later or in other thread
        if (state is CompletedExceptionally) {
            delegate.resumeWithExceptionMode(state.exception, mode)
        } else {
            delegate.resumeMode(getSuccessfulResult<T>(state), mode)
        }
    }

    override fun tryResume(value: T, idempotent: Any?): Any? {
        while (true) { // lock-free loop on state
            val state = this.state // atomic read
            when (state) {
                is Incomplete -> {
                    val update: Any? = if (idempotent == null) value else
                        CompletedIdempotentResult(idempotent, value, state)
                    if (tryUpdateState(state, update)) return state
                }
                is CompletedIdempotentResult -> {
                    if (state.idempotentResume === idempotent) {
                        check(state.result === value) { "Non-idempotent resume" }
                        return state.token
                    } else
                        return null
                }
                else -> return null // cannot resume -- not active anymore
            }
        }
    }

    override fun tryResumeWithException(exception: Throwable): Any? {
        while (true) { // lock-free loop on state
            val state = this.state // atomic read
            when (state) {
                is Incomplete -> {
                    if (tryUpdateState(state, CompletedExceptionally(exception))) return state
                }
                else -> return null // cannot resume -- not active anymore
            }
        }
    }

    override fun completeResume(token: Any) {
        completeUpdateState(token, state, resumeMode)
    }

    override fun CoroutineDispatcher.resumeUndispatched(value: T) {
        val dc = delegate as? DispatchedContinuation ?: throw IllegalArgumentException("Must be used with DispatchedContinuation")
        resumeImpl(value, if (dc.dispatcher === this) MODE_UNDISPATCHED else resumeMode)
    }

    override fun CoroutineDispatcher.resumeUndispatchedWithException(exception: Throwable) {
        val dc = delegate as? DispatchedContinuation ?: throw IllegalArgumentException("Must be used with DispatchedContinuation")
        resumeWithExceptionImpl(exception, if (dc.dispatcher === this) MODE_UNDISPATCHED else resumeMode)
    }

    override fun toString(): String = super.toString() + "[${delegate.toDebugString()}]"
}

private class CompletedIdempotentResult(
    val idempotentResume: Any?,
    val result: Any?,
    val token: JobSupport.Incomplete
) {
    override fun toString(): String = "CompletedIdempotentResult[$result]"
}

@Suppress("UNCHECKED_CAST")
private fun <T> getSuccessfulResult(state: Any?): T =
    if (state is CompletedIdempotentResult) state.result as T else state as T
