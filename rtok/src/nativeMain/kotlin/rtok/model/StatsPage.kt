// Port of the `rtok stats` pages of src/model/mod.rs (T15.11): the transcript report with its store attaches, one
// catalogue plugin's measurements, and per-session cache health. The command renders these; it owns no store.
package rtok.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import rtok.config.Config
import rtok.config.parseSinceFrom
import rtok.measure.stats.CacheHealth
import rtok.measure.stats.Replay
import rtok.measure.stats.Report
import rtok.measure.stats.SerdeJson
import rtok.measure.stats.SessionHealth
import rtok.measure.stats.attachApi
import rtok.measure.stats.attachBashCmd
import rtok.measure.stats.attachCheckpointNotes
import rtok.measure.stats.attachCodex
import rtok.measure.stats.attachCosts
import rtok.measure.stats.attachLanes
import rtok.measure.stats.collect
import rtok.store.Store
import rtok.store.archiveDecisionCounts
import rtok.store.listMeasurements
import rtok.store.unixNow

object StatsPage {
    /** `stats_report`: transcripts since `stats.since`, then every store attach (USD only with `stats.price`), then Codex. */
    fun report(cfg: Config): Report {
        val since = parseSinceFrom(cfg.stats.since, "stats.since")
        val cutoff = (unixNow() - since).coerceAtLeast(0)
        val report = collect(cfg.stats.transcriptsDir, cutoff, cfg.stats.plugin, Replay.fromCfg(cfg))
        runCatching { Store.open(cfg.core.dbPath) }.getOrNull()?.let { store ->
            runCatching { report.attachApi(store) }
            runCatching { report.attachLanes(store) }
            runCatching { report.attachBashCmd(store) }
            runCatching { report.attachCheckpointNotes(store) }
            if (cfg.stats.price) runCatching { report.attachCosts(store, cfg.stats.prices) }
        }
        report.attachCodex(cfg.stats.codexDir, cutoff)
        return report
    }

    private fun sorted(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.sortedBy { it.first }.toMap())

    /** `plugin_stats`: one catalogue plugin's measurement rows; the store is required. Keys sorted, as a serde `Value`. */
    fun plugin(
        cfg: Config,
        plugin: String,
    ): JsonObject {
        val store = Store.open(cfg.core.dbPath)
        val rows = store.listMeasurements(plugin)
        val hits = rows.count { it.kind == "expand" }
        val list =
            rows.map {
                sorted(
                    "kind" to rtok.measure.stats.json(it.kind),
                    "before" to rtok.measure.stats.json(it.beforeBytes),
                    "after" to rtok.measure.stats.json(it.afterBytes),
                    "est_before" to rtok.measure.stats.json(it.estBefore),
                    "est_after" to rtok.measure.stats.json(it.estAfter),
                    "ref_id" to rtok.measure.stats.json(it.refId),
                )
            }
        val out = mutableListOf("plugin" to rtok.measure.stats.json(plugin), "archive_hits" to rtok.measure.stats.json(hits), "rows" to JsonArray(list))
        if (plugin == "archive") {
            val (decisions, expanded) = store.archiveDecisionCounts()
            out += "decisions" to rtok.measure.stats.json(decisions)
            out += "expanded" to rtok.measure.stats.json(expanded)
            out += "expand_rate" to rtok.measure.stats.json(if (decisions > 0) expanded.toDouble() / decisions.toDouble() else 0.0)
        }
        return sorted(*out.toTypedArray())
    }

    fun pluginJson(
        cfg: Config,
        plugin: String,
    ) = SerdeJson.pretty(plugin(cfg, plugin))

    /** `cache_health`: the store is required. */
    fun cacheHealth(cfg: Config): List<SessionHealth> = CacheHealth.report(Store.open(cfg.core.dbPath))
}
