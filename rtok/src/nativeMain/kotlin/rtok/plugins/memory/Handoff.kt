// Port of src/plugins/memory/handoff.rs — budgeted session digest for sub-agents: the files this
// session read or edited (from the hook ledger), the enclosing symbol ranges, archive pointers.
package rtok.plugins.memory

import kotlinx.serialization.json.*
import rtok.config.Memory
import rtok.plugin.fitBudget
import rtok.plugin.sdk.*
import rtok.shortId
import rtok.sys.canonicalize
import rtok.sys.join
import rtok.sys.readToString

private const val SHARE = "23 K of 2.83 M (0.8%)"
private const val INSTRUCTIONS =
    "Read a slice with rtok read(mode=lines, range=a-b) before the whole file.\n" +
        "Get the archived body with rtok expand <id>; answer with path:line citations."
private const val LEDGER_SCAN_ROWS = 100L
private const val MAX_RANGES = 3

internal fun memoryConfig(cx: Ctx) = cx.pluginConfig("memory", Memory.serializer(), Memory())

/** MCP `handoff`: the brief, or why it is off. Always records one `handoff` measurement. */
fun handoff(
    cx: Ctx,
    budgetTokens: UInt,
): String {
    val text =
        if (!memoryConfig(cx).handoff) {
            "handoff disabled: Agent/Task results were $SHARE on the measured workload; enable [plugins.memory] handoff after sub-agents exceed 5 %."
        } else {
            buildBrief(cx, budgetTokens, "") ?: "handoff: nothing read or edited yet."
        }
    runCatching { cx.record(Measurement("memory", "handoff", 0u, text.encodeToByteArray().size.toULong(), 0u, cx.estimate(text, Class.Prose), SHARE, null)) }
    return text
}

fun handoffTool() =
    ToolDef(
        "handoff",
        "Budgeted session digest for sub-agents (off by default).",
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("budget_tokens") { put("type", "integer") } }
            putJsonArray("required") { add("budget_tokens") }
        },
    )

private class Range(
    val from: Int,
    val to: Int,
    val name: String,
) {
    override fun equals(other: Any?) = other is Range && other.from == from && other.to == to && other.name == name

    override fun hashCode() = (from * 31 + to) * 31 + name.hashCode()
}

private class Pointer(
    val path: String,
    val archiveId: String?,
    val ranges: List<Range>,
)

private sealed interface Touch {
    class Lines(
        val from: Int,
        val to: Int,
    ) : Touch

    class Text(
        val s: String,
    ) : Touch
}

private class Touched(
    val path: String,
    val cwd: String,
    val touches: MutableList<Touch> = mutableListOf(),
)

private fun JsonElement?.str(k: String) = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.int(k: String) = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun ledger(cx: Ctx): List<Pointer> {
    val seen = HashMap<String, Int>()
    val touched = mutableListOf<Touched>()
    val bodies = runCatching { cx.recentHookInputsForEvent("PreToolUse", LEDGER_SCAN_ROWS) }.getOrNull() ?: return emptyList()
    for (b in bodies) {
        val v = runCatching { Json.parseToJsonElement(b) }.getOrNull() ?: continue
        val tool = v.str("tool_name") ?: ""
        val cwd = v.str("cwd") ?: "."
        val input = (v as? JsonObject)?.get("tool_input")

        fun note(path: String) =
            seen.getOrPut(path) {
                touched += Touched(path, cwd)
                touched.size - 1
            }
        if (tool == "apply_patch") {
            input.str("command")?.let(::patchPaths)?.forEach { note(underCwd(cwd, it)) }
            continue
        }
        if (tool !in setOf("Read", "Edit", "Write")) continue
        val path = input.str("file_path") ?: if ((input as? JsonObject)?.containsKey("file_path") == true) continue else input.str("path") ?: continue
        val at = note(path)
        val touch: Touch? =
            when {
                tool == "Read" && (input.int("offset") != null || input.int("limit") != null) -> {
                    val from = maxOf(input.int("offset") ?: 1, 1)
                    val span = maxOf(input.int("limit") ?: 2000, 1) - 1
                    val to = if (Long.MAX_VALUE - from < span) Long.MAX_VALUE else from + span
                    Touch.Lines(from.toI32(), to.toI32())
                }

                tool == "Edit" -> {
                    input.str("new_string")?.takeIf { it.isNotEmpty() }?.let { Touch.Text(it) }
                }

                else -> {
                    null
                }
            }
        touch?.let { touched[at].touches += it }
    }
    return touched.map { Pointer(it.path, lastFullReadId(cx, it.path), symbolRanges(cx, it.cwd, it.path, it.touches)) }
}

