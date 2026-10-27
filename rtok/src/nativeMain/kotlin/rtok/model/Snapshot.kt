// Port of src/model/mod.rs `snapshot` (D23): the one operator model `rtok web` sends on `/ws` and `rtok tui`
// renders. Built in slices (docs/web.md step 4); keys come out sorted at every level, the wire Rust sends.
package rtok.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import rtok.config.Config
import rtok.logTail
import rtok.store.CallRow
import rtok.store.SessionTotals
import rtok.store.Store
import rtok.store.archiveRefIds
import rtok.store.recentCalls
import rtok.store.recentSessionTotals

object Snapshot {
    /** Rows of the Calls page (model/mod.rs `CALLS_ROWS`). */
    const val CALLS_ROWS = 120L

    /** What one tick sends: the slices ported so far, plus the pages still to come as their "not yet" values. */
    fun build(cfg: Config): JsonObject {
        val opened = runCatching { Store.open(cfg.core.dbPath) }
        val store = opened.getOrNull()
        try {
            val calls = store?.let { s -> runCatching { s.recentCalls(CALLS_ROWS) }.getOrNull() }.orEmpty()
            val refIds = store?.let { s -> runCatching { s.archiveRefIds(calls.map { it.id }) }.getOrNull() }.orEmpty()
            val sessions =
                store?.let { s -> runCatching { s.recentSessionTotals(0, Agents.SESSIONS_ROWS) }.getOrNull() }.orEmpty()
            val (doctor, doctorPending) = DoctorPage.get(cfg)
            val fields =
                mutableMapOf<String, JsonElement>(
                    "type" to JsonPrimitive("snapshot"),
                    "calls" to Json.encodeToJsonElement<List<CallRow>>(calls),
                    "sessions" to Json.encodeToJsonElement<List<SessionTotals>>(sessions),
                    "ref_ids" to JsonObject(refIds.entries.associate { (k, v) -> k.toString() to JsonPrimitive(v) }),
                    "logs" to JsonArray(logTail(cfg).map(::JsonPrimitive)),
                    "usage" to Overview.build(cfg, store).toJson(),
                    "plugins" to Plugins.json(cfg, store),
                    "graph" to (GraphPage.text(cfg)?.let(::JsonPrimitive) ?: JsonNull),
                    "projects" to GraphPage.projectsJson(cfg),
                    "hosts" to JsonPrimitive(HostsPage.text(cfg)),
                    "agent_usage" to UsagePage.get(cfg).toJson(),
                    "config" to (ConfigPage.text(cfg)?.let(::JsonPrimitive) ?: JsonNull),
                    "doctor" to (doctor ?: JsonNull),
                    "doctor_pending" to JsonPrimitive(doctorPending),
                    // Not ported yet (docs/web.md step 4): rendered as "pending", never as a failure.
                    "stats" to JsonNull,
                    "junk" to (JunkPage.get(cfg)?.card ?: JsonNull),
                    "services" to JsonNull,
                    "worktrees" to JsonPrimitive(WorktreesPage.text()),
                    "tasks" to TasksPage.get(cfg).toJson(),
                )
            // mod.rs `snapshot`: a store that will not open wins; else a probe that landed without a report.
            val err = opened.exceptionOrNull()?.let { it.message ?: it.toString() } ?: "doctor probe failed".takeIf { doctor == null && !doctorPending }
            err?.let { fields["error"] = JsonPrimitive(it) }
            return sorted(JsonObject(fields)) as JsonObject
        } finally {
            store?.close()
        }
    }

    /** The snapshot frame as text. */
    fun frame(cfg: Config): String = build(cfg).toString()

    /** serde_json's default map is a BTreeMap: every object's keys sorted. */
    internal fun sorted(e: JsonElement): JsonElement =
        when (e) {
            is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { (k, v) -> k to sorted(v) })
            is JsonArray -> JsonArray(e.map(::sorted))
            else -> e
        }
}
