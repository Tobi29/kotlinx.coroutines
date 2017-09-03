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

package kotlinx.coroutines.experimental.selects

import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.DisposableHandle
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.TimeUnit
import kotlinx.coroutines.experimental.channels.ClosedReceiveChannelException
import kotlinx.coroutines.experimental.channels.ClosedSendChannelException
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.SendChannel
import kotlinx.coroutines.experimental.internal.AtomicDesc
import kotlinx.coroutines.experimental.internal.Symbol
import kotlinx.coroutines.experimental.sync.Mutex
import kotlin.coroutines.experimental.Continuation

/**
 * Internal representation of select instance. This instance is called _selected_ when
 * the clause to execute is already picked.
 *
 * @suppress **This is unstable API and it is subject to change.**
 */
public interface SelectInstance<in R> {
    /**
     * Returns `true` when this [select] statement had already picked a clause to execute.
     */
    public val isSelected: Boolean

    /**
     * Tries to select this instance.
     */
    public fun trySelect(idempotent: Any?): Boolean

    /**
     * Performs action atomically with [trySelect].
     */
    public fun performAtomicTrySelect(desc: AtomicDesc): Any?

    /**
     * Performs action atomically when [isSelected] is `false`.
     */
    public fun performAtomicIfNotSelected(desc: AtomicDesc): Any?

    /**
     * Returns completion continuation of this select instance.
     * This select instance must be _selected_ first.
     * All resumption through this instance happen _directly_ (as if `mode` is [MODE_DIRECT]).
     */
    public val completion: Continuation<R>

    /**
     * Resumes this instance with [MODE_CANCELLABLE].
     */
    public fun resumeSelectCancellableWithException(exception: Throwable)

    public fun disposeOnSelect(handle: DisposableHandle)
}

/**
 * Scope for [select] invocation.
 */
public interface SelectBuilder<in R> {
    /**
     * Clause for [Job.join] suspending function that selects the given [block] when the job is complete.
     * This clause never fails, even if the job completes exceptionally.
     */
    public fun Job.onJoin(block: suspend () -> R)

    /**
     * Clause for [Deferred.await] suspending function that selects the given [block] with the deferred value is
     * resolved. The [select] invocation fails if the deferred value completes exceptionally (either fails or
     * it cancelled).
     */
    public fun <T> Deferred<T>.onAwait(block: suspend (T) -> R)

    /**
     * Clause for [SendChannel.send] suspending function that selects the given [block] when the [element] is sent to
     * the channel. The [select] invocation fails with [ClosedSendChannelException] if the channel
     * [isClosedForSend][SendChannel.isClosedForSend] _normally_ or with the original
     * [close][SendChannel.close] cause exception if the channel has _failed_.
     */
    public fun <E> SendChannel<E>.onSend(element: E, block: suspend () -> R)

    /**
     * Clause for [ReceiveChannel.receive] suspending function that selects the given [block] with the element that
     * is received from the channel. The [select] invocation fails with [ClosedReceiveChannelException] if the channel
     * [isClosedForReceive][ReceiveChannel.isClosedForReceive] _normally_ or with the original
     * [close][SendChannel.close] cause exception if the channel has _failed_.
     */
    public fun <E> ReceiveChannel<E>.onReceive(block: suspend (E) -> R)

    /**
     * Clause for [ReceiveChannel.receiveOrNull] suspending function that selects the given [block] with the element that
     * is received from the channel or selects the given [block] with `null` if if the channel
     * [isClosedForReceive][ReceiveChannel.isClosedForReceive] _normally_. The [select] invocation fails with
     * the original [close][SendChannel.close] cause exception if the channel has _failed_.
     */
    public fun <E> ReceiveChannel<E>.onReceiveOrNull(block: suspend (E?) -> R)

    /**
     * Clause for [Mutex.lock] suspending function that selects the given [block] when the mutex is locked.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
     *        is already locked with the same token (same identity), this clause throws [IllegalStateException].
     */
    public fun Mutex.onLock(owner: Any? = null, block: suspend () -> R)

    /**
     * Clause that selects the given [block] after a specified timeout passes.
     *
     * @param time timeout time
     * @param unit timeout unit (milliseconds by default)
     */
    public fun onTimeout(time: Long, unit: TimeUnit = TimeUnit.MILLISECONDS, block: suspend () -> R)
}

internal val ALREADY_SELECTED: Any = Symbol("ALREADY_SELECTED")
