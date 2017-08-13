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
import kotlinx.coroutines.experimental.selects.SelectInstance
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.CoroutineContext

// --------------- core job interfaces ---------------

/**
 * A background job. Conceptually, a job is a cancellable thing with a simple life-cycle that
 * culminates in its completion. Jobs can be arranged into parent-child hierarchies where cancellation
 * or completion of parent immediately cancels all its children.
 *
 * The most basic instances of [Job] are created with [launch] coroutine builder or with a
 * `Job()` factory function.  Other coroutine builders and primitives like
 * [Deferred] also implement [Job] interface.
 *
 * A job has the following states:
 *
 * | **State**                               | [isActive] | [isCompleted] | [isCancelled] |
 * | --------------------------------------- | ---------- | ------------- | ------------- |
 * | _New_ (optional initial state)          | `false`    | `false`       | `false`       |
 * | _Active_ (default initial state)        | `true`     | `false`       | `false`       |
 * | _Cancelling_ (optional transient state) | `false`    | `false`       | `true`        |
 * | _Cancelled_ (final state)               | `false`    | `true`        | `true`        |
 * | _Completed normally_ (final state)      | `false`    | `true`        | `false`       |
 *
 * Usually, a job is created in _active_ state (it is created and started). However, coroutine builders
 * that provide an optional `start` parameter create a coroutine in _new_ state when this parameter is set to
 * [CoroutineStart.LAZY]. Such a job can be made _active_ by invoking [start] or [join].
 *
 * A job can be _cancelled_ at any time with [cancel] function that forces it to transition to
 * _cancelling_ state immediately. Simple jobs, that are not backed by a coroutine, like
 * [CompletableDeferred] and the result of `Job()` factory function, don't
 * have a _cancelling_ state, but become _cancelled_ on [cancel] immediately.
 * Coroutines, on the other hand, become _cancelled_ only when they finish executing their code.
 *
 * ```
 *    +-----+       start      +--------+   complete   +-----------+
 *    | New | ---------------> | Active | -----------> | Completed |
 *    +-----+                  +--------+              | normally  |
 *       |                         |                   +-----------+
 *       | cancel                  | cancel
 *       V                         V
 *  +-----------+   finish   +------------+
 *  | Cancelled | <--------- | Cancelling |
 *  |(completed)|            +------------+
 *  +-----------+
 * ```
 *
 * A job in the coroutine [context][CoroutineScope.context] represents the coroutine itself.
 * A job is active while the coroutine is working and job's cancellation aborts the coroutine when
 * the coroutine is suspended on a _cancellable_ suspension point by throwing [CancellationException]
 * or the cancellation cause inside the coroutine.
 *
 * A job can have a _parent_ job. A job with a parent is cancelled when its parent is cancelled or completes.
 *
 * All functions on this interface and on all interfaces derived from it are **thread-safe** and can
 * be safely invoked from concurrent coroutines without external synchronization.
 */
public interface Job : CoroutineContext.Element {
    /**
     * Key for [Job] instance in the coroutine context.
     */
    public companion object Key : CoroutineContext.Key<Job> {
        /**
         * Creates a new job object in _active_ state.
         * It is optionally a child of a [parent] job.
         * @suppress **Deprecated**
         */
        @Deprecated("Replaced with top-level function", level = DeprecationLevel.HIDDEN)
        public operator fun invoke(parent: Job? = null): Job = Job(parent)

        init {
            /*
             * Here we make sure that CoroutineExceptionHandler is always initialized in advance, so
             * that if a coroutine fails due to StackOverflowError we don't fail to report this error
             * trying to initialize CoroutineExceptionHandler
             */
            CoroutineExceptionHandler
        }
    }

    // ------------ state query ------------

    /**
     * Returns `true` when this job is active -- it was already started and has not completed or cancelled yet.
     */
    public val isActive: Boolean

    /**
     * Returns `true` when this job has completed for any reason. A job that was cancelled and has
     * finished its execution is also considered complete.
     */
    public val isCompleted: Boolean

