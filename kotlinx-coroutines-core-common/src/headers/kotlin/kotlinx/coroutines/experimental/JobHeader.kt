package kotlinx.coroutines.experimental

import kotlinx.coroutines.experimental.selects.SelectInstance
import kotlin.coroutines.experimental.AbstractCoroutineContextElement

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
header public open class JobSupport(active: Boolean) : AbstractCoroutineContextElement(Job), Job {
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

    header protected companion object {
        fun stateToString(state: Any?): String
    }

    // ------------ initialization ------------

    /**
     * Initializes parent job.
     * It shall be invoked at most once after construction after all other initialization.
     */
    public fun initParentJob(parent: Job?)

    /**
     * Invoked at most once on parent completion.
     * @suppress **This is unstable API and it is subject to change.**
     */
    protected open fun onParentCancellation(cause: Throwable?)

    // ------------ state query ------------

    /**
     * Returns current state of this job.
     */
    protected val state: Any?

    protected inline fun lockFreeLoopOnState(block: (Any?) -> Unit): Nothing

    public final override val isActive: Boolean

    public final override val isCompleted: Boolean

    public final override val isCancelled: Boolean

    // ------------ state update ------------

    /**
     * Updates current [state] of this job.
     */
    protected fun updateState(expect: Any, proposedUpdate: Any?, mode: Int): Boolean

    /**
     * Tries to initiate update of the current [state] of this job.
     */
    protected fun tryUpdateState(expect: Any, update: Any?): Boolean

    /**
     * Completes update of the current [state] of this job.
     */
    protected fun completeUpdateState(expect: Any, update: Any?, mode: Int)

    public final override fun start(): Boolean

    /**
     * Override to provide the actual [start] action.
     */
    protected open fun onStart()

    public final override fun getCompletionException(): Throwable

    /**
     * Returns the cause that signals the completion of this job -- it returns the original
     * [cancel] cause or **`null` if this job had completed
     * normally or was cancelled without a cause**. This function throws
     * [IllegalStateException] when invoked for an job that has not [completed][isCompleted] nor
     * [isCancelled] yet.
     */
    protected fun getCompletionCause(): Throwable?

    public final override fun invokeOnCompletion(handler: CompletionHandler): DisposableHandle

    public final override fun invokeOnCompletion(handler: CompletionHandler, onCancelling: Boolean): DisposableHandle

    final override suspend fun join()

    override fun <R> registerSelectJoin(select: SelectInstance<R>, block: suspend () -> R)

    internal fun removeNode(node: JobNode<*>)

    protected open val hasCancellingState: Boolean

    public final override fun cancel(cause: Throwable?): Boolean

    /**
     * Override to process any exceptions that were encountered while invoking completion handlers
     * installed via [invokeOnCompletion].
     */
    protected open fun handleException(exception: Throwable)

    /**
     * It is invoked once when job is cancelled or is completed, similarly to [invokeOnCompletion] with
     * `onCancelling` set to `true`.
     */
    protected open fun onCancellation()

    /**
     * Override for post-completion actions that need to do something with the state.
     * @param mode completion mode.
     */
    protected open fun afterCompletion(state: Any?, mode: Int)

    /**
     * Interface for incomplete [state] of a job.
     */
    header public interface Incomplete {
        val isActive: Boolean
    }

    /**
     * Class for a [state] of a job that had completed exceptionally, including cancellation.
     *
     * @param cause the exceptional completion cause. If `cause` is null, then a [CancellationException]
     *        if created on first get from [exception] property.
     */
    header public open class CompletedExceptionally(
            cause: Throwable?
    ) {
        val cause: Throwable?
        /**
         * Returns completion exception.
         */
        public val exception: Throwable
    }

    /**
     * A specific subclass of [CompletedExceptionally] for cancelled jobs.
     */
    header public class Cancelled(
            cause: Throwable?
    ) : CompletedExceptionally(cause)


    /*
     * =================================================================================================
     * This is ready-to-use implementation for Deferred interface.
     * However, it is not type-safe. Conceptually it just exposes the value of the underlying
     * completed state as `Any?`
     * =================================================================================================
     */

    public val isCompletedExceptionally: Boolean

    protected fun getCompletedInternal(): Any?

    protected suspend fun awaitInternal(): Any?

    protected fun <R> registerSelectAwaitInternal(select: SelectInstance<R>, block: suspend (Any?) -> R)

    internal fun <R> selectAwaitCompletion(select: SelectInstance<R>, block: suspend (Any?) -> R)
}
