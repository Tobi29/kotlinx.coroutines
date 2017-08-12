package java.util.concurrent

// TODO: Use typealias and declare this in kotlinx.coroutines package
// Using impl typealias appears to be broken, generating bad bytecode
// (specifically referencing the alias location instead of the real
// declaration
// For types that make sense to get emulated on other platforms
// this trick seems to work well enough
// TODO: Add typealias declaration to point to this until above can be fixed

@Suppress("HEADER_WITHOUT_IMPLEMENTATION")
header enum class TimeUnit {
    NANOSECONDS,
    MICROSECONDS,
    MILLISECONDS,
    SECONDS,
    MINUTES,
    HOURS,
    DAYS;

    open fun convert(sourceDuration: Long,
                     sourceUnit: TimeUnit): Long

    open fun toNanos(duration: Long): Long

    open fun toMicros(duration: Long): Long

    open fun toMillis(duration: Long): Long

    open fun toSeconds(duration: Long): Long

    open fun toMinutes(duration: Long): Long

    open fun toHours(duration: Long): Long

    open fun toDays(duration: Long): Long
}
