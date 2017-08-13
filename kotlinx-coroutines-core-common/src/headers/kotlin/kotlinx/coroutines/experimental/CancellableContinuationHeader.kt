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
import kotlin.coroutines.experimental.suspendCoroutine

// --------------- cancellable continuations ---------------

/**
 * Suspends coroutine similar to [suspendCoroutine], but provide an implementation of [CancellableContinuation] to
 * the [block]. This function throws [CancellationException] if the coroutine is cancelled or completed while suspended.
 *
 * If [holdCancellability] optional parameter is `true`, then the coroutine is suspended, but it is not
 * cancellable until [CancellableContinuation.initCancellability] is invoked.
 *
 * See [suspendAtomicCancellableCoroutine] for suspending functions that need *atomic cancellation*.
 */
public inline suspend fun <T> suspendCancellableCoroutine(
        holdCancellability: Boolean = false,
        crossinline block: (CancellableContinuation<T>) -> Unit
): T = suspendCancellableCoroutineImpl(holdCancellability, block)

@PublishedApi
header internal inline suspend fun <T> suspendCancellableCoroutineImpl(
        holdCancellability: Boolean,
        crossinline block: (CancellableContinuation<T>) -> Unit
): T

/**
 * Suspends coroutine similar to [suspendCancellableCoroutine], but with *atomic cancellation*.
 *
 * When suspended function throws [CancellationException] it means that the continuation was not resumed.
 * As a side-effect of atomic cancellation, a thread-bound coroutine (to some UI thread, for example) may
 * continue to execute even after it was cancelled from the same thread in the case when the continuation
 * was already resumed and was posted for execution to the thread's queue.
 */
public inline suspend fun <T> suspendAtomicCancellableCoroutine(
        holdCancellability: Boolean = false,
        crossinline block: (CancellableContinuation<T>) -> Unit
): T = suspendAtomicCancellableCoroutineImpl(holdCancellability, block)

@PublishedApi
header internal inline suspend fun <T> suspendAtomicCancellableCoroutineImpl(
        holdCancellability: Boolean,
        crossinline block: (CancellableContinuation<T>) -> Unit
): T

/**
 * Removes a given node on cancellation.
 * @suppress **This is unstable API and it is subject to change.**
 */
header public fun CancellableContinuation<*>.removeOnCancel(node: LockFreeLinkedListNode): DisposableHandle
