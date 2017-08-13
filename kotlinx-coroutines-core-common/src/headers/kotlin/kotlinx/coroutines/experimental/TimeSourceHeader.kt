package kotlinx.coroutines.experimental

import java.lang.Runnable

header internal interface TimeSource {
    fun nanoTime(): Long
    fun trackTask(block: Runnable): Runnable
    fun unTrackTask()
    fun registerTimeLoopThread()
    fun unregisterTimeLoopThread()
}

header internal var timeSource: TimeSource
