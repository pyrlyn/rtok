// Port of src/measure/discover.rs — `rtok discover` (T688): the shell commands agents ran that no `cmd` rule or
// formatter covers, ranked by the output tokens the agent read. Input is the `PostToolUse` bodies the hook already
// stores; nothing is re-run and nothing is claimed saved: an unfiltered command has no `Measurement` row.
package rtok.measure

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import rtok.Col
import rtok.canonicalToolName
import rtok.measure.stats.bashFamily
import rtok.plugins.cmd.Formatters
import rtok.plugins.cmd.Settings
import rtok.stripWrap
import rtok.table

/** One command family with no rule and no formatter. */
@Serializable
data class Gap(
    val family: String,
    val calls: Long,
    /** Output bytes the agent read across all calls (after any default cut, if `rtok run` ran). */
    val bytes: Long,
    @SerialName("est_tokens") val estTokens: Long,
    /** The command behind the largest single output, first line, capped. */
    val example: String,
)

@Serializable
data class Discovery(
    /** Bash calls read. */
    val scanned: Long,
    /** Bash calls whose family has a rule or a formatter. */
    val covered: Long,
    /** Hook rows with no readable body (over `core.call_io_inline_bytes`, or not JSON). */
    val unreadable: Long,
    val gaps: List<Gap>,
) {
    fun toTable(): String {
        val head = "bash calls $scanned  covered $covered  unreadable rows $unreadable\n"
        if (gaps.isEmpty()) return head + "no uncovered command family\n"
        val cols = listOf(Col.left(16), Col.right(7), Col.right(12), Col.right(12), Col.left(8))
        val rows = listOf(listOf("family", "calls", "bytes", "est_tokens", "largest output")) + gaps.map { listOf(it.family, "${it.calls}", "${it.bytes}", "${it.estTokens}", it.example) }
        return head + table(cols, rows)
    }
}

/** Longest `example` kept, in chars. */
private const val EXAMPLE_CHARS = 80

/** Bytes the agent was shown: a plain string, `stdout` + `stderr`, or the serialized value. */
internal fun outputBytes(response: JsonElement): Long {
    response.str()?.let { return utf8Len(it) }
    val out = response.field("stdout").str()
    val err = response.field("stderr").str()
    return if (out == null && err == null) utf8Len(response.toString()) else (out?.let(::utf8Len) ?: 0) + (err?.let(::utf8Len) ?: 0)
}

private class Family {
    var calls = 0L
    var bytes = 0L
    var largest = 0L
    var command = ""
}

object Discover {
    /** Rank the uncovered families in [bodies] (`PostToolUse` hook JSON). `rtok`'s own commands are skipped: their output is already shaped. */
    fun rank(
        bodies: List<String>,
        settings: Settings,
        limit: Int,
    ): Discovery {
        val parsed = bodies.map(::parseJson)
        val calls =
            parsed.filterNotNull().mapNotNull { v ->
                val raw = v.pointer("/tool_input/command").str()?.takeIf { canonicalToolName(v.field("tool_name").str().orEmpty()) == "Bash" }
                raw?.let(::stripWrap)?.let { command -> Triple(v, command, bashFamily(command)) }?.takeIf { it.third != "rtok" }
            }
        var covered = 0L
        val byFamily = HashMap<String, Family>()
        for ((v, command, family) in calls) {
            if (Formatters.filterKind(settings, family) != "default") {
                covered++
            } else {
                val bytes = v.field("tool_response")?.let(::outputBytes) ?: 0
                val row = byFamily.getOrPut(family, ::Family)
                row.calls++
                row.bytes += bytes
                if (bytes > row.largest) {
                    row.largest = bytes
                    row.command = command
                }
            }
        }
        val gaps =
            byFamily
                .map { (family, r) -> Gap(family, r.calls, r.bytes, estTokens(r.bytes), firstChars(r.command.substringBefore('\n').removeSuffix("\r"), EXAMPLE_CHARS)) }
                .sortedWith(compareByDescending<Gap> { it.estTokens }.thenByDescending { it.calls }.thenBy { it.family })
                .take(limit)
        return Discovery(calls.size.toLong(), covered, parsed.count { it == null }.toLong(), gaps)
    }
}

/** Rust `chars().take(n)`: [n] code points, a surrogate pair counted once. */
private fun firstChars(
    s: String,
    n: Int,
): String {
    var i = 0
    var taken = 0
    while (i < s.length && taken < n) {
        i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
        taken++
    }
    return s.substring(0, i)
}
