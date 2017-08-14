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
}

internal class EventLoopImpl : EventLoopBase() {
    private var parentJob: Job? = null

    override val canComplete: Boolean get() = parentJob != null
    override val isCompleted: Boolean get() = parentJob?.isCompleted == true
    override fun isCorrectThread(): Boolean = true

    fun initParentJob(coroutine: Job) {
        require(this.parentJob == null)
        this.parentJob = coroutine
    }

    override fun unpark() {}

    fun shutdown() {
        // assert(isCompleted)
        // assert(isCorrectThread())
        // complete processing of all queued tasks
        while (processNextEvent() <= 0) { /* spin */
        }
        // reschedule the rest of delayed tasks
        rescheduleAllDelayed()
    }
}
