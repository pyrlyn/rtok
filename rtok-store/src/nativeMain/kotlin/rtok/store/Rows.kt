// Row types from crates/rtok-store/src/lib.rs. Field names follow the Rust structs (camelCase).
package rtok.store

import rtok.db.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

data class SessionSeen(val id: String, val host: String?, val cwd: String, val lastSeen: Long, val endedAt: Long?)
/** `(host slug, project, cwd)`. */
typealias SessionRow = Triple<String?, String?, String?>

data class NoteRow(val id: Int, val project: String?, val kind: String, val title: String, val body: String, val retired: Long?, val supersededBy: Int?, val pinned: Int) {
    fun isPinned() = pinned != 0
}
data class ExportNote(val id: Int, val ts: Long, val project: String?, val kind: String, val title: String, val body: String, val retired: Long?, val supersededBy: Int?, val pinned: Int)
class PortableNote(val project: String?, val kind: String, val title: String, val body: String, val id: Int? = null, val ts: Long? = null, val retired: Long? = null, val supersededBy: Int? = null, val pinned: Int? = null)
data class MemoryNoteKindAgg(val project: String?, val kind: String, val live: ULong, val pinned: ULong, val retired: ULong, val bodyBytes: Long, val oldestTs: Long, val newestTs: Long)
data class MeasRow(val kind: String, val beforeBytes: Long, val afterBytes: Long, val estBefore: Int, val estAfter: Int, val refId: String?)
data class MeasurementTotal(val plugin: String, val kind: String, val rows: Long, val estBefore: Long, val estAfter: Long)
data class ApiUsage(val api: String, val input: Long, val cacheCreate: Long, val cacheRead: Long, val output: Long)
data class LaneUsage(val kind: String, val tier: String?, val input: Long, val cacheCreate: Long, val cacheRead: Long, val output: Long)
data class ModelUsage(val model: String, var input: Long, var cacheCreate: Long, var cacheRead: Long, var output: Long)
data class LaneModelUsage(val kind: String?, val usage: ModelUsage)
data class UsageSlice(val host: String?, val api: String, val session: String, val model: String?, val ts: Long, val input: Long, val cacheCreate: Long, val cacheRead: Long, val output: Long)
data class UsageRow(val session: String, val model: String?, val api: String, val input: Long, val cacheCreate: Long, val cacheRead: Long, val output: Long, val callId: Long?)
/** `(model, api, [input, cache_create, cache_read, output], result_key)`. */
data class UsageRowInput(val model: String?, val api: String, val legs: LongArray, val key: String?)

@Serializable
data class SessionTotals(
    val id: String, val host: String?, val project: String?, val provider: String?, val api: String?, val model: String?,
    val input: Long, @SerialName("cache_create") val cacheCreate: Long, @SerialName("cache_read") val cacheRead: Long, val output: Long,
    @SerialName("started_at") val startedAt: Long, @SerialName("last_activity") val lastActivity: Long, @SerialName("ended_at") val endedAt: Long?,
)

@Serializable
data class CallRow(
    val id: Int, val ts: Long, val session: String, val surface: String, val kind: String, val plugin: String?, val name: String?,
    @SerialName("parent_id") val parentId: Int?, val ms: Double?, val ok: Int, val error: String?, val host: String?, val provider: String?,
    val model: String?, val api: String?, val input: Long?, @SerialName("cache_create") val cacheCreate: Long?, @SerialName("cache_read") val cacheRead: Long?, val output: Long?,
)

internal fun hostBySession(db: Db): Map<String, String?> =
    db.query("SELECT sessions.id, hosts.slug FROM sessions LEFT JOIN hosts ON hosts.id = sessions.host_id") { it.string(0) to it.stringOrNull(1) }.toMap()
