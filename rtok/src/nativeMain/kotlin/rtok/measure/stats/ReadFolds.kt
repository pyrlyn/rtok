// Port of the read-side folds of src/measure/stats.rs: changed re-reads (T58.1), whole-file Reads (T136), read-hook
// denies (T383) and Grep answers from the symbol index (T369.1).
@file:Suppress("LoopWithTooManyJumpStatements", "CyclomaticComplexMethod")

package rtok.measure.stats

import rtok.measure.Parsed
import rtok.measure.ToolUse
import rtok.measure.estTokens
import rtok.measure.field
import rtok.measure.samePath
import rtok.measure.str
import rtok.measure.toolPath
import rtok.measure.utf8Len
import rtok.plugins.graph.Tools
import rtok.plugins.guard.GrepSymbol
import rtok.plugins.read.ReadHook

/** How many tool calls after a deny count as "what the agent did next". */
internal const val DENY_FOLLOW_CALLS = 3

private val EDITS = setOf("Edit", "Write", "MultiEdit")

/** Each tool result's text by the id of the call it answers (a later duplicate id wins, as in a `BTreeMap` collect). */
internal fun resultTexts(parsed: Parsed): Map<String, String> = parsed.toolResults.associate { it.toolUseId to it.content }

private fun sizes(parsed: Parsed): Map<String, Long> = parsed.toolResults.associate { it.toolUseId to utf8Len(it.content) }

private class PathState(
    val path: String,
    var seen: Boolean,
    var dirty: Boolean,
)

/** T58.1: a native `Read` of a path already read, with an edit of it in between, is a changed re-read. */
internal fun foldReadDelta(
    row: ReadDeltaRow,
    parsed: Parsed,
) {
    val sizes = sizes(parsed)
    val paths = ArrayList<PathState>()
    for (u in parsed.toolUses) {
        val path = toolPath(u.input) ?: continue
        val known = paths.firstOrNull { samePath(it.path, path) }
        when (u.name) {
            "Read" -> {
                val bytes = sizes[u.id] ?: 0
                row.readBytes += bytes
                if (known != null) {
                    if (known.seen && known.dirty) {
                        row.calls++
                        row.bytes += bytes
                    }
                    known.seen = true
                    known.dirty = false
                } else {
                    paths += PathState(path, seen = true, dirty = false)
                }
            }

            in EDITS -> {
                if (known != null) known.dirty = true else paths += PathState(path, seen = false, dirty = true)
            }
        }
    }
}

/** T136: unranged Reads of outlined files at or above `native_max_bytes`, and whether an edit followed within the window. */
internal fun foldReadWhole(
    row: ReadWholeRow,
    parsed: Parsed,
    rp: Replay,
) {
    val sizes = sizes(parsed)
    row.resultBytes += sizes.values.sum()
    for ((i, u) in parsed.toolUses.withIndex()) {
        if (u.name != "Read") continue
        val bytes = sizes[u.id] ?: 0
        row.readBytes += bytes
        val ranged = u.input.field("offset") != null || u.input.field("limit") != null
        val path = toolPath(u.input) ?: continue
        if (ranged || bytes < maxOf(rp.readWholeMin, 1) || !rtok.plugins.read.supported(path)) continue
        row.calls++
        row.bytes += bytes
        val edited =
            parsed.toolUses
                .subList(i + 1, parsed.toolUses.size)
                .asSequence()
                .takeWhile { it.turn.toLong() <= u.turn.toLong() + rp.editWindowTurns }
                .any { e -> e.name in EDITS && toolPath(e.input)?.let { samePath(it, path) } == true }
        if (edited) {
            row.edited++
            row.editedBytes += bytes
        }
    }
}

internal fun isReadDeny(content: String) = ReadHook.REASON in content || ReadHook.DELTA_REASON in content

private fun isRtokMcpRead(name: String) = mcpGroup(name) == "rtok" && name.endsWith("__read")

/** T383: denied native Reads, then whether the agent went to ToolSearch, `rtok read`, or a native Read of the same path. */
internal fun foldReadDeny(
    row: ReadDenyRow,
    parsed: Parsed,
) {
    val results = resultTexts(parsed)
    for ((i, u) in parsed.toolUses.withIndex()) {
        val text = results[u.id] ?: continue
        if (u.name != "Read" || !isReadDeny(text)) continue
        row.calls++
        row.tokens += estTokens(utf8Len(text))
        val window = parsed.toolUses.drop(i + 1).take(DENY_FOLLOW_CALLS)
        if (window.takeWhile { it.name != "Read" && mcpGroup(it.name) != "rtok" }.any { it.name == "ToolSearch" }) row.thenToolsearch++
        val path = toolPath(u.input) ?: continue
        val next = window.firstOrNull { n -> (n.name == "Read" || isRtokMcpRead(n.name)) && toolPath(n.input)?.let { samePath(it, path) } == true }
        when {
            next == null -> {}

            next.name == "Read" -> {
                row.thenNative++
            }

            else -> {
                row.thenMcp++
            }
        }
    }
}

/** T369.1: Grep calls the guard answered from the index, and whether a same-name search or a read of a listed file followed. */
internal fun foldGrepSymbol(
    row: GrepSymbolRow,
    parsed: Parsed,
) {
    val results = resultTexts(parsed)
    for ((i, u) in parsed.toolUses.withIndex()) {
        if (u.name != "Grep") continue
        val text = results[u.id]?.takeIf { GrepSymbol.ANSWERED in it } ?: continue
        val name = GrepSymbol.symbolName(u.input) ?: continue
        row.calls++
        row.tokens += estTokens(utf8Len(text))
        val files =
            rtokLines(text).mapNotNull { l ->
                l
                    .trim()
                    .split(WS)
                    .firstOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(Tools::parseSymbolId)
                    ?.path
            }
        if (parsed.toolUses
                .drop(i + 1)
                .take(DENY_FOLLOW_CALLS)
                .any { sameNameFollowUp(it, name, files) }
        ) {
            row.followed++
        }
    }
}

internal val WS = Regex("\\s+")

private fun sameNameFollowUp(
    n: ToolUse,
    name: String,
    files: List<String>,
): Boolean {
    fun text(key: String) =
        n.input
            .field(key)
            .str()
            .orEmpty()
    return when {
        n.name == "Grep" -> name in text("pattern")
        n.name == "Bash" -> text("command").let { cmd -> bashFamily(cmd) in setOf("rg", "grep") && name in cmd }
        n.name == "Read" || isRtokMcpRead(n.name) -> toolPath(n.input)?.let { p -> files.any { f -> p == f || p.endsWith("/$f") } } == true
        else -> false
    }
}
