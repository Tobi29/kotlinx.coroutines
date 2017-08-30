package java.util.concurrent

impl enum class TimeUnit(private val base: Long) {
    NANOSECONDS(1L),
    MICROSECONDS(1000L),
    MILLISECONDS(1000L * 1000L),
    SECONDS(1000L * 1000L * 1000L),
    MINUTES(1000L * 1000L * 1000L * 60L),
    HOURS(1000L * 1000L * 1000L * 60L * 60L),
    DAYS(1000L * 1000L * 1000L * 60L * 60L * 24L);

    impl open fun convert(sourceDuration: Long,
                          sourceUnit: TimeUnit) =
            scale(sourceDuration, sourceUnit.base, base)

    impl open fun toNanos(duration: Long) =
            scale(duration, base, NANOSECONDS.base)

    impl open fun toMicros(duration: Long) =
            scale(duration, base, MICROSECONDS.base)

    impl open fun toMillis(duration: Long) =
            scale(duration, base, MILLISECONDS.base)

    impl open fun toSeconds(duration: Long) =
            scale(duration, base, SECONDS.base)

    impl open fun toMinutes(duration: Long) =
            scale(duration, base, MINUTES.base)

    impl open fun toHours(duration: Long) =
            scale(duration, base, HOURS.base)

    impl open fun toDays(duration: Long) =
            scale(duration, base, DAYS.base)

    private fun scale(duration: Long,
                      base: Long,
                      destination: Long) =
            if (destination < base) scaleUp(duration, base, destination)
            else if (destination > base) scaleDown(duration, base, destination)
            else duration

    private fun scaleUp(duration: Long,
                        base: Long,
                        destination: Long): Long {
        val scale = base / destination
        val max = Long.MAX_VALUE / scale
        return if (duration > max) Long.MAX_VALUE
        else if (duration < -max) Long.MIN_VALUE
        else duration * scale
    }

    private fun scaleDown(duration: Long,
                          base: Long,
                          destination: Long): Long {
        val scale = destination / base
        return duration / scale
    }
}
