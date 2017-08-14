package kotlinx.coroutines.experimental

header internal object DefaultExecutor : EventLoopBase, Runnable {

    override val canComplete: Boolean
    override val isCompleted: Boolean

    override fun run()

    override fun unpark()

    override fun isCorrectThread(): Boolean

    // used for tests
    internal fun ensureStarted()

    // used for tests
    internal fun shutdown(timeout: Long)
}
