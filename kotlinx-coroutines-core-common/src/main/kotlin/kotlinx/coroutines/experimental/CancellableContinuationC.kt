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

import kotlin.coroutines.experimental.Continuation

// --------------- cancellable continuations ---------------

/**
 * Cancellable continuation. Its job is _completed_ when it is resumed or cancelled.
 * When [cancel] function is explicitly invoked, this continuation immediately resumes with [CancellationException] or
 * with the specified cancel cause.
 *
 * Cancellable continuation has three states (as subset of [Job] states):
 *
 * | **State**                           | [isActive] | [isCompleted] | [isCancelled] |
 * | ----------------------------------- | ---------- | ------------- | ------------- |
 * | _Active_ (initial state)            | `true`     | `false`       | `false`       |
 * | _Resumed_ (final _completed_ state) | `false`    | `true`        | `false`       |
 * | _Canceled_ (final _completed_ state)| `false`    | `true`        | `true`        |
 *
 * Invocation of [cancel] transitions this continuation from _active_ to _cancelled_ state, while
 * invocation of [resume] or [resumeWithException] transitions it from _active_ to _resumed_ state.
 *
 * A [cancelled][isCancelled] continuation implies that it is [completed][isCompleted].
 *
 * Invocation of [resume] or [resumeWithException] in _resumed_ state produces [IllegalStateException]
 * but is ignored in _cancelled_ state.
 *
 * ```
 *    +-----------+   resume    +---------+
 *    |  Active   | ----------> | Resumed |
 *    +-----------+             +---------+
 *          |
 *          | cancel
 *          V
 *    +-----------+
 *    | Cancelled |
 *    +-----------+
 *
 * ```
 */
public interface CancellableContinuation<in T> : Continuation<T>, Job {
    /**
     * Returns `true` if this continuation was [cancelled][cancel].
     *
     * It implies that [isActive] is `false` and [isCompleted] is `true`.
     */
    public override val isCancelled: Boolean

    /**
     * Tries to resume this continuation with a given value and returns non-null object token if it was successful,
     * or `null` otherwise (it was already resumed or cancelled). When non-null object was returned,
     * [completeResume] must be invoked with it.
     *
     * When [idempotent] is not `null`, this function performs _idempotent_ operation, so that
     * further invocations with the same non-null reference produce the same result.
     *
     * @suppress **This is unstable API and it is subject to change.**
     */
    public fun tryResume(value: T, idempotent: Any? = null): Any?

    /**
     * Tries to resume this continuation with a given exception and returns non-null object token if it was successful,
     * or `null` otherwise (it was already resumed or cancelled). When non-null object was returned,
     * [completeResume] must be invoked with it.
     *
     * @suppress **This is unstable API and it is subject to change.**
     */
    public fun tryResumeWithException(exception: Throwable): Any?

    /**
     * Completes the execution of [tryResume] or [tryResumeWithException] on its non-null result.
     *
     * @suppress **This is unstable API and it is subject to change.**
     */
    public fun completeResume(token: Any)

    /**
     * Makes this continuation cancellable. Use it with `holdCancellability` optional parameter to
     * [suspendCancellableCoroutine] function. It throws [IllegalStateException] if invoked more than once.
     */
    public fun initCancellability()

    /**
     * Resumes this continuation with a given [value] in the invoker thread without going though
     * [dispatch][CoroutineDispatcher.dispatch] function of the [CoroutineDispatcher] in the [context].
     * This function is designed to be used only by the [CoroutineDispatcher] implementations themselves.
     * **It should not be used in general code**.
     */
    public fun CoroutineDispatcher.resumeUndispatched(value: T)

    /**
     * Resumes this continuation with a given [exception] in the invoker thread without going though
     * [dispatch][CoroutineDispatcher.dispatch] function of the [CoroutineDispatcher] in the [context].
     * This function is designed to be used only by the [CoroutineDispatcher] implementations themselves.
     * **It should not be used in general code**.
     */
    public fun CoroutineDispatcher.resumeUndispatchedWithException(exception: Throwable)
}
