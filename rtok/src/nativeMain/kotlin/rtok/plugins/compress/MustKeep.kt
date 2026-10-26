// Port of src/plugins/compress/must_keep.rs — T385.9.1: the must-keep spans of an archived tool result
// and the guard that rejects a summary which drops one.
package rtok.plugins.compress

import rtok.sys.rustLines
import rtok.sys.takeScalars

/** The span kinds of the P28 fixture. */
val KINDS = listOf("path", "identifier", "error", "number", "command", "id")

/** Longest span kept whole, in chars. */
internal const val SPAN_CHARS = 200

data class Span(
    val kind: String,
    val text: String,
)

private val TOKENS: List<Pair<String, Regex>> =
    listOf(
        "path" to """[\w./\\-]*[\w-]\.[A-Za-z][A-Za-z0-9]{0,5}:\d+(?::\d+)?""",
        "path" to """\b[\w.-]+(?:/[\w.-]+)+\.[A-Za-z]{1,6}\b""",
        "path" to """^@@ .*? @@""",
        "identifier" to """`[^`\n]{1,80}`""",
        "identifier" to """\b[A-Za-z_]\w*(?:::\w+)+""",
        "identifier" to """\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\b""",
        "identifier" to """\b(?:fn|def|class|struct|enum|trait|impl|func) [A-Za-z_]\w*""",
        "error" to """\bE\d{4}\b|\bos error \d+""",
        "error" to """\b(?:status|returned|exited with status|code) \d{3}\b""",
        "number" to """\b\d+(?:\.\d+)?\s?(?:ms|s|MB|KB|GB|%)\b""",
        "number" to """\b\d+\.\d{2,}\b|\b\d[\d_]{4,}\b""",
        "number" to """\b\w+=\d+\b|"\w+": \d+""",
        "number" to """\b\d+ (?:passed|failed|ignored|errors?|warnings?|attempts?|retries)\b""",
        "id" to """\b[0-9a-f]{12,}\b|\b[a-z]{2,6}_[0-9A-Za-z]*\d[0-9A-Za-z]{3,}\b""",
        "id" to """\b\d{1,3}(?:\.\d{1,3}){3}:\d+\b""",
    ).map { (k, re) -> k to Regex(re) }

private val ERROR_LINE = Regex("""^(?:error|Error|ERROR|fatal|FATAL|FAILED|panicked|thread '[^']*' panicked)\b""")

/** Every must-keep span of [text], each once, in order of appearance. */
fun spans(text: String): List<Span> {
    val seen = HashSet<String>()
    val out = ArrayList<Span>()

    fun push(
        kind: String,
        s: String,
    ) {
        val t = s.trim().takeScalars(SPAN_CHARS)
        if (t.isNotEmpty() && seen.add(t)) out += Span(kind, t)
    }
    for (raw in text.rustLines()) {
        val line = raw.trim()
        if (ERROR_LINE.containsMatchIn(line)) push("error", line)
        if (line.startsWith("$ ")) push("command", line.removePrefix("$ "))
        for ((kind, re) in TOKENS) {
            for (m in re.findAll(line)) {
                push(if (kind == "identifier" && isCommand(m.value)) "command" else kind, m.value)
            }
        }
    }
    return out
}

/** A backticked invocation with a flag in it (`acme settle --retry x`) is a command. */
private fun isCommand(token: String) = token.startsWith('`') && " -" in token

/** The spans [summary] does not contain verbatim; empty = it keeps them all. */
fun dropped(
    spans: List<Span>,
    summary: String,
): List<Span> = spans.filter { it.text !in summary }
