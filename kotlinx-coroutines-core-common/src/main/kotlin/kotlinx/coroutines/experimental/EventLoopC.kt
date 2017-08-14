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
import kotlinx.coroutines.experimental.internal.LockFreeLinkedListNode
import kotlinx.coroutines.experimental.internal.ThreadSafeHeap
import kotlinx.coroutines.experimental.internal.ThreadSafeHeapNode
import kotlin.coroutines.experimental.CoroutineContext

private const val DELAYED = 0
private const val REMOVED = 1
private const val RESCHEDULED = 2

internal abstract class EventLoopBase : CoroutineDispatcher(), Delay, EventLoop {
    private val queue = LockFreeLinkedListHead()
    private val delayed = ThreadSafeHeap<DelayedTask>()

    protected abstract val canComplete: Boolean
    protected abstract val isCompleted: Boolean
    protected abstract fun unpark()
    protected abstract fun isCorrectThread(): Boolean

    protected val isEmpty: Boolean
        get() = queue.isEmpty && delayed.isEmpty

    private val nextTime: Long
        get() {
            if (!queue.isEmpty) return 0
            val nextDelayedTask = delayed.peek() ?: return Long.MAX_VALUE
            return (nextDelayedTask.nanoTime - timeSource.nanoTime()).coerceAtLeast(0)
        }

    fun execute(block: Runnable) =
            enqueue(block.toQueuedTask())

    override fun dispatch(context: CoroutineContext, block: Runnable) =
            enqueue(block.toQueuedTask())

    override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>) =
            schedule(DelayedResumeTask(time, unit, continuation))

    override fun invokeOnTimeout(time: Long, unit: TimeUnit, block: Runnable): DisposableHandle =
            DelayedRunnableTask(time, unit, block).also { schedule(it) }

    override fun processNextEvent(): Long {
        if (!isCorrectThread()) return Long.MAX_VALUE
        // queue all delayed tasks that are due to be executed
        if (!delayed.isEmpty) {
            val now = timeSource.nanoTime()
            while (true) {
                // make sure that moving from delayed to queue removes from delayed only after it is added to queue
                // to make sure that 'isEmpty' and `nextTime` that check both of them
                // do not transiently report that both delayed and queue are empty during move
                delayed.removeFirstIf {
                    if (it.timeToExecute(now)) {
                        queue.addLast(it)
                        true // proceed with remove
                    } else
                        false
                } ?: break // quit loop when nothing more to remove
            }
        }
        // then process one event from queue
        (queue.removeFirstOrNull() as? QueuedTask)?.run()
        return nextTime
    }

    private fun Runnable.toQueuedTask(): QueuedTask =
            if (this is QueuedTask && isFresh) this else QueuedRunnableTask(this)

    internal fun enqueue(queuedTask: QueuedTask) {
        if (enqueueImpl(queuedTask)) {
            // todo: we should unpark only when this delayed task became first in the queue
            unpark()
        } else
            DefaultExecutor.enqueue(queuedTask)
    }

    private fun enqueueImpl(queuedTask: QueuedTask): Boolean {
        if (!canComplete) {
            queue.addLast(queuedTask)
            return true
        }
        return queue.addLastIf(queuedTask) { !isCompleted }
    }

    internal fun schedule(delayedTask: DelayedTask) {
        if (scheduleImpl(delayedTask)) {
            // todo: we should unpark only when this delayed task became first in the queue
            unpark()
        } else
            DefaultExecutor.schedule(delayedTask)
    }

    private fun scheduleImpl(delayedTask: DelayedTask): Boolean {
        if (!canComplete) {
            delayed.addLast(delayedTask)
            return true
        }
        return delayed.addLastIf(delayedTask) { !isCompleted }
    }

    internal fun removeDelayedImpl(delayedTask: DelayedTask) {
        delayed.remove(delayedTask)
    }

    protected fun clearAll() {
        while (true) queue.removeFirstOrNull() ?: break
        while (true) delayed.removeFirstOrNull() ?: break
    }

    protected fun rescheduleAllDelayed() {
        while (true) {
            val delayedTask = delayed.removeFirstOrNull() ?: break
            delayedTask.rescheduleOnShutdown()
        }
    }

    internal abstract class QueuedTask : LockFreeLinkedListNode(), Runnable

    private class QueuedRunnableTask(
            private val block: Runnable
    ) : QueuedTask() {
        override fun run() {
            block.run()
        }

        override fun toString(): String = block.toString()
    }

    internal abstract inner class DelayedTask(
            time: Long, timeUnit: TimeUnit
    ) : QueuedTask(), Comparable<DelayedTask>, DisposableHandle, ThreadSafeHeapNode {
        override var index: Int = -1
        var state = DELAYED
        /* @JvmField */
        val nanoTime: Long = timeSource.nanoTime() + timeUnit.toNanos(time)

        override fun compareTo(other: DelayedTask): Int {
            val dTime = nanoTime - other.nanoTime
            return when {
                dTime > 0 -> 1
                dTime < 0 -> -1
                else -> 0
            }
        }

        fun timeToExecute(now: Long): Boolean = now - nanoTime >= 0L

        fun rescheduleOnShutdown() = synchronized(delayed) {
            if (state != DELAYED) return@synchronized
            if (delayed.remove(this)) {
                state = RESCHEDULED
                DefaultExecutor.schedule(this)
            } else
                state = REMOVED
        }

        override final fun dispose() = synchronized(delayed) {
            when (state) {
                DELAYED -> delayed.remove(this)
                RESCHEDULED -> DefaultExecutor.removeDelayedImpl(this)
                else -> return@synchronized
            }
            state = REMOVED
        }

        override fun toString(): String = "Delayed[nanos=$nanoTime]"
    }

    private inner class DelayedResumeTask(
            time: Long, timeUnit: TimeUnit,
            private val cont: CancellableContinuation<Unit>
    ) : DelayedTask(time, timeUnit) {
        override fun run() {
            with(cont) { resumeUndispatched(Unit) }
        }
    }

    private inner class DelayedRunnableTask(
            time: Long, timeUnit: TimeUnit,
            private val block: Runnable
    ) : DelayedTask(time, timeUnit) {
        override fun run() {
            block.run()
        }

        override fun toString(): String = super.toString() + block.toString()
    }
}
