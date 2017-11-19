package kotlinx.coroutines.experimental

impl open class CancellationException : IllegalStateException {
    actual constructor() : super()

    actual constructor(message: String) : super(message)
}