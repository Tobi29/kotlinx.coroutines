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
import java.lang.Runnable
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext

/**
 * Implemented by [CoroutineDispatcher] implementations that have event loop inside and can
 * be asked to process next event from their event queue.
 *
 * It may optionally implement [Delay] interface and support time-scheduled tasks. It is used by [runBlocking] to
 * continue processing events when invoked from the event dispatch thread.
 */
header public interface EventLoop {
    /**
     * Processes next event in this event loop.
     *
     * The result of this function is to be interpreted like this:
     * * `<= 0` -- there are potentially more events for immediate processing;
     * * `> 0` -- a number of nanoseconds to wait for next scheduled event;
     * * [Long.MAX_VALUE] -- no more events, or was invoked from the wrong thread.
     */
    public fun processNextEvent(): Long
}

header internal abstract class EventLoopBase: CoroutineDispatcher, Delay, EventLoop {
    protected abstract val canComplete: Boolean
    protected abstract val isCompleted: Boolean
    protected abstract fun unpark()
    protected abstract fun isCorrectThread(): Boolean

    protected val isEmpty: Boolean

    fun execute(block: Runnable)

    override fun dispatch(context: CoroutineContext, block: Runnable)

    override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>)

    override fun invokeOnTimeout(time: Long, unit: TimeUnit, block: Runnable): DisposableHandle

    override fun processNextEvent(): Long

    internal fun enqueue(queuedTask: QueuedTask)

    protected fun clearAll()

    protected fun rescheduleAllDelayed()

    header internal abstract class QueuedTask : LockFreeLinkedListNode, Runnable
}
