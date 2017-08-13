package kotlinx.coroutines.experimental

import java.lang.Runnable

header internal interface TimeSource {
    fun nanoTime(): Long
    fun trackTask(block: Runnable): Runnable
    fun unTrackTask()
    fun registerTimeLoopThread()
    fun unregisterTimeLoopThread()
    fun parkNanos(blocker: Any, nanos: Long) // should return immediately when nanos <= 0
}

header internal var timeSource: TimeSource
