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

import kotlinx.coroutines.experimental.internal.LockFreeLinkedListHead
import kotlinx.coroutines.experimental.internal.OpDescriptor
import kotlinx.coroutines.experimental.intrinsics.startCoroutineUndispatched
import kotlinx.coroutines.experimental.selects.SelectInstance
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.coroutines.experimental.AbstractCoroutineContextElement

// --------------- core job interfaces ---------------

/**
 * Cancels a specified [future] when this job is complete.
 *
 * This is a shortcut for the following code with slightly more efficient implementation (one fewer object created).
 * ```
 * invokeOnCompletion { future.cancel(false) }
 * ```
 */
public fun Job.cancelFutureOnCompletion(future: Future<*>): DisposableHandle =
        invokeOnCompletion(CancelFutureOnCompletion(this, future))

// --------------- utility classes to simplify job implementation

/**
 * A concrete implementation of [Job]. It is optionally a child to a parent job.
 * This job is cancelled when the parent is complete, but not vise-versa.
 *
 * This is an open class designed for extension by more specific classes that might augment the
 * state and mare store addition state information for completed jobs, like their result values.
 *
 * @param active when `true` the job is created in _active_ state, when `false` in _new_ state. See [Job] for details.
 * @suppress **This is unstable API and it is subject to change.**
 */
