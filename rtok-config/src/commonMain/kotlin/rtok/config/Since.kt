// Port of crates/rtok-config/src/since.rs — `<n>`, `<n>d` or `<n>h` windows (bare n = days).
package rtok.config

class ConfigException(message: String) : Exception(message)

/** `<n>`, `<n>d` or `<n>h` from the `--since` flag, in seconds. */
fun parseSince(s: String): Long = parseSinceFrom(s, "--since")

/** [parseSince] for a value read from [source]; the error names where the bad value came from. */
fun parseSinceFrom(s0: String, source: String): Long {
    val s = s0.trim()
    val cut = s.indexOfFirst { it !in '0'..'9' }.let { if (it < 0) s.length else it }
    val n = s.substring(0, cut).toULongOrNull() ?: throw ConfigException("bad $source $s")
    val perUnit: ULong = when (s.substring(cut)) { "", "d" -> 86_400u; "h" -> 3_600u; else -> throw ConfigException("bad $source unit in $s") }
    if (n != 0uL && n > ULong.MAX_VALUE / perUnit) throw ConfigException("$source $s is out of range")
    val secs = n * perUnit
    if (secs > Long.MAX_VALUE.toULong()) throw ConfigException("$source $s is out of range")
    return secs.toLong()
}
