package java.util.concurrent

impl open class CancellationException : IllegalStateException {
    constructor() : super()

    constructor(message: String) : super(message)
}