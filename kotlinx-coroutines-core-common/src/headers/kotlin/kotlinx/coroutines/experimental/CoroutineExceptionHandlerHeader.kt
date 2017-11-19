package kotlinx.coroutines.experimental

import kotlin.coroutines.experimental.CoroutineContext

/**
 * Helper function for coroutine builder implementations to handle uncaught exception in coroutines.
 * It tries to handle uncaught exception in the following way:
 * * If there is [CoroutineExceptionHandler] in the context, then it is used.
 * * Otherwise, if exception is [CancellationException] then it is ignored
 *   (because that is the supposed mechanism to cancel the running coroutine)
 * * Otherwise, if there is a [Job] in the context, then [Job.cancel] is invoked and if it
 *   returns `true` (it was still active), then the exception is considered to be handled.
 * * Otherwise, current thread's [Thread.uncaughtExceptionHandler] is used.
 */
expect fun handleCoroutineException(context: CoroutineContext, exception: Throwable)