public actual open class JobSupport actual constructor(active: Boolean) : AbstractCoroutineContextElement(Job), Job {
    /*
       === Internal states ===

       name       state class    public state  description
       ------     ------------   ------------  -----------
       EMPTY_N    EmptyNew     : New           no listeners
       EMPTY_A    EmptyActive  : Active        no listeners
       SINGLE     JobNode      : Active        a single listener
       SINGLE+    JobNode      : Active        a single listener + NodeList added as its next
       LIST_N     NodeList     : New           a list of listeners (promoted once, does not got back to EmptyNew)
       LIST_A     NodeList     : Active        a list of listeners (promoted once, does not got back to JobNode/EmptyActive)
       CANCELLING Cancelling   : Cancelling(*) a list of listeners (promoted once)
       FINAL_C    Cancelled    : Cancelled     cancelled (final state)
       FINAL_F    Failed       : Completed     failed for other reason (final state)
       FINAL_R    <any>        : Completed     produced some result

       === Transitions ===

           New states      Active states     Inactive states
          +---------+       +---------+       +----------+
          | EMPTY_N | --+-> | EMPTY_A | --+-> |  FINAL_* |
          +---------+   |   +---------+   |   +----------+
               |        |     |     ^     |
               |        |     V     |     |
               |        |   +---------+   |
               |        |   | SINGLE  | --+
               |        |   +---------+   |
               |        |        |        |
               |        |        V        |
               |        |   +---------+   |
               |        +-- | SINGLE+ | --+
               |            +---------+   |
               |                 |        |
               V                 V        |
          +---------+       +---------+   |
          | LIST_N  | ----> | LIST_A  | --+
          +---------+       +---------+   |
               |                |         |
               |                V         |
               |         +------------+   |
               +-------> | CANCELLING | --+
                         +------------+

       This state machine and its transition matrix are optimized for the common case when job is created in active
       state (EMPTY_A) and at most one completion listener is added to it during its life-time.

       Note, that the actual `_state` variable can also be a reference to atomic operation descriptor `OpDescriptor`

       (*) The CANCELLING state is used only in AbstractCoroutine class. A general Job (that does not
           extend AbstractCoroutine) does not have CANCELLING state. It immediately transitions to
           FINAL_C (Cancelled) state on cancellation/completion
     */

    @Volatile
    private var _state: Any? = if (active) EmptyActive else EmptyNew // shared objects while we have no listeners

    @Volatile
    private var parentHandle: DisposableHandle? = null

    protected actual companion object {
        private val STATE: AtomicReferenceFieldUpdater<JobSupport, Any?> =
                AtomicReferenceFieldUpdater.newUpdater(JobSupport::class.java, Any::class.java, "_state")

        actual fun stateToString(state: Any?): String =
                if (state is Incomplete)
                    if (state.isActive) "Active" else "New"
                else "Completed"
    }

    // ------------ initialization ------------

    /**
     * Initializes parent job.
     * It shall be invoked at most once after construction after all other initialization.
     */
    public actual fun initParentJob(parent: Job?) {
        check(parentHandle == null)
        if (parent == null) {
            parentHandle = NonDisposableHandle
            return
        }
        parent.start() // make sure the parent is started
        // directly pass HandlerNode to parent scope to optimize one closure object (see makeNode)
        val newRegistration = parent.invokeOnCompletion(ParentOnCancellation(parent), onCancelling = true)
        parentHandle = newRegistration
        // now check our state _after_ registering (see updateState order of actions)
        if (isCompleted) newRegistration.dispose()
    }

    private inner class ParentOnCancellation(parent: Job) : JobCancellationNode<Job>(parent) {
        override fun invokeOnce(reason: Throwable?) {
            onParentCancellation(reason)
        }

        override fun toString(): String = "ParentOnCancellation[${this@JobSupport}]"
    }

    /**
     * Invoked at most once on parent completion.
     * @suppress **This is unstable API and it is subject to change.**
     */
    protected actual open fun onParentCancellation(cause: Throwable?) {
        // if parent was completed with CancellationException then use it as the cause of our cancellation, too.
        // however, we shall not use application specific exceptions here. So if parent crashes due to IOException,
        // we cannot and should not cancel the child with IOException
        cancel(cause as? CancellationException)
    }

    // ------------ state query ------------

    /**
     * Returns current state of this job.
     */
    protected actual val state: Any?
        get() {
            while (true) { // helper loop on state (complete in-progress atomic operations)
                val state = _state
                if (state !is OpDescriptor) return state
                state.perform(this)
            }
        }

    protected actual inline fun lockFreeLoopOnState(block: (Any?) -> Unit): Nothing {
        while (true) {
            block(state)
        }
    }

    public actual final override val isActive: Boolean
        get() {
            val state = this.state
            return state is Incomplete && state.isActive
        }

    public actual final override val isCompleted: Boolean get() = state !is Incomplete

    public actual final override val isCancelled: Boolean
        get() {
            val state = this.state
            return state is Cancelled || state is Cancelling
        }

    // ------------ state update ------------

    /**
     * Updates current [state] of this job.
     */
    protected actual fun updateState(expect: Any, proposedUpdate: Any?, mode: Int): Boolean {
        val update = coerceProposedUpdate(expect, proposedUpdate)
        if (!tryUpdateState(expect, update)) return false
        completeUpdateState(expect, update, mode)
        // if an exceptional completion was suppressed (because cancellation was in progress), then report it separately
        if (proposedUpdate !== update && proposedUpdate is CompletedExceptionally && proposedUpdate.cause != null)
            handleException(proposedUpdate.cause)
        return true
    }

    // when Job is in Cancelling state, it can only be promoted to Cancelled state with the same cause
    // however, null cause can be replaced with more specific CancellationException (that contains stack trace)
    private fun coerceProposedUpdate(expect: Any, proposedUpdate: Any?): Any? =
            if (expect is Cancelling && !correspondinglyCancelled(expect, proposedUpdate))
                expect.cancelled else proposedUpdate

    private fun correspondinglyCancelled(expect: Cancelling, proposedUpdate: Any?): Boolean {
        if (proposedUpdate !is Cancelled) return false
        return proposedUpdate.cause === expect.cancelled.cause ||
                proposedUpdate.cause is CancellationException && expect.cancelled.cause == null
    }

    /**
     * Tries to initiate update of the current [state] of this job.
     */
    protected actual fun tryUpdateState(expect: Any, update: Any?): Boolean {
        require(expect is Incomplete && update !is Incomplete) // only incomplete -> completed transition is allowed
        if (!STATE.compareAndSet(this, expect, update)) return false
        // Unregister from parent job
        parentHandle?.dispose() // volatile read parentHandle _after_ state was updated
        return true // continues in completeUpdateState
    }

    /**
     * Completes update of the current [state] of this job.
     */
    protected actual fun completeUpdateState(expect: Any, update: Any?, mode: Int) {
        // Invoke completion handlers
        val cause = (update as? CompletedExceptionally)?.cause
        when (expect) {
            is JobNode<*> -> try { // SINGLE/SINGLE+ state -- one completion handler (common case)
                expect.invoke(cause)
            } catch (ex: Throwable) {
                handleException(ex)
            }
            is NodeList -> notifyCompletion(expect, cause) // LIST state -- a list of completion handlers
            is Cancelling -> notifyCompletion(expect.list, cause) // has list, too
        // otherwise -- do nothing (it was Empty*)
            else -> check(expect is Empty)
        }
        // Do overridable processing after completion handlers
        if (expect !is Cancelling) onCancellation() // only notify when was not cancelling before
        afterCompletion(update, mode)
    }

    private inline fun <reified T : JobNode<*>> notifyHandlers(list: NodeList, cause: Throwable?) {
        var exception: Throwable? = null
        list.forEach<T> { node ->
            try {
                node.invoke(cause)
            } catch (ex: Throwable) {
                exception?.apply { addSuppressed(ex) } ?: run { exception = ex }
            }

        }
        exception?.let { handleException(it) }
    }

    private fun notifyCompletion(list: NodeList, cause: Throwable?) =
            notifyHandlers<JobNode<*>>(list, cause)

    private fun notifyCancellation(list: NodeList, cause: Throwable?) =
            notifyHandlers<JobCancellationNode<*>>(list, cause)

    public actual final override fun start(): Boolean {
        lockFreeLoopOnState { state ->
            when (startInternal(state)) {
                FALSE -> return false
                TRUE -> return true
            }
        }
    }

    // returns: RETRY/FALSE/TRUE:
    //   FALSE when not new,
    //   TRUE  when started
    //   RETRY when need to retry
    private fun startInternal(state: Any?): Int {
        when (state) {
            is Empty -> { // EMPTY_X state -- no completion handlers
                if (state.isActive) return FALSE // already active
                if (!STATE.compareAndSet(this, state, EmptyActive)) return RETRY
                onStart()
                return TRUE
            }
            is NodeList -> { // LIST -- a list of completion handlers (either new or active)
                if (state.active != 0) return FALSE
                if (!NodeList.ACTIVE.compareAndSet(state, 0, 1)) return RETRY
                onStart()
                return TRUE
            }
            else -> return FALSE // not a new state
        }
    }

    /**
     * Override to provide the actual [start] action.
     */
    protected actual open fun onStart() {}

    public actual final override fun getCompletionException(): Throwable {
        val state = this.state
        return when (state) {
            is Cancelling -> state.cancelled.exception
            is Incomplete -> error("Job was not completed or cancelled yet")
            is CompletedExceptionally -> state.exception
            else -> CancellationException("Job has completed normally")
        }
    }

    /**
     * Returns the cause that signals the completion of this job -- it returns the original
     * [cancel] cause or **`null` if this job had completed
     * normally or was cancelled without a cause**. This function throws
     * [IllegalStateException] when invoked for an job that has not [completed][isCompleted] nor
     * [isCancelled] yet.
     */
    protected actual fun getCompletionCause(): Throwable? {
        val state = this.state
        return when (state) {
            is Cancelling -> state.cancelled.cause
            is Incomplete -> error("Job was not completed or cancelled yet")
            is CompletedExceptionally -> state.cause
            else -> null
        }
    }

    public actual final override fun invokeOnCompletion(handler: CompletionHandler): DisposableHandle =
            installHandler(handler, onCancelling = false)

    public actual final override fun invokeOnCompletion(handler: CompletionHandler, onCancelling: Boolean): DisposableHandle =
            installHandler(handler, onCancelling = onCancelling && hasCancellingState)

    private fun installHandler(handler: CompletionHandler, onCancelling: Boolean): DisposableHandle {
        var nodeCache: JobNode<*>? = null
        lockFreeLoopOnState { state ->
            when (state) {
                is Empty -> { // EMPTY_X state -- no completion handlers
                    if (state.isActive) {
                        // try move to SINGLE state
                        val node = nodeCache ?: makeNode(handler, onCancelling).also { nodeCache = it }
                        if (STATE.compareAndSet(this, state, node)) return node
                    } else
                        promoteEmptyToNodeList(state) // that way we can add listener for non-active coroutine
                }
                is JobNode<*> -> { // SINGLE/SINGLE+ state -- one completion handler
                    promoteSingleToNodeList(state)
                }
                is NodeList -> { // LIST -- a list of completion handlers (either new or active)
                    val node = nodeCache ?: makeNode(handler, onCancelling).also { nodeCache = it }
                    if (addLastAtomic(state, state, node)) return node
                }
                is Cancelling -> { // CANCELLING -- has a list of completion handlers
                    if (onCancelling) { // installing cancellation handler on job that is being cancelled
                        handler((state as? CompletedExceptionally)?.exception)
                        return NonDisposableHandle
                    }
                    val node = nodeCache ?: makeNode(handler, onCancelling).also { nodeCache = it }
                    if (addLastAtomic(state, state.list, node)) return node
                }
                else -> { // is inactive
                    handler((state as? CompletedExceptionally)?.exception)
                    return NonDisposableHandle
                }
            }
        }
    }

    private fun makeNode(handler: CompletionHandler, onCancelling: Boolean): JobNode<*> =
            if (onCancelling)
                (handler as? JobCancellationNode<*>)?.also { require(it.job === this) }
                        ?: InvokeOnCancellation(this, handler)
            else
                (handler as? JobNode<*>)?.also { require(it.job === this && (!hasCancellingState || it !is JobCancellationNode)) }
                        ?: InvokeOnCompletion(this, handler)


    private fun addLastAtomic(expect: Any, list: NodeList, node: JobNode<*>) =
            list.addLastIf(node) { this.state === expect }

    private fun promoteEmptyToNodeList(state: Empty) {
        // try to promote it to list in new state
        STATE.compareAndSet(this, state, NodeList(state.isActive))
    }

    private fun promoteSingleToNodeList(state: JobNode<*>) {
        // try to promote it to list (SINGLE+ state)
        state.addOneIfEmpty(NodeList(active = true))
        // it must be in SINGLE+ state or state has changed (node could have need removed from state)
        val list = state.next // either NodeList or somebody else won the race, updated state
        // just attempt converting it to list if state is still the same, then we'll continue lock-free loop
        STATE.compareAndSet(this, state, list)
    }

    actual final override suspend fun join() {
        if (!joinInternal()) return // fast-path no wait
        return joinSuspend() // slow-path wait
    }

    private fun joinInternal(): Boolean {
        lockFreeLoopOnState { state ->
            if (state !is Incomplete) return false // not active anymore (complete) -- no need to wait
            if (startInternal(state) >= 0) return true // wait unless need to retry
        }
    }

    private suspend fun joinSuspend() = suspendCancellableCoroutine<Unit> { cont ->
        cont.disposeOnCompletion(invokeOnCompletion(ResumeOnCompletion(this, cont)))
    }

    actual override fun <R> registerSelectJoin(select: SelectInstance<R>, block: suspend () -> R) {
        // fast-path -- check state and select/return if needed
        lockFreeLoopOnState { state ->
            if (select.isSelected) return
            if (state !is Incomplete) {
                // already complete -- select result
                if (select.trySelect(null))
                    block.startCoroutineUndispatched(select.completion)
                return
            }
            if (startInternal(state) == 0) {
                // slow-path -- register waiter for completion
                select.disposeOnSelect(invokeOnCompletion(SelectJoinOnCompletion(this, select, block)))
                return
            }
        }
    }

    internal actual fun removeNode(node: JobNode<*>) {
        // remove logic depends on the state of the job
        lockFreeLoopOnState { state ->
            when (state) {
                is JobNode<*> -> { // SINGE/SINGLE+ state -- one completion handler
                    if (state !== node) return // a different job node --> we were already removed
                    // try remove and revert back to empty state
                    if (STATE.compareAndSet(this, state, EmptyActive)) return
                }
                is NodeList, is Cancelling -> { // LIST or CANCELLING -- a list of completion handlers
                    // remove node from the list
                    node.remove()
                    return
                }
                else -> return // it is inactive or Empty* (does not have any completion handlers)
            }
        }
    }

    protected actual open val hasCancellingState: Boolean get() = false

    public actual final override fun cancel(cause: Throwable?): Boolean =
            if (hasCancellingState)
                makeCancelling(cause) else
                makeCancelled(cause)

    // we will be dispatching coroutine to process its cancellation exception, so there is no need for
    // an extra check for Job status in MODE_CANCELLABLE
    private fun updateStateCancelled(state: Incomplete, cause: Throwable?) =
            updateState(state, Cancelled(cause), mode = MODE_ATOMIC_DEFAULT)

    // transitions to Cancelled state
    private fun makeCancelled(cause: Throwable?): Boolean {
        lockFreeLoopOnState { state ->
            if (state !is Incomplete) return false // quit if already complete
            if (updateStateCancelled(state, cause)) return true
        }
    }

    // transitions to Cancelling state
    private fun makeCancelling(cause: Throwable?): Boolean {
        lockFreeLoopOnState { state ->
            when (state) {
                is Empty -> { // EMPTY_X state -- no completion handlers
                    if (state.isActive) {
                        promoteEmptyToNodeList(state) // this way can wrap it into Cancelling on next pass
                    } else {
                        // cancelling a non-started coroutine makes it immediately cancelled
                        // (and we have no listeners to notify which makes it very simple)
                        if (updateStateCancelled(state, cause)) return true
                    }
                }
                is JobNode<*> -> { // SINGLE/SINGLE+ state -- one completion handler
                    promoteSingleToNodeList(state)
                }
                is NodeList -> { // LIST -- a list of completion handlers (either new or active)
                    if (state.isActive) {
                        // try make it cancelling on the condition that we're still in this state
                        if (STATE.compareAndSet(this, state, Cancelling(state, Cancelled(cause)))) {
                            notifyCancellation(state, cause)
                            onCancellation()
                            return true
                        }
                    } else {
                        // cancelling a non-started coroutine makes it immediately cancelled
                        if (updateStateCancelled(state, cause))
                            return true
                    }
                }
                else -> { // is inactive or already cancelling
                    return false
                }
            }
        }
    }

    /**
     * Override to process any exceptions that were encountered while invoking completion handlers
     * installed via [invokeOnCompletion].
     */
    protected actual open fun handleException(exception: Throwable) {
        throw exception
    }

    /**
     * It is invoked once when job is cancelled or is completed, similarly to [invokeOnCompletion] with
     * `onCancelling` set to `true`.
     */
    protected actual open fun onCancellation() {}

    /**
     * Override for post-completion actions that need to do something with the state.
     * @param mode completion mode.
     */
    protected actual open fun afterCompletion(state: Any?, mode: Int) {}

    // for nicer debugging
    override fun toString(): String {
        val state = this.state
        val result = if (state is Incomplete) "" else "[$state]"
        return "${this::class.java.simpleName}{${stateToString(state)}}$result@${Integer.toHexString(System.identityHashCode(this))}"
    }

    /**
     * Interface for incomplete [state] of a job.
     */
    public actual interface Incomplete {
        actual val isActive: Boolean
    }

    private class Cancelling(
            @JvmField val list: NodeList,
            @JvmField val cancelled: Cancelled
    ) : Incomplete {
        override val isActive: Boolean get() = false
    }

    private class NodeList(
            active: Boolean
    ) : LockFreeLinkedListHead(), Incomplete {
        @Volatile
        @JvmField
        var active: Int = if (active) 1 else 0

        override val isActive: Boolean get() = active != 0

        companion object {
            @JvmField
            val ACTIVE: AtomicIntegerFieldUpdater<NodeList> =
                    AtomicIntegerFieldUpdater.newUpdater(NodeList::class.java, "active")
        }

        override fun toString(): String = buildString {
            append("List")
            append(if (isActive) "{Active}" else "{New}")
            append("[")
            var first = true
            this@NodeList.forEach<JobNode<*>> { node ->
                if (first) first = false else append(", ")
                append(node)
            }
            append("]")
        }
    }

    /**
     * Class for a [state] of a job that had completed exceptionally, including cancellation.
     *
     * @param cause the exceptional completion cause. If `cause` is null, then a [CancellationException]
     *        if created on first get from [exception] property.
     */
    public actual open class CompletedExceptionally actual constructor(
            @JvmField actual val cause: Throwable?
    ) {
        @Volatile
        private var _exception: Throwable? = cause // materialize CancellationException on first need

        /**
         * Returns completion exception.
         */
        public actual val exception: Throwable
            get() =
                _exception ?: // atomic read volatile var or else create new
                        CancellationException("Job was cancelled").also { _exception = it }

        override fun toString(): String = "${this::class.java.simpleName}[$exception]"
    }

    /**
     * A specific subclass of [CompletedExceptionally] for cancelled jobs.
     */
    public actual class Cancelled actual constructor(
            cause: Throwable?
    ) : CompletedExceptionally(cause)


    /*
     * =================================================================================================
     * This is ready-to-use implementation for Deferred interface.
     * However, it is not type-safe. Conceptually it just exposes the value of the underlying
     * completed state as `Any?`
     * =================================================================================================
     */

    public actual val isCompletedExceptionally: Boolean get() = state is CompletedExceptionally

    protected actual fun getCompletedInternal(): Any? {
        val state = this.state
        check(state !is Incomplete) { "This job has not completed yet" }
        if (state is CompletedExceptionally) throw state.exception
        return state
    }

    protected actual suspend fun awaitInternal(): Any? {
        // fast-path -- check state (avoid extra object creation)
        while (true) { // lock-free loop on state
            val state = this.state
            if (state !is Incomplete) {
                // already complete -- just return result
                if (state is CompletedExceptionally) throw state.exception
                return state

            }
            if (startInternal(state) >= 0) break // break unless needs to retry
        }
        return awaitSuspend() // slow-path
    }

    private suspend fun awaitSuspend(): Any? = suspendCancellableCoroutine { cont ->
        cont.disposeOnCompletion(invokeOnCompletion {
            val state = this.state
            check(state !is Incomplete)
            if (state is CompletedExceptionally)
                cont.resumeWithException(state.exception)
            else
                cont.resume(state)
        })
    }

    protected actual fun <R> registerSelectAwaitInternal(select: SelectInstance<R>, block: suspend (Any?) -> R) {
        // fast-path -- check state and select/return if needed
        lockFreeLoopOnState { state ->
            if (select.isSelected) return
            if (state !is Incomplete) {
                // already complete -- select result
                if (select.trySelect(null)) {
                    if (state is CompletedExceptionally)
                        select.resumeSelectCancellableWithException(state.exception)
                    else
                        block.startCoroutineUndispatched(state, select.completion)
                }
                return
            }
            if (startInternal(state) == 0) {
                // slow-path -- register waiter for completion
                select.disposeOnSelect(invokeOnCompletion(SelectAwaitOnCompletion(this, select, block)))
                return
            }
        }
    }

    internal actual fun <R> selectAwaitCompletion(select: SelectInstance<R>, block: suspend (Any?) -> R) {
        val state = this.state
        // Note: await is non-atomic (can be cancelled while dispatched)
        if (state is CompletedExceptionally)
            select.resumeSelectCancellableWithException(state.exception)
        else
            block.startCoroutineCancellable(state, select.completion)
    }
}