    /**
     * Returns `true` if this job was [cancelled][cancel]. In the general case, it does not imply that the
     * job has already [completed][isCompleted] (it may still be cancelling whatever it was doing).
     */
    public val isCancelled: Boolean

    /**
     * Returns the exception that signals the completion of this job -- it returns the original
     * [cancel] cause or an instance of [CancellationException] if this job had completed
     * normally or was cancelled without a cause. This function throws
     * [IllegalStateException] when invoked for an job that has not [completed][isCompleted] nor
     * [isCancelled] yet.
     *
     * The [cancellable][suspendCancellableCoroutine] suspending functions throw this exception
     * when trying to suspend in the context of this job.
     */
    public fun getCompletionException(): Throwable

    // ------------ state update ------------

    /**
     * Starts coroutine related to this job (if any) if it was not started yet.
     * The result `true` if this invocation actually started coroutine or `false`
     * if it was already started or completed.
     */
    public fun start(): Boolean

    /**
     * Cancel this job with an optional cancellation [cause]. The result is `true` if this job was
     * cancelled as a result of this invocation and `false` otherwise
     * (if it was already _completed_ or if it is [NonCancellable]).
     * Repeated invocations of this function have no effect and always produce `false`.
     *
     * When cancellation has a clear reason in the code, an instance of [CancellationException] should be created
     * at the corresponding original cancellation site and passed into this method to aid in debugging by providing
     * both the context of cancellation and text description of the reason.
     */
    public fun cancel(cause: Throwable? = null): Boolean

    // ------------ state waiting ------------

    /**
     * Suspends coroutine until this job is complete. This invocation resumes normally (without exception)
     * when the job is complete for any reason. This function also [starts][Job.start] the corresponding coroutine
     * if the [Job] was still in _new_ state.
     *
     * This suspending function is cancellable. If the [Job] of the invoking coroutine is cancelled or completed while this
     * suspending function is suspended, this function immediately resumes with [CancellationException].
     *
     * This function can be used in [select] invocation with [onJoin][SelectBuilder.onJoin] clause.
     * Use [isCompleted] to check for completion of this job without waiting.
     */
    public suspend fun join()

    // ------------ low-level state-notification ------------

    /**
     * Registers handler that is **synchronously** invoked once on completion of this job.
     * When job is already complete, then the handler is immediately invoked
     * with a job's cancellation cause or `null`. Otherwise, handler will be invoked once when this
     * job is complete.
     *
     * The resulting [DisposableHandle] can be used to [dispose][DisposableHandle.dispose] the
     * registration of this handler and release its memory if its invocation is no longer needed.
     * There is no need to dispose the handler after completion of this job. The references to
     * all the handlers are released when this job completes.
     *
     * Note, that the handler is not invoked on invocation of [cancel] when
     * job becomes _cancelling_, but only when the corresponding coroutine had finished execution
     * of its code and became _cancelled_. There is an overloaded version of this function
     * with `onCancelling` parameter to receive notification on _cancelling_ state.
     *
     * **Note**: This function is a part of internal machinery that supports parent-child hierarchies
     * and allows for implementation of suspending functions that wait on the Job's state.
     * This function should not be used in general application code.
     * Implementations of `CompletionHandler` must be fast and _lock-free_.
     */
    public fun invokeOnCompletion(handler: CompletionHandler): DisposableHandle

    /**
     * Registers handler that is **synchronously** invoked once on cancellation or completion of this job.
     * When job is already complete, then the handler is immediately invoked
     * with a job's cancellation cause or `null`. Otherwise, handler will be invoked once when this
     * job is cancelled or complete.
     *
     * Invocation of this handler on a transition to a transient _cancelling_ state
     * is controlled by [onCancelling] boolean parameter.
     * The handler is invoked on invocation of [cancel] when
     * job becomes _cancelling_ when [onCancelling] parameters is set to `true`. However,
     * when this [Job] is not backed by a coroutine, like [CompletableDeferred] or [CancellableContinuation]
     * (both of which do not posses a _cancelling_ state), then the value of [onCancelling] parameter is ignored.
     *
     * The resulting [DisposableHandle] can be used to [dispose][DisposableHandle.dispose] the
     * registration of this handler and release its memory if its invocation is no longer needed.
     * There is no need to dispose the handler after completion of this job. The references to
     * all the handlers are released when this job completes.
     *
     * **Note**: This function is a part of internal machinery that supports parent-child hierarchies
     * and allows for implementation of suspending functions that wait on the Job's state.
     * This function should not be used in general application code.
     * Implementations of `CompletionHandler` must be fast and _lock-free_.
     */
    public fun invokeOnCompletion(handler: CompletionHandler, onCancelling: Boolean): DisposableHandle

