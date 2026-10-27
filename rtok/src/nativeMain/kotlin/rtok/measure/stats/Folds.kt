// Port of the remaining per-session folds of src/measure/stats.rs (edits, repeats, re-expands, repeat-read classes) and
// the Bash family / MCP group names.
@file:Suppress("TooManyFunctions", "LoopWithTooManyJumpStatements", "ReturnCount", "MagicNumber")

package rtok.measure.stats

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import rtok.MIN_PREFIX
import rtok.cmdStem
import rtok.measure.Parsed
import rtok.measure.ToolUse
import rtok.measure.field
import rtok.measure.samePath
import rtok.measure.str
import rtok.measure.toolPath
import rtok.measure.utf8Len
import rtok.plugins.cmd.Formatters
import rtok.plugins.read.ReadCache
import rtok.skipWord
import rtok.store.hexSha256

/** T58.3: every tool input counts toward the denominator; `Edit` and each `MultiEdit.edits[]` add their spans. */
internal fun foldEdits(
    row: EditRow,
    u: ToolUse,
) {
    row.toolInputBytes += utf8Len(SerdeJson.compact(u.input))
    val edits =
        when (u.name) {
            "Edit" -> listOf(u.input)
            "MultiEdit" -> (u.input.field("edits") as? JsonArray)?.toList().orEmpty()
            else -> return
        }
    for (e in edits) {
        row.calls++
        row.oldBytes += e.field("old_string").str()?.let(::utf8Len) ?: 0
        row.newBytes += e.field("new_string").str()?.let(::utf8Len) ?: 0
    }
}

/** T65.1: a result whose SHA-256 equals an earlier one in the session is a repeat. */
internal fun foldRepeat(
    row: RepeatRow,
    parsed: Parsed,
) {
    val seen = HashSet<String>()
    for (r in parsed.toolResults) {
        val bytes = utf8Len(r.content)
        row.resultBytes += bytes
        if (!seen.add(hexSha256(r.content.encodeToByteArray()))) {
            row.calls++
            row.bytes += bytes
        }
    }
}

private val NAMED = Regex("(?:expand:? (?:rtok expand )?|\\[rtok )([0-9a-f]{$MIN_PREFIX,64})\\b")
private val CALLED = Regex("rtok expand ([0-9a-f]{$MIN_PREFIX,64})")

/** T176: an expand (Bash `rtok expand <id>` or MCP `expand`) of an id an earlier result showed. */
internal fun foldExpandAfter(
    row: ExpandAfterRow,
    parsed: Parsed,
) {
    val results = resultTexts(parsed)
    // id prefix → bytes of the result that showed it, null once counted.
    val shown = HashMap<String, Long?>()

    fun key(id: String) = id.take(MIN_PREFIX)
    for (u in parsed.toolUses) {
        val content = results[u.id].orEmpty()
        val asked =
            when {
                u.name.endsWith("expand") -> {
                    u.input.field("id").str()
                }

                u.name == "Bash" -> {
                    CALLED
                        .find(
                            u.input
                                .field("command")
                                .str()
                                .orEmpty(),
                        )?.groupValues
                        ?.get(1)
                }

                else -> {
                    null
                }
            }
        if (asked != null && shown.containsKey(key(asked))) {
            row.calls++
            row.bytes += utf8Len(content)
            row.shownBytes += shown[key(asked)] ?: 0
            shown[key(asked)] = null
            continue
        }
        for (m in NAMED.findAll(content)) {
            val k = key(m.groupValues[1])
            if (!shown.containsKey(k)) shown[k] = utf8Len(content)
        }
    }
}

/** A native Read result carrying rtok's own trace: a deny reason, a cache-hit message, or an archive/cmd trailer. */
internal fun hasRtokMarker(content: String) = isReadDeny(content) || ReadCache.DEDUP_PREFIX in content || content.startsWith("[archived ") || "[rtok " in content

private val WRITE_SYMBOLS = listOf(">", "sed -i", "perl -i", "perl -pi", "tee ")
private val WRITE_WORDS = listOf("mv", "cp", "rm", "touch", "patch")

/** Blanket writers: can rewrite any tracked file without naming it. */
private val BLANKET_WRITERS =
    listOf(
        "git checkout",
        "git restore",
        "git stash",
        "git reset",
        "git rebase",
        "git merge",
        "git pull",
        "git apply",
        "git cherry-pick",
        "cargo fmt",
        "rustfmt",
        "oxfmt",
        "prettier --write",
    )