// -------- invokeOnCancellation nodes

internal abstract class JobCancellationNode<out J : Job>(job: J) : JobNode<J>(job) {
    // shall be invoked at most once, so here is an additional flag
    @Volatile
    private var invoked: Int = 0

    private companion object {
        private val INVOKED: AtomicIntegerFieldUpdater<JobCancellationNode<*>> = AtomicIntegerFieldUpdater
                .newUpdater<JobCancellationNode<*>>(JobCancellationNode::class.java, "invoked")
    }

    final override fun invoke(reason: Throwable?) {
        if (INVOKED.compareAndSet(this, 0, 1)) invokeOnce(reason)
    }

    abstract fun invokeOnce(reason: Throwable?)
}

private class InvokeOnCancellation(
        job: Job,
        private val handler: CompletionHandler
) : JobCancellationNode<Job>(job) {
    override fun invokeOnce(reason: Throwable?) = handler.invoke(reason)
    override fun toString() = "InvokeOnCancellation[${handler::class.java.name}@${Integer.toHexString(System.identityHashCode(handler))}]"
}

private class CancelFutureOnCompletion(
        job: Job,
        private val future: Future<*>
) : JobNode<Job>(job)  {
    override fun invoke(reason: Throwable?) {
        // Don't interrupt when cancelling future on completion, because no one is going to reset this
        // interruption flag and it will cause spurious failures elsewhere
        future.cancel(false)
    }
    override fun toString() = "CancelFutureOnCompletion[$future]"
}

actual typealias CancellationException = java.util.concurrent.CancellationException
