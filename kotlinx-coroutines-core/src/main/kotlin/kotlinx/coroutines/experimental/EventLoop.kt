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

import java.util.concurrent.locks.LockSupport

/**
 * Implemented by [CoroutineDispatcher] implementations that have event loop inside and can
 * be asked to process next event from their event queue.
 *
 * It may optionally implement [Delay] interface and support time-scheduled tasks. It is used by [runBlocking] to
 * continue processing events when invoked from the event dispatch thread.
 */
impl public interface EventLoop {
    /**
     * Processes next event in this event loop.
     *
     * The result of this function is to be interpreted like this:
     * * `<= 0` -- there are potentially more events for immediate processing;
     * * `> 0` -- a number of nanoseconds to wait for next scheduled event;
     * * [Long.MAX_VALUE] -- no more events, or was invoked from the wrong thread.
     */
    impl public fun processNextEvent(): Long

    /** @suppress **Deprecated **/
    @Deprecated(message = "Companion object to be removed, no replacement")
    public companion object Factory {
        /** @suppress **Deprecated **/
        @Deprecated("Replaced with top-level function", level = DeprecationLevel.HIDDEN)
        public operator fun invoke(thread: Thread = Thread.currentThread(), parentJob: Job? = null): CoroutineDispatcher =
                EventLoopImpl(thread).apply {
                    if (parentJob != null) initParentJob(parentJob)
                }
    }
}

/**
 * Creates a new event loop that is bound the specified [thread] (current thread by default) and
 * stops accepting new events when [parentJob] completes. Every continuation that is scheduled
 * onto this event loop unparks the specified thread via [LockSupport.unpark].
 *
 * The main event-processing loop using the resulting `eventLoop` object should look like this:
 * ```
 * while (needsToBeRunning) {
 *     if (Thread.interrupted()) break // or handle somehow
 *     LockSupport.parkNanos(eventLoop.processNextEvent()) // event loop will unpark
 * }
 * ```
 */
public fun EventLoop(thread: Thread = Thread.currentThread(), parentJob: Job? = null): CoroutineDispatcher =
        EventLoopImpl(thread).apply {
            if (parentJob != null) initParentJob(parentJob)
        }

internal class EventLoopImpl(
        private val thread: Thread
) : EventLoopBase() {
    private var parentJob: Job? = null

    override val canComplete: Boolean get() = parentJob != null
    override val isCompleted: Boolean get() = parentJob?.isCompleted == true
    override fun isCorrectThread(): Boolean = Thread.currentThread() === thread

    fun initParentJob(coroutine: Job) {
        require(this.parentJob == null)
        this.parentJob = coroutine
    }

    override fun unpark() {
        if (Thread.currentThread() !== thread)
            timeSource.unpark(thread)
    }

    fun shutdown() {
        assert(isCompleted)
        assert(isCorrectThread())
        // complete processing of all queued tasks
        while (processNextEvent() <= 0) { /* spin */
        }
        // reschedule the rest of delayed tasks
        rescheduleAllDelayed()
    }
}

