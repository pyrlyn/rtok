// The small helpers src/measure/stats.rs shares with its readers (`est_tokens`, `pct`, `tool_path`, `same_path`) and
// Rust's `str::len`: a string's UTF-8 byte length, which every size column counts.
@file:Suppress("MagicNumber")

package rtok.measure

import kotlinx.serialization.json.JsonElement
import rtok.HostPaths

/** `research.md` §2: 4 chars per token, so the report stays comparable to that baseline. */
const val CHARS_PER_TOKEN = 4.0

private const val ONE_BYTE = 0x80
private const val TWO_BYTES = 0x800

/** Rust's `str::len`: UTF-8 bytes, without encoding the string. */
fun utf8Len(s: String): Long {
    var n = 0L
    var i = 0
    while (i < s.length) {
        val c = s[i].code
        n +=
            when {
                c < ONE_BYTE -> 1
                c < TWO_BYTES -> 2
                s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> 4L.also { i++ }
                else -> 3
            }
        i++
    }
    return n
}

/** `est_tokens`: bytes / 4, rounded up; 0 for 0. */
fun estTokens(bytes: Long): Long = if (bytes == 0L) 0 else kotlin.math.ceil(bytes / CHARS_PER_TOKEN).toLong()

/** `pct`: part over whole in percent; 0 for an empty whole. */
fun pct(
    part: Long,
    whole: Long,
): Double = if (whole == 0L) 0.0 else 100.0 * part.toDouble() / whole.toDouble()

/** `tool_path`: `file_path`, else `path`, trimmed; null when empty. */
fun toolPath(input: JsonElement?): String? = (input.field("file_path") ?: input.field("path")).str()?.trim()?.takeIf { it.isNotEmpty() }

/** `same_path` (`fs::same_spelling`). */
fun samePath(
    a: String,
    b: String,
) = HostPaths.sameSpelling(a, b)
