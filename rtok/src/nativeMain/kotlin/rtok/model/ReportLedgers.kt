// Port of src/model/report.rs (T22.1, D24): every number `rtok report` prints, read from the ledgers by one store
// open. The renderers format these figures; they never compute one of their own.
package rtok.model

import rtok.config.Config
import rtok.config.parseSinceFrom
import rtok.measure.stats.ApiRow
import rtok.measure.stats.fixed
import rtok.measure.stats.laneRows
import rtok.store.Store
import rtok.store.callsAfter
import rtok.store.countMeasurements
import rtok.store.countUsage

/** The report's window and row counts per ledger. */
data class ReportWindow(
    /** The configured window, e.g. `"30d"`: the report's own, not `[stats] since`. */
    val since: String,
    val fromUnix: Long,
    val toUnix: Long,
    /** `YYYY-MM-DD` UTC, through `log.stamp`'s calendar. */
    val fromDate: String,
    val toDate: String,
    val dbPath: String,
    /** `calls` rows with `ts` in the window: the one ledger whose reader returns row times. */
    val callsInWindow: Long,
    val callsTotal: Long,
    /** Whole-ledger counts: the measurements and usage readers return no row time to filter on. */
    val measurements: Long,
    val usage: Long,
)

/** One plugin's savings, from `Measurement` rows only; `saved` is net (an `expand` row counts negative). */
data class ReportSavings(
    val plugin: String,
    val rows: Long,
    val estBefore: Long,
    val estAfter: Long,
    val saved: Long,
)

data class ReportSavingsSection(
    val rows: List<ReportSavings>,
    val totalRows: Long,
    val totalSaved: Long,
    /** Every distinct `Measurement` kind present, sorted. */
    val kinds: List<String>,
)

/** Latency of one surface (`hook` | `mcp` | `proxy`); p50/p95 are nearest-rank over the timed calls. */
data class ReportCalls(
    val surface: String,
    val calls: Long,
    val timed: Long,
    val p50Ms: Double?,
    val p95Ms: Double?,
)

data class ReportCallsSection(
    val rows: List<ReportCalls>,
    val inWindow: Long,
    val total: Long,
    /** Hook-surface calls in window by event, busiest first. */
    val hooks: List<ReportHook>,
)

/** One hook event's in-window `calls` rows; `"unknown"` when the row names no event. */
data class ReportHook(
    val name: String,
    val calls: Long,
)

/** Cache busts and their cause, aggregated from the `stats --cache` page. */
data class ReportCache(
    val sessions: Long,
    val turns: Long,
    val busts: Long,
    /** Busts per cause, cause order. */
    val byCause: List<Pair<String, Long>>,
    /** Every bust with its 1-based turn, in session/turn order. */
    val detail: List<ReportBust>,
)

data class ReportBust(
    val session: String,
    val turn: Long,
    val cause: String,
    val cacheCreate: Long,
    val cacheRead: Long,
)

/** The archive plugin's honesty metric (T5.4): how often a live-zone pointer had to be expanded. */
data class ReportExpand(
    val decisions: Long,
    val expanded: Long,
    /** expanded / decisions; `0.0` with no decisions. */
    val rate: Double,
    val expandedIds: List<String>,
    /** Σ est_after over the `expand` rows: what the re-reads cost. */
    val cost: Long,
    val costRows: Long,
)

/** One token sink ranked by raw bytes in `Measurement` rows (T59.8). */
data class ReportSink(
    /** `read` | `cmd` | `mcp` */
    val cls: String,
    /** File path, command stem, or `server/tool`. */
    val sink: String,
    val beforeBytes: Long,
    val rows: Long,
    /** Which rtok switch would shorten this sink. */
    val switch: String,
)

data class ReportSinksSection(
    val rows: List<ReportSink>,
)

/** Every ledger section of the report, from one store open. */
data class ReportLedgers(
    val window: ReportWindow,
    val savings: ReportSavingsSection,
    val sinks: ReportSinksSection,
    val calls: ReportCallsSection,
    val cache: ReportCache,
    /** Per lane/tier counters (T385.12.2), key-sorted; empty while everything ran on the agent lane at the ordinary tier. */
    val lanes: List<Pair<String, ApiRow>>,
    val expand: ReportExpand,
) {
    /** The lane/tier rows as strings in [LANE_HEADS] order, so every renderer prints one table. */
    fun laneCells(): List<List<String>> = lanes.map { (key, r) -> listOf(key, r.input.toString(), r.cacheCreate.toString(), r.cacheRead.toString(), r.output.toString(), "${fixed(r.hit * PERCENT, 1)}%") }

    companion object {
        /** Column heads of the cache-by-lane table. */
        val LANE_HEADS = listOf("lane/tier", "input", "cache_create", "cache_read", "output", "hit")

        private const val PERCENT = 100.0
        private const val DATE_LEN = 10

        /** The report's ledger read; a store that will not open is an error. */
        fun read(cfg: Config): ReportLedgers {
            val store = Store.open(cfg.core.dbPath)
            try {
                val calls = store.callsAfter(0, Long.MAX_VALUE)
                val window = window(cfg, store, calls)
                return ReportLedgers(
                    window = window,
                    savings = ReportSections.savings(store),
                    sinks = ReportSections.sinks(store, cfg),
                    calls = ReportSections.calls(calls, window.fromUnix),
                    cache = ReportSections.cache(store),
                    lanes = laneRows(store).second.sortedEntries().map { it.key to it.value },
                    expand = ReportSections.expand(store),
                )
            } finally {
                store.close()
            }
        }

        private fun window(
            cfg: Config,
            store: Store,
            calls: List<rtok.store.Call>,
        ): ReportWindow {
            val since = cfg.report.since
            val to = rtok.log.now()
            val from = to - minOf(parseSinceFrom(since, "report.since"), to)

            fun date(secs: Long) = rtok.log.stamp(maxOf(secs, 0)).take(DATE_LEN)
            return ReportWindow(
                since = since,
                fromUnix = from,
                toUnix = to,
                fromDate = date(from),
                toDate = date(to),
                dbPath = cfg.core.dbPath,
                callsInWindow = calls.count { it.ts >= from }.toLong(),
                callsTotal = calls.size.toLong(),
                measurements = store.countMeasurements(),
                usage = store.countUsage(),
            )
        }
    }
}
