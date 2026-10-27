// Port of src/measure/subagents.rs (T128, T131): sub-agent transcripts, `<session>/subagents/agent-<id>.jsonl` plus
// `agent-<id>.meta.json` (`agentType`, `model`, `toolUseId`), attributed to the parent session. The session walk skips
// them (never a session of their own) and they are counted here exactly once.
package rtok.measure

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.sys.fileName
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.readToString

/** One `(agentType, model)` split row. */
@Serializable
data class SubagentRow(
    @SerialName("agent_type") var agentType: String = "",
    var model: String = "",
    var count: Long = 0,
    @SerialName("result_bytes") var resultBytes: Long = 0,
    @SerialName("read_bytes") var readBytes: Long = 0,
    @SerialName("reread_bytes") var rereadBytes: Long = 0,
)

/** T131: one "spawned with/without a brief" split. */
@Serializable
data class BriefSplitRow(
    var count: Long = 0,
    @SerialName("read_bytes") var readBytes: Long = 0,
    @SerialName("reread_bytes") var rereadBytes: Long = 0,
    @SerialName("share_read") var shareRead: Double = 0.0,
    @SerialName("usage_input") var usageInput: Long = 0,
)

/**
 * The `subagents` row. A re-read is a sub-agent `Read` of a path the parent read or an earlier sibling read;
 * parent-first, so the two re-read columns are disjoint and sum to [rereadBytes].
 */
@Serializable
data class Subagents(
    var sessions: Long = 0,
    var count: Long = 0,
    @SerialName("result_bytes") var resultBytes: Long = 0,
    @SerialName("parent_result_bytes") var parentResultBytes: Long = 0,
    @SerialName("read_bytes") var readBytes: Long = 0,
    @SerialName("read_parent") var readParent: Long = 0,
    @SerialName("read_sibling") var readSibling: Long = 0,
    @SerialName("reread_bytes") var rereadBytes: Long = 0,
    @SerialName("reread_calls") var rereadCalls: Long = 0,
    @SerialName("share_read") var shareRead: Double = 0.0,
    @SerialName("share_results") var shareResults: Double = 0.0,
    @SerialName("share_tree") var shareTree: Double = 0.0,
    @SerialName("usage_input") var usageInput: Long = 0,
    @SerialName("usage_cache_read") var usageCacheRead: Long = 0,
    @SerialName("usage_cache_write") var usageCacheWrite: Long = 0,
    @SerialName("usage_output") var usageOutput: Long = 0,
    @SerialName("by_type") var byType: List<SubagentRow> = emptyList(),
    @SerialName("with_brief") val withBrief: BriefSplitRow = BriefSplitRow(),
    @SerialName("without_brief") val withoutBrief: BriefSplitRow = BriefSplitRow(),
)

/** T131: substring of `memory::handoff::INSTRUCTIONS` that marks a `SubagentStart` spawn brief. */
const val SPAWN_BRIEF_MARKER = "Get the archived body with rtok expand"

internal fun hasSpawnBrief(text: String) = SPAWN_BRIEF_MARKER in text

/** `is_subagent`: the transcript's parent directory is named `subagents`. */
internal fun isSubagent(path: String): Boolean = parent(path)?.let(::fileName) == "subagents"

/** `Path::with_extension`: [path] with its last extension replaced by [ext] (dropped when empty). */
internal fun withExtension(
    path: String,
    ext: String,
): String {
    val name = fileName(path) ?: return path
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val dir = path.substring(0, path.length - name.length)
    return dir + stem + if (ext.isEmpty()) "" else ".$ext"
}

/** `file_stem`. */
internal fun fileStem(path: String): String? = fileName(path)?.let { n -> n.lastIndexOf('.').takeIf { it > 0 }?.let { n.substring(0, it) } ?: n }

/** What the walk needs from one parent transcript. */
class SubagentParent(
    transcript: String,
    parsed: Parsed,
) {
    internal val sidecar = withExtension(transcript, "")
    internal val readPaths = parsed.toolUses.filter { it.name == "Read" }.mapNotNull { toolPath(it.input) }
    internal val spawnOrder =
        parsed.toolUses
            .filter { it.name == "Agent" || it.name == "Task" }
            .fold(LinkedHashMap<String, Int>()) { m, u -> m.apply { put(u.id, size) } }
    internal val resultBytes = parsed.toolResults.sumOf { utf8Len(it.content) }
}

object SubagentScan {
    private data class Spawned(
        val order: Int,
        val stem: String,
        val path: String,
        val agentType: String,
        val model: String,
    )