private fun Long.toI32() = if (this > Int.MAX_VALUE) Int.MAX_VALUE else toInt()

private val PATCH_HEADERS = listOf("*** Add File: ", "*** Update File: ", "*** Delete File: ", "*** Move to: ")

internal fun patchPaths(patch: String): List<String> =
    patch
        .lines()
        .mapNotNull { l -> PATCH_HEADERS.firstNotNullOfOrNull { h -> l.takeIf { it.startsWith(h) }?.removePrefix(h) } }
        .map { it.trim() }
        .filter { it.isNotEmpty() }

internal fun underCwd(
    cwd: String,
    path: String,
): String {
    if (path.startsWith('/') || path.startsWith('\\')) return path
    val sep = if ('\\' in cwd && '/' !in cwd) '\\' else '/'
    return cwd.trimEnd('/', '\\') + sep + path.replace('/', sep).replace('\\', sep)
}

private fun symbolRanges(
    cx: Ctx,
    cwd: String,
    path: String,
    touches: List<Touch>,
): List<Range> {
    if (touches.isEmpty()) return emptyList()

    fun canon(p: String) = (canonicalize(p) ?: p).replace('\\', '/')
    val abs = join(cwd, path)
    val root = canon(cwd)
    val file = canon(abs)
    val rel =
        file
            .takeIf { it.startsWith(root) }
            ?.substring(root.length)
            ?.takeIf { it.startsWith('/') }
            ?.substring(1) ?: return emptyList()
    val defs = runCatching { cx.symbolFileDefs(root, rel) }.getOrDefault(emptyList())
    if (defs.isEmpty()) return emptyList()
    val text = if (touches.any { it is Touch.Text }) runCatching { readToString(abs) }.getOrNull() else null
    val out = mutableListOf<Range>()
    for (t in touches) {
        val (from, to) =
            when (t) {
                is Touch.Lines -> {
                    t.from to t.to
                }

                is Touch.Text -> {
                    val i = text?.indexOf(t.s)?.takeIf { it >= 0 } ?: continue
                    val f = text.substring(0, i).count { it == '\n' } + 1
                    f to f + t.s.trimEnd('\n').count { it == '\n' }
                }
            }
        val d = defs.filter { it.line <= from && it.endLine >= to }.minByOrNull { it.endLine - it.line } ?: continue
        val r = Range(d.line, d.endLine, d.a)
        if (r !in out) out += r
        if (out.size == MAX_RANGES) break
    }
    return out
}

private fun lastFullReadId(
    cx: Ctx,
    path: String,
): String? {
    val abs = canonicalize(path) ?: return null
    return runCatching { cx.getReadCache("$abs\tfull\t") }.getOrNull()?.first
}

/** The sub-agent brief within [budgetTokens]; files named in [hint] first. Null when nothing was touched. */
fun buildBrief(
    cx: Ctx,
    budgetTokens: UInt,
    hint: String,
): String? {
    val pointers = ledger(cx).sortedBy { it.path !in hint }
    if (pointers.isEmpty()) return null

    fun digest(ranged: Boolean) =
        pointers.joinToString("\n") { p ->
            var line = p.path
            if (ranged && p.ranges.isNotEmpty()) line += ":" + p.ranges.joinToString(", ") { "${it.from}-${it.to} ${it.name}" }
            p.archiveId?.let { "$line — rtok expand ${shortId(it)}" } ?: line
        } + "\n$INSTRUCTIONS"
    val full = digest(true)
    val archived = runCatching { cx.putArchive(full.encodeToByteArray()) }.getOrNull()
    val trailer =
        archived?.let {
            val s = shortId(it)
            "\n[rtok $s · expand: rtok expand $s]"
        } ?: ""
    val tEst = cx.estimate(trailer, Class.Prose)
    val room = if (budgetTokens > tEst) budgetTokens - tEst else 0u
    val shown = if (cx.estimate(full, Class.Prose) <= room) full else digest(false)
    val text = fitBudget(cx, shown, Class.Prose, room) + trailer
    runCatching { cx.record(Measurement("memory", "brief", 0u, text.encodeToByteArray().size.toULong(), 0u, cx.estimate(text, Class.Prose), archived, null)) }
    return text
}
