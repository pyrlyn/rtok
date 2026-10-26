// Port of model::memory_status (src/model/mod.rs) and src/memory_status.rs (T69.4): note counts per project and
// kind, recalls and MCP memory calls in a window, as a table or the `--json` shape of `MemoryStatus`.
package rtok

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rtok.config.Config
import rtok.config.parseSince
import rtok.config.parseSinceFrom
import rtok.store.Store
import rtok.store.memoryMcpCalls
import rtok.store.memoryNoteAggs
import rtok.store.memoryRecallTotals

@Serializable
data class MemoryNotesTotals(
    val live: ULong,
    val pinned: ULong,
    val retired: ULong,
    @SerialName("body_bytes") val bodyBytes: Long,
)

@Serializable
data class MemoryRecallTotals(
    val recalls: ULong,
    @SerialName("stood_for_bytes") val stoodForBytes: Long,
    @SerialName("injected_bytes") val injectedBytes: Long,
)

@Serializable
data class MemoryMcpCalls(
    @SerialName("mem_search") val memSearch: ULong,
    @SerialName("mem_get") val memGet: ULong,
)

@Serializable
data class MemoryKindAgg(
    val kind: String,
    val live: ULong,
    val pinned: ULong,
    val retired: ULong,
    @SerialName("body_bytes") val bodyBytes: Long,
    @SerialName("oldest_ts") val oldestTs: Long,
    @SerialName("newest_ts") val newestTs: Long,
)

@Serializable
data class MemoryProjectBlock(
    val project: String? = null,
    val kinds: List<MemoryKindAgg>,
)

@Serializable
data class MemoryStatus(
    val since: String,
    val project: String? = null,
    val notes: MemoryNotesTotals,
    val recall: MemoryRecallTotals,
    val calls: MemoryMcpCalls,
    @SerialName("by_project") val byProject: List<MemoryProjectBlock>,
)

object MemoryStatusView {
    @OptIn(ExperimentalSerializationApi::class)
    private val pretty =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            explicitNulls = false
        }

    /** One snapshot; [since] overrides `[stats] since`. */
    fun collect(
        cfg: Config,
        project: String?,
        since: String?,
    ): MemoryStatus {
        val span = if (since != null) parseSince(since) else parseSinceFrom(cfg.stats.since, "stats.since")
        val sinceUnix = rtok.log.now() - span
        return Store.open(cfg.core.dbPath).use { store ->
            val aggs = store.memoryNoteAggs(project)
            // Grouped in the store's `ORDER BY project, kind`; Rust's BTreeMap puts the no-project block first.
            val blocks =
                aggs
                    .groupBy { it.project }
                    .entries
                    .sortedWith(compareBy({ it.key != null }, { it.key ?: "" }))
                    .map { (p, rows) -> MemoryProjectBlock(p, rows.map { MemoryKindAgg(it.kind, it.live, it.pinned, it.retired, it.bodyBytes, it.oldestTs, it.newestTs) }) }
            val (recalls, stood, injected) = store.memoryRecallTotals(sinceUnix)
            val (search, get) = store.memoryMcpCalls(sinceUnix)
            MemoryStatus(
                since = since ?: cfg.stats.since,
                project = project,
                notes =
                    MemoryNotesTotals(
                        aggs.fold(0uL) { a, r -> a + r.live },
                        aggs.fold(0uL) { a, r -> a + r.pinned },
                        aggs.fold(0uL) { a, r -> a + r.retired },
                        aggs.sumOf { it.bodyBytes },
                    ),
                recall = MemoryRecallTotals(recalls, stood, injected),
                calls = MemoryMcpCalls(search, get),
                byProject = blocks,
            )
        }
    }

    fun json(s: MemoryStatus): String = pretty.encodeToString(MemoryStatus.serializer(), s)

    fun table(s: MemoryStatus): String =
        buildString {
            append("since ${s.since}\n")
            s.project?.let { append("project $it\n") }
            append("notes  live ${s.notes.live}  pinned ${s.notes.pinned}  retired ${s.notes.retired}  body ${s.notes.bodyBytes} B\n")
            append("recall  count ${s.recall.recalls}  stood_for ${s.recall.stoodForBytes} B  injected ${s.recall.injectedBytes} B\n")
            append("calls  mem_search ${s.calls.memSearch}  mem_get ${s.calls.memGet}\n")
            for (b in s.byProject) {
                val name = b.project ?: "-"
                for (k in b.kinds) {
                    append(
                        "project $name  kind ${k.kind}  live ${k.live}  pinned ${k.pinned}  retired ${k.retired}  body ${k.bodyBytes} B  ts ${k.oldestTs}..${k.newestTs}\n",
                    )
                }
            }
        }
}
