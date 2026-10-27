// The section readers of src/model/report.rs: savings, sinks, calls, hooks, cache, expand.
package rtok.model

import rtok.config.CATALOGUE
import rtok.config.Config
import rtok.measure.stats.CacheHealth
import rtok.store.Call
import rtok.store.Store
import rtok.store.archiveDecisionCounts
import rtok.store.listMeasurements
import rtok.store.measurementTotals
import kotlin.math.ceil

internal object ReportSections {
    private val SURFACES = listOf("hook", "mcp", "proxy")
    private const val TOP_SINKS = 10
    private const val P50 = 0.5
    private const val P95 = 0.95

    /** One row per plugin with a `Measurement` row: every plugin the ledger has seen, not just the catalogue (T207). */
    fun savings(store: Store): ReportSavingsSection {
        val totals = store.measurementTotals()
        val rows =
            totals.groupBy { it.plugin }.entries.sortedBy { it.key }.map { (plugin, ts) ->
                val before = ts.sumOf { it.estBefore }
                val after = ts.sumOf { it.estAfter }
                ReportSavings(plugin, ts.sumOf { it.rows }, before, after, before - after)
            }
        return ReportSavingsSection(rows, rows.sumOf { it.rows }, rows.sumOf { it.saved }, totals.map { it.kind }.distinct().sorted())
    }

    /** The classes [sinkSwitch] can name a switch for: an `archive` or `inject` row is already shortened. */
    fun isActionableSink(cls: String) = cls == "read" || cls == "cmd" || cls == "mcp"

    fun sinks(
        store: Store,
        cfg: Config,
    ): ReportSinksSection {
        val by = HashMap<Pair<String, String>, LongArray>()
        for ((plugin, _) in CATALOGUE) {
            for (m in store.listMeasurements(plugin).filter { it.beforeBytes > 0 }) {
                val key = sinkLabel(plugin, m.kind, m.refId)
                // Filtered before the top-ten cut so larger non-actionable rows cannot displace an eligible sink.
                if (!isActionableSink(key.first)) continue
                val e = by.getOrPut(key) { LongArray(2) }
                e[0] += m.beforeBytes
                e[1] += 1
            }
        }
        val rows =
            by
                .map { (k, v) -> ReportSink(k.first, k.second, v[0], v[1], sinkSwitch(cfg, k.first, k.second)) }
                .sortedWith(compareByDescending<ReportSink> { it.beforeBytes }.thenBy { it.sink }.thenBy { it.cls })
        return ReportSinksSection(rows.take(TOP_SINKS))
    }

    fun sinkLabel(
        plugin: String,
        kind: String,
        refId: String?,
    ): Pair<String, String> =
        when {
            plugin == "cmd" && kind == "wrap" -> "mcp" to (refId?.takeIf { ':' in it }?.substringBeforeLast(':') ?: "mcp")
            plugin == "cmd" -> "cmd" to (refId?.takeIf { ':' in it }?.substringBefore(':') ?: kind)
            plugin == "read" -> "read" to (refId ?: "unknown")
            else -> plugin to (refId ?: kind)
        }

    fun sinkSwitch(
        cfg: Config,
        cls: String,
        sink: String,
    ): String =
        when (cls) {
            "read" -> {
                "[plugins.read] default_mode = ${cfg.plugins.read.defaultMode}"
            }

            "cmd" -> {
                if (rtok.plugins.cmd.Rules
                        .defaults()
                        .any { it.matchCmd == sink }
                ) {
                    "[$sink] rule"
                } else {
                    "[$sink] rule (default head/tail)"
                }
            }

            "mcp" -> {
                "rtok mcp -- <server> (wrap)"
            }

            else -> {
                "none: already shortened"
            }
        }

    fun calls(
        all: List<Call>,
        from: Long,
    ): ReportCallsSection {
        val inWindow = all.filter { it.ts >= from }
        val rows =
            SURFACES.map { surface ->
                val group = inWindow.filter { it.surface == surface }
                val ms = group.mapNotNull { it.ms }.sorted()
                ReportCalls(surface, group.size.toLong(), ms.size.toLong(), pct(ms, P50), pct(ms, P95))
            }
        return ReportCallsSection(rows, inWindow.size.toLong(), all.size.toLong(), hooks(inWindow))
    }

    /** Hook-surface rows grouped by event name, busiest first. */
    private fun hooks(inWindow: List<Call>): List<ReportHook> =
        inWindow
            .filter { it.surface == "hook" }
            .groupingBy { it.name ?: "unknown" }
            .eachCount()
            .map { (name, n) -> ReportHook(name, n.toLong()) }
            .sortedWith(compareByDescending<ReportHook> { it.calls }.thenBy { it.name })

    fun cache(store: Store): ReportCache {
        val health = CacheHealth.report(store)
        val detail =
            health.flatMap { h ->
                h.turns.withIndex().mapNotNull { (i, t) -> t.bust?.let { ReportBust(h.session, i + 1L, it, t.cacheCreate, t.cacheRead) } }
            }
        val byCause =
            detail
                .groupingBy { it.cause }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .map { (c, n) -> c to n.toLong() }
        return ReportCache(health.size.toLong(), health.sumOf { it.turns.size }.toLong(), health.sumOf { it.busts }.toLong(), byCause, detail)
    }

    fun expand(store: Store): ReportExpand {
        val (decisions, expanded) = store.archiveDecisionCounts()
        val rows = store.listMeasurements("archive").filter { it.kind == "expand" }
        return ReportExpand(
            decisions = decisions,
            expanded = expanded,
            rate = if (decisions > 0) expanded.toDouble() / decisions else 0.0,
            expandedIds = rows.mapNotNull { it.refId },
            cost = rows.sumOf { it.estAfter.toLong() },
            costRows = rows.size.toLong(),
        )
    }

    /** Nearest-rank percentile: the `ceil(p·n)`-th smallest value; null when nothing was timed. */
    fun pct(
        sorted: List<Double>,
        p: Double,
    ): Double? {
        if (sorted.isEmpty()) return null
        val idx = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
