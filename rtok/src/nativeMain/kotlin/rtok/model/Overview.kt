// Port of src/model/mod.rs `Overview`, `Model::overview` and `savings_trend` (T15.3, T414.13), plus
// src/proxy/live.rs `alerts`: the Overview page the snapshot sends under `usage`.
package rtok.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import rtok.agents.Usage
import rtok.config.Config
import rtok.store.MeasurementTotal
import rtok.store.Store
import rtok.store.measurementTotalsSince
import rtok.store.usageByApi
import rtok.store.usageCtt
import rtok.sys.Zone

/** The shared stats widget (model/mod.rs `Stats`): `usage` rows for the overview, `Measurement` rows per plugin. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Stats(
    @EncodeDefault val input: Long = 0,
    @EncodeDefault val output: Long = 0,
    @EncodeDefault @SerialName("cache_create") val cacheCreate: Long = 0,
    @EncodeDefault @SerialName("cache_read") val cacheRead: Long = 0,
    @EncodeDefault @SerialName("est_before") val estBefore: Long = 0,
    @EncodeDefault @SerialName("est_after") val estAfter: Long = 0,
    @EncodeDefault val rows: Long = 0,
)

/** One day of the Δtok savings trend; `saved` is null on a day with no rows (a gap, never a zero). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SavingsDay(
    val day: String,
    val rows: Long,
    @EncodeDefault val saved: Long?,
    val plugins: Map<String, Long>,
)

/** The Overview page: totals flat at the top level (P19 shape), then ctt, turns, alerts (omitted when empty), savings. */
data class OverviewPage(
    val totals: Stats = Stats(),
    val ctt: Long = 0,
    val turns: List<Long> = emptyList(),
    val alerts: List<String> = emptyList(),
    val savings: List<SavingsDay> = emptyList(),
) {
    fun toJson(): JsonObject {
        val m = (Json.encodeToJsonElement(totals) as JsonObject).toMutableMap<String, JsonElement>()
        m["ctt"] = Json.encodeToJsonElement(ctt)
        m["turns"] = Json.encodeToJsonElement(turns)
        if (alerts.isNotEmpty()) m["alerts"] = Json.encodeToJsonElement(alerts)
        m["savings"] = Json.encodeToJsonElement(savings)
        return JsonObject(m)
    }
}

object Overview {
    /** Points of the Overview sparkline (model/mod.rs `OVERVIEW_TURNS`). */
    const val TURNS = 120L

    /** Days the Δtok trend covers (`SAVINGS_DAYS`). */
    const val SAVINGS_DAYS = 14

    /** Usage totals across every API, CTT and the per-turn series; no store, or one that will not read: zeros. */
    fun build(
        cfg: Config,
        store: Store?,
        now: Long = rtok.log.now(),
    ): OverviewPage {
        val alerts = alerts(cfg)
        store ?: return OverviewPage(alerts = alerts)
        val totals =
            runCatching { store.usageByApi() }.getOrNull().orEmpty().fold(Stats()) { s, r ->
                s.copy(input = s.input + r.input, output = s.output + r.output, cacheCreate = s.cacheCreate + r.cacheCreate, cacheRead = s.cacheRead + r.cacheRead)
            }
        val (ctt, turns) = runCatching { store.usageCtt(TURNS) }.getOrNull() ?: (0L to emptyList())
        // A bad zone is the Usage page's error to report; the trend falls back to the system's.
        val tz = runCatching { Usage.zone(cfg.agents.usage.tz) }.getOrElse { Zone.system() }
        val savings = runCatching { savingsTrend(store, now, tz) }.getOrNull().orEmpty()
        return OverviewPage(totals, ctt, turns, alerts, savings)
    }

    /** proxy/live.rs `alerts`: the standing warning while the proxy runs as a plain passthrough. */
    fun alerts(cfg: Config): List<String> {
        val proxy = cfg.proxy.enabled
        val core = cfg.core.enabled
        if (proxy && core) return emptyList()
        val which =
            when {
                !proxy && !core -> "[proxy] enabled and [core] enabled"
                !proxy -> "[proxy] enabled"
                else -> "[core] enabled"
            }
        return listOf("proxy disabled — live passthrough (not recorded). Set $which = true and restart to restore compress/bookkeeping.")
    }

    /** [SAVINGS_DAYS] days ending with [now]'s day in [tz], oldest first; a row stamped after today is left out. */
    fun savingsTrend(
        store: Store,
        now: Long,
        tz: Zone,
    ): List<SavingsDay> {
        var first = tz.date(now)
        repeat(SAVINGS_DAYS - 1) { first = tz.date(tz.startOf(first) - 1) }
        val days = generateSequence(first) { it.tomorrow() }.take(SAVINGS_DAYS).toList()
        val by = days.associateWith { HashMap<String, Stats>() }
        for ((ts, t) in store.measurementTotalsSince(tz.startOf(first))) {
            by[tz.date(ts)]?.let { fold(it, t) }
        }
        return days.map { d ->
            val m = by.getValue(d)
            val rows = m.values.sumOf { it.rows }
            val plugins = m.entries.sortedBy { it.key }.associate { (p, s) -> p to s.estBefore - s.estAfter }
            SavingsDay(d.toString(), rows, if (rows > 0) plugins.values.sum() else null, plugins)
        }
    }

    /** model/mod.rs `fold_total`: one `Measurement` total into its plugin's stats. */
    fun fold(
        by: MutableMap<String, Stats>,
        t: MeasurementTotal,
    ) {
        val s = by[t.plugin] ?: Stats()
        by[t.plugin] = s.copy(rows = s.rows + t.rows, estBefore = s.estBefore + t.estBefore, estAfter = s.estAfter + t.estAfter)
    }
}
