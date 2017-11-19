package kotlinx.coroutines.experimental.sync

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.selects.SelectBuilder
import kotlinx.coroutines.experimental.selects.SelectInstance
import kotlinx.coroutines.experimental.yield

/**
 * Mutual exclusion for coroutines.
 *
 * Mutex has two states: _locked_ and _unlocked_.
 * It is **non-reentrant**, that is invoking [lock] even from the same thread/coroutine that currently holds
 * the lock still suspends the invoker.
 */
public interface Mutex {
    /**
     * Factory for [Mutex] instances.
     * @suppress **Deprecated**
     */
    public companion object Factory {
        /**
         * Creates new [Mutex] instance.
         * @suppress **Deprecated**
         */
        @Deprecated("Replaced with top-level function", level = DeprecationLevel.HIDDEN)
        public operator fun invoke(locked: Boolean = false): Mutex = Mutex(locked)
    }

    /**
     * Returns `true` when this mutex is locked.
     */
    public val isLocked: Boolean

    /**
     * Tries to lock this mutex, returning `false` if this mutex is already locked.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
     *        is already locked with the same token (same identity), this function throws [IllegalStateException].
     */
    public fun tryLock(owner: Any? = null): Boolean

    /**
     * Locks this mutex, suspending caller while the mutex is locked.
     *
     * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
     * function is suspended, this function immediately resumes with [CancellationException].
     *
     * *Cancellation of suspended lock invocation is atomic* -- when this function
     * throws [CancellationException] it means that the mutex was not locked.
     * As a side-effect of atomic cancellation, a thread-bound coroutine (to some UI thread, for example) may
     * continue to execute even after it was cancelled from the same thread in the case when this lock operation
     * was already resumed and the continuation was posted for execution to the thread's queue.
     *
     * Note, that this function does not check for cancellation when it is not suspended.
     * Use [yield] or [CoroutineScope.isActive] to periodically check for cancellation in tight loops if needed.
     *
     * This function can be used in [select] invocation with [onLock][SelectBuilder.onLock] clause.
     * Use [tryLock] to try acquire lock without waiting.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
     *        is already locked with the same token (same identity), this function throws [IllegalStateException].
     */
    public suspend fun lock(owner: Any? = null)

    /**
     * Registers [onLock][SelectBuilder.onLock] select clause.
     * @suppress **This is unstable API and it is subject to change.**
     */
    public fun <R> registerSelectLock(select: SelectInstance<R>, owner: Any?, block: suspend () -> R)

    /**
     * Unlocks this mutex. Throws [IllegalStateException] if invoked on a mutex that is not locked.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
     *        was locked with the different token (by identity), this function throws [IllegalStateException].
     */
    public fun unlock(owner: Any? = null)
}

/**
 * Creates new [Mutex] instance.
 * @param locked initial state of the mutex.
 */
public fun Mutex(locked: Boolean = false): Mutex = MutexNewImpl(locked)

internal expect fun MutexNewImpl(locked: Boolean): Mutex

/**
 * Executes the given [action] under this mutex's lock.
 * @return the return value of the action.
 */
// :todo: this function needs to be make inline as soon as this bug is fixed: https://youtrack.jetbrains.com/issue/KT-16448
public suspend fun <T> Mutex.withLock(action: suspend () -> T): T {
    lock()
    try {
        return action()
    } finally {
        unlock()
    }
}

/**
 * @suppress: **Deprecated**: Use [withLock]
 */
@Deprecated("Use `withLock`", replaceWith = ReplaceWith("withLock(action)"))
public suspend fun <T> Mutex.withMutex(action: suspend () -> T): T {
    lock()
    try {
        return action()
    } finally {
        unlock()
    }
}