private fun hasWord(
    cmd: String,
    w: String,
) = cmd.splitBy { it.isWhitespace() || it == '&' || it == '|' || it == ';' }.any { it == w }

private inline fun String.splitBy(crossinline sep: (Char) -> Boolean): List<String> {
    val out = ArrayList<String>()
    var start = 0
    for (i in indices) {
        if (sep(this[i])) {
            out += substring(start, i)
            start = i + 1
        }
    }
    out += substring(start)
    return out
}

/** T179 "changed": a Bash call with a write signal that names the path (or a blanket writer). */
internal fun bashTouches(
    cmd: String,
    path: String,
): Boolean {
    if (BLANKET_WRITERS.any { it in cmd }) return true
    val named = path in cmd || path.substringAfterLast('/').takeIf { it.isNotEmpty() && it != ".." && it != "." }?.let { it in cmd } == true
    return named && (WRITE_SYMBOLS.any { it in cmd } || WRITE_WORDS.any { hasWord(cmd, it) })
}

private class Last(
    val path: String,
    var offset: Long?,
    var limit: Long?,
    var touched: Boolean,
)

/** T179: each repeat native Read classed fired / changed / ranged / declined / hook_absent; `subagent` comes from `collect`. */
@Suppress("CyclomaticComplexMethod")
internal fun foldRepeatReads(
    row: RepeatReadsRow,
    parsed: Parsed,
) {
    val results = resultTexts(parsed)
    val sawRtok = results.values.any(::hasRtokMarker)
    val paths = ArrayList<Last>()

    fun u64(v: kotlinx.serialization.json.JsonElement?) = (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
    for (u in parsed.toolUses) {
        when (u.name) {
            "Edit", "Write", "MultiEdit" -> {
                toolPath(u.input)?.let { path -> paths.firstOrNull { samePath(it.path, path) }?.touched = true }
            }

            "Bash" -> {
                val cmd =
                    u.input
                        .field("command")
                        .str()
                        .orEmpty()
                paths.filter { bashTouches(cmd, it.path) }.forEach { it.touched = true }
            }

            "Read" -> {
                val path = toolPath(u.input) ?: continue
                val content = results[u.id].orEmpty()
                val offset = u64(u.input.field("offset"))
                val limit = u64(u.input.field("limit"))
                val p = paths.firstOrNull { samePath(it.path, path) }
                if (p == null) {
                    paths += Last(path, offset, limit, false)
                    continue
                }
                val c =
                    when {
                        hasRtokMarker(content) -> row.fired
                        p.touched -> row.changed
                        p.offset != offset || p.limit != limit -> row.ranged
                        sawRtok -> row.declined
                        else -> row.hookAbsent
                    }
                c.calls++
                c.bytes += utf8Len(content)
                p.offset = offset
                p.limit = limit
                p.touched = false
            }
        }
    }
}

/** The family a Bash command buckets under: env assignments and `cd … &&` peeled, `mise exec --` seen through. */
fun bashFamily(cmd: String): String {
    var s = cmd.trim()
    while (true) {
        val next = stripPrefixEnv(s) ?: stripPrefixCd(s)
        if (next == null || next == s) break
        s = next
    }
    val words = s.split(WS).filter { it.isNotEmpty() }
    val first = words.firstOrNull() ?: "other"
    return cmdStem(Formatters.visibleArgv(words).firstOrNull() ?: first)
}

private fun stripPrefixEnv(s: String): String? {
    val t = s.trimStart()
    val end = t.indexOfFirst { !(it.code < 0x80 && it.isLetterOrDigit()) && it != '_' }
    if (end <= 0 || t[end] != '=') return null
    return skipWord(t.substring(end + 1))
}

private fun stripPrefixCd(s: String): String? {
    val t = s.trimStart()
    if (!t.startsWith("cd")) return null
    var after = t.substring(2)
    if (!(after.startsWith(' ') || after.startsWith('\t'))) return null
    after = after.trimStart()
    val rest = if (after.startsWith("&&")) after else skipWord(after) ?: return null
    if (!rest.startsWith("&&")) return null
    return rest.substring(2).trimStart()
}

/** `mcp__<group>__tool` → `<group>`. */
internal fun mcpGroup(name: String): String? {
    if (!name.startsWith("mcp__")) return null
    val rest = name.removePrefix("mcp__")
    return rest.substringBefore("__")
}
