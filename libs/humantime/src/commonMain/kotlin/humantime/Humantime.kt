// Standalone port of the Rust `humantime::parse_duration` grammar (humantime 2.x), kept as its own
// module: no maintained Kotlin/Native library parses this grammar. Used for `[agents] idle`, `--idle`, `--stale-lock`.
package humantime

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class DurationParseException(message: String) : IllegalArgumentException(message)

private fun unitNanos(unit: String): Pair<Long, Long>? = when (unit) { // (seconds, nanos) per 1 unit
    "nanos", "nsec", "ns" -> 0L to 1L
    "usec", "us", "µs" -> 0L to 1_000L
    "millis", "msec", "ms" -> 0L to 1_000_000L
    "seconds", "second", "secs", "sec", "s" -> 1L to 0L
    "minutes", "minute", "min", "mins", "m" -> 60L to 0L
    "hours", "hour", "hr", "hrs", "h" -> 3_600L to 0L
    "days", "day", "d" -> 86_400L to 0L
    "weeks", "week", "wk", "wks", "w" -> 604_800L to 0L
    "months", "month", "M" -> 2_630_016L to 0L
    "years", "year", "yr", "yrs", "y" -> 31_557_600L to 0L
    else -> null
}

/** humantime 2.x: one or more `<number><unit>` groups, optionally separated by whitespace. */
fun parseHumanDuration(text: String): Duration {
    val s = text.trim()
    if (s.isEmpty()) throw DurationParseException("value was empty")
    var i = 0; var secs = 0L; var nanos = 0L
    while (i < s.length) {
        while (i < s.length && s[i].isWhitespace()) i++
        val numStart = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == numStart) throw DurationParseException("expected number at $numStart")
        val n = s.substring(numStart, i).toLongOrNull() ?: throw DurationParseException("number is too large")
        val unitStart = i
        while (i < s.length && !s[i].isWhitespace() && s[i] !in '0'..'9' && s[i] != '+') i++
        val unit = s.substring(unitStart, i)
        if (unit.isEmpty()) throw DurationParseException("time unit needed, for example ${n}sec or ${n}ms")
        val (us, un) = unitNanos(unit) ?: throw DurationParseException(
            "unknown time unit \"$unit\", supported units: ns, us, ms, sec, min, hours, days, weeks, months, years (and few variations)")
        secs = Checked.addExact(secs, Checked.multiplyExact(n, us)); nanos += n * un
        if (i < s.length && s[i] == '+') i++
    }
    return secs.seconds + nanos.nanoseconds
}

private object Checked {
    fun addExact(a: Long, b: Long): Long { val r = a + b; if (((a xor r) and (b xor r)) < 0) throw DurationParseException("number is too large"); return r }
    fun multiplyExact(a: Long, b: Long): Long { if (a != 0L && b != 0L && (a * b) / b != a) throw DurationParseException("number is too large"); return a * b }
}
