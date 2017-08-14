package kotlinx.coroutines.experimental

header internal interface TimeSource {
    fun nanoTime(): Long
    fun trackTask(block: Runnable): Runnable
    fun unTrackTask()
    fun registerTimeLoopThread()
    fun unregisterTimeLoopThread()
}

header internal var timeSource: TimeSource