    /** The parent's `subagents/agent-*.jsonl` under [cutoff], in spawn order; unknown metas last by name. */
    private fun spawned(
        parent: SubagentParent,
        cutoff: Long,
    ): List<Spawned> =
        jsonlPaths(join(parent.sidecar, "subagents"), cutoff)
            .map { p ->
                val (type, model, order) = meta(p, parent.spawnOrder)
                Spawned(order, fileStem(p).orEmpty(), p, type, model)
            }.sortedWith(compareBy<Spawned>({ it.order }, { it.stem }, { it.path }, { it.agentType }, { it.model }))

    /** `meta`: `(agentType, model, spawn order)` from the sidecar meta; unknown metas sort last. */
    private fun meta(
        jsonl: String,
        spawnOrder: Map<String, Int>,
    ): Triple<String, String, Int> {
        val v = runCatching { readToString(withExtension(jsonl, "meta.json")) }.getOrNull()?.let(::parseJson) ?: return Triple("unknown", "-", Int.MAX_VALUE)
        return Triple(v.field("agentType").str() ?: "unknown", v.field("model").str() ?: "-", v.field("toolUseId").str()?.let(spawnOrder::get) ?: Int.MAX_VALUE)
    }

    /** `collect`: every `<stem>/subagents/agent-*.jsonl` under [cutoff]; null when no parent had a sub-agent. */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod", "LoopWithTooManyJumpStatements")
    fun collect(
        parents: List<SubagentParent>,
        cutoff: Long,
    ): Subagents? {
        val out = Subagents()
        val byType = HashMap<String, SubagentRow>()
        for (parent in parents) {
            val agents = spawned(parent, cutoff)
            if (agents.isEmpty()) continue
            out.sessions++
            out.parentResultBytes += parent.resultBytes
            val siblingPaths = ArrayList<String>()
            for (a in agents) {
                val parsed = parsePath(a.path) ?: continue
                out.count++
                val withBrief = hasSpawnBrief(parsed.subagentStartContext) || parsed.firstUserText?.let(::hasSpawnBrief) == true
                var readBytes = 0L
                var rereadBytes = 0L
                var usageInput = 0L
                val idName = parsed.toolUses.associate { it.id to it.name }
                val reads = parsed.toolUses.filter { it.name == "Read" }.mapNotNull { u -> toolPath(u.input)?.let { u.id to it } }
                val idPath = reads.toMap()
                // BTreeMap<(String, String)>: agent type, then model.
                val row = byType.getOrPut("${a.agentType}\u0000${a.model}") { SubagentRow(a.agentType, a.model) }
                row.count++
                for (r in parsed.toolResults) {
                    val bytes = utf8Len(r.content)
                    out.resultBytes += bytes
                    row.resultBytes += bytes
                    if (idName[r.toolUseId] != "Read") continue
                    out.readBytes += bytes
                    row.readBytes += bytes
                    readBytes += bytes
                    val path = idPath[r.toolUseId] ?: continue
                    val fromParent = parent.readPaths.any { samePath(it, path) }
                    if (fromParent || siblingPaths.any { samePath(it, path) }) {
                        if (fromParent) out.readParent += bytes else out.readSibling += bytes
                        out.rereadBytes += bytes
                        out.rereadCalls++
                        row.rereadBytes += bytes
                        rereadBytes += bytes
                    }
                }
                for (u in parsed.usages) {
                    out.usageInput += u.inputTokens
                    out.usageCacheRead += u.cacheReadInputTokens
                    out.usageCacheWrite += u.cacheCreationInputTokens
                    out.usageOutput += u.outputTokens
                    usageInput += u.inputTokens
                }
                val split = if (withBrief) out.withBrief else out.withoutBrief
                split.count++
                split.readBytes += readBytes
                split.rereadBytes += rereadBytes
                split.usageInput += usageInput
                siblingPaths += reads.map { it.second }
            }
        }
        if (out.count == 0L) return null
        out.shareRead = pct(out.rereadBytes, out.readBytes)
        out.shareResults = pct(out.rereadBytes, out.resultBytes)
        out.shareTree = pct(out.rereadBytes, out.resultBytes + out.parentResultBytes)
        out.withBrief.shareRead = pct(out.withBrief.rereadBytes, out.withBrief.readBytes)
        out.withoutBrief.shareRead = pct(out.withoutBrief.rereadBytes, out.withoutBrief.readBytes)
        out.byType = byType.entries.sortedBy { it.key }.map { it.value }
        return out
    }
}
