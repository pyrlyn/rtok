// Port of src/plugins/memory/observe.rs — mechanical observation of a tool call (T454). Shape follows
// agentmemory `compress-synthetic.ts` (Apache-2.0): short title, type from the tool name, narrative
// capped at 400 characters. No model call.
package rtok.plugins.memory

import kotlinx.serialization.json.*
import rtok.plugin.sdk.*
import rtok.store.SerdeJson
import rtok.store.hexSha256

private const val TITLE_MAX = 80
internal const val NARRATIVE_MAX = 400
private const val RRF_K = 60.0f
/** At most this many hits from one session before the list backfills. */
const val MAX_PER_SESSION = 3

/** One compressed tool call, ready to store. */
data class Synthesized(val obsType: String, val title: String, val narrative: String, val files: List<String>)

private val TYPE_WORDS = listOf(
    "web_fetch" to listOf("fetch", "http", "web"),
    "search" to listOf("grep", "search", "glob", "find"),
    "command_run" to listOf("bash", "shell", "exec", "run"),
    "file_edit" to listOf("edit", "update", "patch", "replace"),
    "file_write" to listOf("write", "create"),
    "file_read" to listOf("read", "view"),
    "subagent" to listOf("task", "agent"),
)

/** Map a tool name to an observation type (word breaks follow `inferType`). */
fun inferType(toolName: String?, hook: String, isError: Boolean): String {
    if (isError || hook == "post_tool_failure") return "error"
    if (hook == "prompt_submit") return "conversation"
    if (toolName.isNullOrEmpty()) return "other"
    val n = splitName(toolName)
    fun has(w: String) = n == w || n.startsWith("${w}_") || n.endsWith("_$w") || "_${w}_" in n
    return TYPE_WORDS.firstOrNull { (_, words) -> words.any(::has) }?.first ?: "other"
}

private fun splitName(name: String) = buildString {
    for ((i, c) in name.withIndex()) {
        if (c.isUpperCase() && i > 0 && name[i - 1].isLowerCase()) append('_')
        if (c == '-' || c.isWhitespace()) append('_') else append(if (c in 'A'..'Z') c.lowercaseChar() else c)
    }
}

private fun stringify(v: JsonElement): String = when {
    v is JsonNull -> ""
    v is JsonPrimitive && v.isString -> v.content
    else -> SerdeJson.encode(v)
}

/** At most [n] scalars; a cut ends in `…`. */
internal fun truncateChars(s: String, n: Int): String {
    val cs = s.scalars()
    return if (cs.size <= n) s else cs.take(maxOf(n - 1, 0)).joinToString("") + "…"
}

private fun extractFiles(toolName: String, input: JsonElement): List<String> {
    val obj = input as? JsonObject ?: return emptyList()
    fun str(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val lower = toolName.lowercase()
    val isSearch = listOf("grep", "glob", "search", "find", "ls", "list").any { it in lower } || str("pattern") != null || str("query") != null
    val keys = if (isSearch) listOf("file_path", "filepath", "filePath", "notebook_path")
    else listOf("file_path", "filepath", "path", "filePath", "file", "notebook_path")
    return keys.mapNotNull(::str).filter { it.isNotEmpty() && it.encodeToByteArray().size < 512 }.distinct()
}

/** The stored observation for a tool call; each field is scrubbed before it is truncated. */
fun synthesize(toolName: String, hook: String, input: JsonElement, output: JsonElement): Synthesized {
    val o = output as? JsonObject
    val isError = (o?.get("is_error") as? JsonPrimitive)?.booleanOrNull == true || o?.containsKey("error") == true
    val narrative = listOf(stringify(input), stringify(output)).map { truncateChars(scrubSecrets(it), 200) }.filter { it.isNotEmpty() }.joinToString(" | ")
    return Synthesized(inferType(toolName, hook, isError), truncateChars(toolName.ifEmpty { "observation" }, TITLE_MAX),
        truncateChars(narrative, NARRATIVE_MAX), extractFiles(toolName, input))
}

/** Reciprocal rank fusion over observation lists (K = 60, as the note leg). */
fun rrfObs(lists: List<List<ObsHit>>, limit: Int): List<ObsHit> {
    val scores = LinkedHashMap<Int, Float>(); val hits = HashMap<Int, ObsHit>()
    for (list in lists) list.forEachIndexed { rank, h -> scores[h.id] = (scores[h.id] ?: 0f) + 1.0f / (RRF_K + rank.toFloat() + 1.0f); hits.getOrPut(h.id) { h } }
    return scores.entries.sortedWith(compareByDescending<Map.Entry<Int, Float>> { it.value }.thenBy { it.key })
        .take(maxOf(limit, 1)).mapNotNull { hits[it.key] }
}

/** Keep at most [maxPerSession] hits from one session, then backfill. */
fun diversifyBySession(hits: List<ObsHit>, limit: Int, maxPerSession: Int): List<ObsHit> {
    val selected = mutableListOf<ObsHit>(); val counts = HashMap<String, Int>()
    for (h in hits) {
        val n = counts[h.sessionId] ?: 0
        if (n >= maxPerSession) continue
        counts[h.sessionId] = n + 1; selected += h
        if (selected.size >= limit) break
    }
    for (h in hits) { if (selected.size >= limit) break; if (selected.none { it.id == h.id }) selected += h }
    return selected
}

private fun skipTool(name: String) = name.lowercase().let { it.startsWith("mem_") || it.startsWith("memory_") || "mem_save" in it || "mem_search" in it }

/** Store one observation for this tool call; null when empty, a memory tool, a duplicate or refused. */
fun capture(cx: Ctx, ev: PostToolUse): Int? {
    if (skipTool(ev.toolName)) return null
    val made = synthesize(ev.toolName, "post_tool_use", ev.toolInput, ev.toolResponse)
    if (made.narrative.isEmpty()) return null
    val dedup = hexSha256("${cx.session()}\u0000${ev.toolName}\u0000${made.narrative}".encodeToByteArray())
    // No Measurement row on purpose (T927): the agent still receives the whole tool output.
    return runCatching {
        cx.insertObservation(NewObservation(cx.session(), resolvedProject(cx), made.obsType, made.title, made.narrative, dedup, existingRels(cx, made.files)))
    }.getOrNull()
}

/** Up to [n] lines `obs <id> <title> (<session prefix>)`. */
fun indexLines(hits: List<ObsHit>, n: Int): List<String> =
    hits.take(n).map { "obs ${it.id} ${it.title} (${it.sessionId.scalars().take(8).joinToString("")})" }