    // ------------ unstable internal API ------------

    /**
     * Registers [onJoin][SelectBuilder.onJoin] select clause.
     * @suppress **This is unstable API and it is subject to change.**
     */
    public fun <R> registerSelectJoin(select: SelectInstance<R>, block: suspend () -> R)

    /**
     * @suppress **Error**: Operator '+' on two Job objects is meaningless.
     * Job is a coroutine context element and `+` is a set-sum operator for coroutine contexts.
     * The job to the right of `+` just replaces the job the left of `+`.
     */
    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated(message = "Operator '+' on two Job objects is meaningless. " +
            "Job is a coroutine context element and `+` is a set-sum operator for coroutine contexts. " +
            "The job to the right of `+` just replaces the job the left of `+`.",
            level = DeprecationLevel.ERROR)
    public operator fun plus(other: Job) = other

    /**
     * Registration object for [invokeOnCompletion]. It can be used to [unregister] if needed.
     * There is no need to unregister after completion.
     * @suppress **Deprecated**: Replace with `DisposableHandle`
     */
    @Deprecated(message = "Replace with `DisposableHandle`",
            replaceWith = ReplaceWith("DisposableHandle"))
    public interface Registration {
        /**
         * Unregisters completion handler.
         * @suppress **Deprecated**: Replace with `dispose`
         */
        @Deprecated(message = "Replace with `dispose`",
                replaceWith = ReplaceWith("dispose()"))
        public fun unregister()
    }
}

/**
 * Creates a new job object in an _active_ state.
 * It is optionally a child of a [parent] job.
 */
public fun Job(parent: Job? = null): Job = JobImpl(parent)

/**
 * A handle to an allocated object that can be disposed to make it eligible for garbage collection.
 */
@Suppress("DEPRECATION") // todo: remove when Job.Registration is removed
public interface DisposableHandle : Job.Registration {
    /**
     * Disposes the corresponding object, making it eligible for garbage collection.
     * Repeated invocation of this function has no effect.
     */
    public fun dispose()

    /**
     * Unregisters completion handler.
     * @suppress **Deprecated**: Replace with `dispose`
     */
    @Deprecated(message = "Replace with `dispose`",
            replaceWith = ReplaceWith("dispose()"))
    public override fun unregister() = dispose()
}

/**
 * Handler for [Job.invokeOnCompletion].
 *
 * **Note**: This type is a part of internal machinery that supports parent-child hierarchies
 * and allows for implementation of suspending functions that wait on the Job's state.
 * This type should not be used in general application code.
 * Implementations of `CompletionHandler` must be fast and _lock-free_.
 */
public typealias CompletionHandler = (Throwable?) -> Unit

/**
 * Thrown by cancellable suspending functions if the [Job] of the coroutine is cancelled while it is suspending.
 */
public typealias CancellationException = java.util.concurrent.CancellationException

/**
 * Unregisters a specified [registration] when this job is complete.
 *
 * This is a shortcut for the following code with slightly more efficient implementation (one fewer object created).
 * ```
 * invokeOnCompletion { registration.unregister() }
 * ```
 * @suppress: **Deprecated**: Renamed to `disposeOnCompletion`.
 */
@Deprecated(message = "Renamed to `disposeOnCompletion`",
        replaceWith = ReplaceWith("disposeOnCompletion(registration)"))
