package kotlinx.coroutines.experimental

internal expect interface TimeSource {
    fun nanoTime(): Long
    fun trackTask(block: Runnable): Runnable
    fun unTrackTask()
    fun registerTimeLoopThread()
    fun unregisterTimeLoopThread()
}

internal expect var timeSource: TimeSource
