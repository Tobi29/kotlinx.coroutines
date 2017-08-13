package java.util.concurrent

// TODO: Use typealias and declare this in kotlinx.coroutines package
// Using impl typealias appears to be broken, generating bad bytecode
// (specifically referencing the alias location instead of the real
// declaration
// For types that make sense to get emulated on other platforms
// this trick seems to work well enough
// TODO: Add typealias declaration to point to this until above can be fixed

@Suppress("HEADER_WITHOUT_IMPLEMENTATION")
header open class CancellationException : IllegalStateException {
    constructor()

    constructor(message: String)
}
