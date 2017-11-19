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

internal actual interface TimeSource {
    actual fun nanoTime(): Long
    actual fun trackTask(block: Runnable): Runnable
    actual fun unTrackTask()
    actual fun registerTimeLoopThread()
    actual fun unregisterTimeLoopThread()
    fun parkNanos(blocker: Any, nanos: Long) // should return immediately when nanos <= 0
    fun unpark(thread: Thread)
}

internal object DefaultTimeSource : TimeSource {
    override fun nanoTime(): Long = System.nanoTime()
    override fun trackTask(block: Runnable): Runnable = block
    override fun unTrackTask() {}
    override fun registerTimeLoopThread() {}
    override fun unregisterTimeLoopThread() {}

    override fun parkNanos(blocker: Any, nanos: Long) {
        LockSupport.parkNanos(blocker, nanos)
    }

    override fun unpark(thread: Thread) {
        LockSupport.unpark(thread)
    }
}

internal actual var timeSource: TimeSource = DefaultTimeSource

// actual typealias TimeUnit = java.util.concurrent.TimeUnit

typealias JTimeUnit = java.util.concurrent.TimeUnit

actual enum class TimeUnit(val java: JTimeUnit) {
    NANOSECONDS(JTimeUnit.NANOSECONDS),
    MICROSECONDS(JTimeUnit.MICROSECONDS),
    MILLISECONDS(JTimeUnit.MILLISECONDS),
    SECONDS(JTimeUnit.SECONDS),
    MINUTES(JTimeUnit.MINUTES),
    HOURS(JTimeUnit.HOURS),
    DAYS(JTimeUnit.DAYS);

    actual open fun convert(sourceDuration: Long,
                            sourceUnit: TimeUnit) =
            java.convert(sourceDuration, sourceUnit.java)

    actual open fun toNanos(duration: Long) =
            java.toNanos(duration)

    actual open fun toMicros(duration: Long) =
            java.toMicros(duration)

    actual open fun toMillis(duration: Long) =
            java.toMillis(duration)

    actual open fun toSeconds(duration: Long) =
            java.toSeconds(duration)

    actual open fun toMinutes(duration: Long) =
            java.toMinutes(duration)

    actual open fun toHours(duration: Long) =
            java.toHours(duration)

    actual open fun toDays(duration: Long) =
            java.toDays(duration)
}
