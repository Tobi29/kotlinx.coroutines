package kotlinx.coroutines.experimental

actual open class CancellationException : IllegalStateException {
    actual constructor() : super()

    actual constructor(message: String) : super(message)
}