public fun Job.unregisterOnCompletion(registration: DisposableHandle): DisposableHandle =
        invokeOnCompletion(DisposeOnCompletion(this, registration))

/**
 * Disposes a specified [handle] when this job is complete.
 *
 * This is a shortcut for the following code with slightly more efficient implementation (one fewer object created).
 * ```
 * invokeOnCompletion { handle.dispose() }
 * ```
 */
public fun Job.disposeOnCompletion(handle: DisposableHandle): DisposableHandle =
        invokeOnCompletion(DisposeOnCompletion(this, handle))

/**
 * @suppress **Deprecated**: `join` is now a member function of `Job`.
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER", "DeprecatedCallableAddReplaceWith")
@Deprecated(message = "`join` is now a member function of `Job`")
public suspend fun Job.join() = this.join()

/**
 * No-op implementation of [Job.Registration].
 */
@Deprecated(message = "Replace with `NonDisposableHandle`",
        replaceWith = ReplaceWith("NonDisposableHandle"))
typealias EmptyRegistration = NonDisposableHandle

/**
 * No-op implementation of [DisposableHandle].
 */
public object NonDisposableHandle : DisposableHandle {
    /** Does not do anything. */
    override fun dispose() {}

    /** Returns "NonDisposableHandle" string. */
    override fun toString(): String = "NonDisposableHandle"
}

// --------------- utility classes to simplify job implementation

internal const val RETRY = -1
internal const val FALSE = 0
internal const val TRUE = 1

internal val EmptyNew = Empty(false)
internal val EmptyActive = Empty(true)

internal class Empty(override val isActive: Boolean) : JobSupport.Incomplete {
    override fun toString(): String = "Empty{${if (isActive) "Active" else "New"}}"
}

internal class JobImpl(parent: Job? = null) : JobSupport(true) {
    init {
        initParentJob(parent)
    }
}

// -------- invokeOnCompletion nodes

internal abstract class JobNode<out J : Job>(
        /* @JvmField */ val job: J
) : LockFreeLinkedListNode(), DisposableHandle, CompletionHandler, JobSupport.Incomplete {
    final override val isActive: Boolean get() = true
    final override fun dispose() = (job as JobSupport).removeNode(this)
    override abstract fun invoke(reason: Throwable?)
}

internal class InvokeOnCompletion(
        job: Job,
        private val handler: CompletionHandler
) : JobNode<Job>(job) {
    override fun invoke(reason: Throwable?) = handler.invoke(reason)
    // TODO
    // override fun toString() = "InvokeOnCompletion[${handler::class.java.name}@${Integer.toHexString(System.identityHashCode(handler))}]"
}

internal class ResumeOnCompletion(
        job: Job,
        private val continuation: Continuation<Unit>
) : JobNode<Job>(job) {
    override fun invoke(reason: Throwable?) = continuation.resume(Unit)
    override fun toString() = "ResumeOnCompletion[$continuation]"
}

internal class DisposeOnCompletion(
        job: Job,
        private val handle: DisposableHandle
) : JobNode<Job>(job) {
    override fun invoke(reason: Throwable?) = handle.dispose()
    override fun toString(): String = "DisposeOnCompletion[$handle]"
}

internal class SelectJoinOnCompletion<R>(
        job: JobSupport,
        private val select: SelectInstance<R>,
        private val block: suspend () -> R
) : JobNode<JobSupport>(job) {
    override fun invoke(reason: Throwable?) {
        if (select.trySelect(null))
            block.startCoroutineCancellable(select.completion)
    }

    override fun toString(): String = "SelectJoinOnCompletion[$select]"
}

internal class SelectAwaitOnCompletion<R>(
        job: JobSupport,
        private val select: SelectInstance<R>,
        private val block: suspend (Any?) -> R
) : JobNode<JobSupport>(job) {
    override fun invoke(reason: Throwable?) {
        if (select.trySelect(null))
            job.selectAwaitCompletion(select, block)
    }

    override fun toString(): String = "SelectAwaitOnCompletion[$select]"
}

