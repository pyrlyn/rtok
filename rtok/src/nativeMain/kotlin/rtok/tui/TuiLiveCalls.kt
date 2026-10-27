// Port of src/tui/live_calls.rs (T480): the Graph tab's live calls pane, the terminal counterpart of the web graph
// page's panel. The rows are `graph_events`, read by the web's own Reader and folded by the web's CallsStore, so the
// pane and the page show the same totals for the same calls. A freeze holds the picture only: the latest store keeps
// taking batches, so nothing is lost when it lifts. The loop has no worker yet, so the reader is polled from the
// loop, at most every POLL_MS, while the Graph tab is up.
package rtok.tui

import rtok.config.Graph
import rtok.web.CallBatch
import rtok.web.CallsStore
import rtok.web.FeedFilter
import rtok.web.Finished
import rtok.web.OUTSIDE_SCOPE
import rtok.web.POLL_MS
import rtok.web.Reader
import rtok.web.Totals
import rtok.web.WINDOWS
import rtok.web.clockMs
import rtok.web.distinct
import rtok.web.filterFeed
import rtok.web.headOf
import rtok.web.outsideScope
import rtok.web.sparkLabel
import tui.Buffer
import tui.Col
import tui.Color
import tui.Key
import tui.Line
import tui.Rect
import tui.Size
import tui.Span
import tui.Style
import tui.block
import tui.paragraph
import tui.rows
import tui.table
import kotlin.math.roundToLong

/** Tool bars the pane draws at most; the rest of a tall tool list is cut, not scrolled. */
private const val MAX_BARS = 4
private const val BAR_WIDTH = 16L

/** Rows the pane needs before it draws the two sparklines: below that the feed keeps its rows. */
private const val SPARKS_FROM = 14
private const val ROWS_WITH_SPARKS = 13
private const val ROWS_WITHOUT = 11
private const val FILTER_CHARS = 12
private const val LABEL_PAD = 34
private const val TOOL_PAD = 10
private const val BAR_STEPS = 7
private const val BARS = "▁▂▃▄▅▆▇█"
private const val THOUSAND = 1e3
private const val TEN_THOUSAND = 1e4
private const val MILLION = 1e6
private const val TEN_MILLION = 1e7
private const val BILLION = 1e9
private const val TEN_BILLION = 1e10
private const val HUNDRED = 100.0
private const val PROJECT_COL = 4
private val WARN_S = Style(fg = Color.Yellow)
private val ERR_S = Style(fg = Color.Red)
private val FEED_COLS = listOf(Col("time", 8), Col("tool", 9), Col("symbol"), Col("caller", 16), Col("project"), Col("result"))

/** live_calls.rs `compact`, the web page's: `1.2k`, `34k`, `5.6M`. */
internal fun liveCompact(n: Long): String {
    val a = kotlin.math.abs(n.toDouble())
    val f = n.toDouble()
    return when {
        a >= BILLION -> "${if (a < TEN_BILLION) oneDecimal(f / BILLION) else (f / BILLION).roundToLong().toString()}B"
        a >= MILLION -> "${if (a < TEN_MILLION) oneDecimal(f / MILLION) else (f / MILLION).roundToLong().toString()}M"
        a >= TEN_THOUSAND -> "${(f / THOUSAND).roundToLong()}k"
        a >= THOUSAND -> "${oneDecimal(f / THOUSAND)}k"
        else -> n.toString()
    }
}

/** One block per slot, scaled to the series' own maximum; an empty slot is the lowest bar. */
internal fun blocks(values: List<Long>): String {
    val top = (values.maxOrNull() ?: 0L).coerceAtLeast(1)
    return values.joinToString("") { v -> if (v <= 0) BARS[0].toString() else BARS[((v * BAR_STEPS + top - 1) / top).toInt()].toString() }
}

/** `30 ms`, `1.5 s`: the web page's `millis`. */
internal fun millis(ms: Double) = if (ms < THOUSAND) "${ms.roundToLong()} ms" else "${oneDecimal(ms / THOUSAND)} s"

private fun percent(
    part: Long,
    whole: Long,
) = if (whole == 0L) "-" else "${(part * HUNDRED / whole).roundToLong()}%"

private fun resultCell(r: Finished): String =
    when {
        r.interrupted -> "interrupted"
        r.ok -> "${r.backend ?: "-"} · ${liveCompact(r.before - r.after)} saved · ${(r.ms ?: 0.0).roundToLong()} ms"
        else -> r.error ?: "failed"
    }

/** Steps a filter through "all" and the values in the feed, wrapping back to "all". */
private fun cycle(
    slot: String,
    feed: List<Finished>,
    pick: (Finished) -> String?,
): String {
    val values = distinct(feed, pick)
    val next = if (slot.isEmpty()) values.firstOrNull() else values.indexOf(slot).takeIf { it >= 0 }?.let { values.getOrNull(it + 1) }
    return next.orEmpty()
}

class TuiLiveCalls(
    /** Null in the tests of navigation: nothing is read, batches come through [feedBatch]. */
    private val dbPath: String?,
    private val graph: Graph = Graph(),
) {
    /** The newest event id when the TUI started: the totals count the events above it. */
    private val started: Long? = dbPath?.let(::headOf)
    private var reader: Reader? = null
    private var lastPoll = 0L
    val latest = CallsStore(graph)
    private var held: Pair<CallsStore, Long>? = null
    var window = 1
        private set
    var filter = FeedFilter()
        private set
    private var now = clockMs()

    /** Takes what the reader found and drops calls whose process is gone; the reader starts with the Graph tab. */
    fun poll(
        active: Boolean,
        at: Long = clockMs(),
    ) {
        now = at
        val path = dbPath
        if (active && path != null && at - lastPoll >= POLL_MS) {
            lastPoll = at
            val r = reader ?: Reader.open(path, graph)?.also { opened -> started?.let { opened.replay(it) }?.forEach { (b, t) -> latest.fold(b, t) } }
            reader = r
            r?.poll()?.let { latest.fold(it, at) }
        }
        latest.sweep(at)
    }

    /** Test seam: folds [batch] at [at]. */
    fun feedBatch(
        batch: CallBatch,
        at: Long,
    ) {
        now = at
        latest.fold(batch, at)
    }

    private val shown: CallsStore get() = held?.first ?: latest
    private val shownNow: Long get() = held?.second ?: now

    /** Calls that arrived while the picture was held. */
    private val pending: Long get() = held?.let { latest.all.calls - it.first.all.calls } ?: 0L

    /** The Graph tab's calls keys: `f` freeze, `w` window, `a`/`t`/`o` filters; true when taken. */
    fun key(k: Key): Boolean {
        // The filter picks from the rows on screen, which a freeze holds.
        val feed = shown.feed
        when (k) {
            Key.Char("f") -> held = if (held != null) null else latest.copy() to now
            Key.Char("w") -> window = (window + 1) % WINDOWS.size
            Key.Char("a") -> filter = filter.copy(caller = cycle(filter.caller, feed) { it.caller })
            Key.Char("t") -> filter = filter.copy(tool = cycle(filter.tool, feed) { it.tool })
            Key.Char("o") -> filter = filter.copy(project = cycle(filter.project, feed) { it.project })
            else -> return false
        }
        return true
    }

    private fun chip(
        label: String,
        on: Boolean,
    ) = Span(" $label ", if (on) Style(reverse = true) else MUTED)

    private fun filterSpans(
        name: String,
        value: String,
    ): List<Span> = listOf(Span("$name ", MUTED), Span(value.ifEmpty { "all" }, if (value.isEmpty()) MUTED else WARN_S), Span("  "))

    private fun metricLines(
        t: Totals,
        bars: Int,
        sparks: Boolean,
    ): List<Line> {
        val store = shown
        val saved = t.before - t.after
        val windows =
            WINDOWS.mapIndexed { i, (label, _) -> chip(label, i == window) } +
                Span("  ") +
                if (held != null) Span("frozen · $pending held", WARN_S) else Span("live", MUTED)
        val running = if (store.running.isEmpty()) "idle" else store.running.joinToString(", ") { it.tool }
        val kpi =
            listOf(
                Span("running ", MUTED),
                Span("${store.running.size} ($running)  "),
                Span("calls ", MUTED),
                Span("${t.calls} "),
                Span("(${t.failed} failed)  ", if (t.failed > 0) WARN_S else MUTED),
                Span("sent ", MUTED),
                Span("${liveCompact(t.after)}  "),
                Span("without rtok ", MUTED),
                Span("${liveCompact(t.before)}  "),
                Span("saved ", MUTED),
                Span("${liveCompact(saved)} (${percent(saved, t.before)})", ACCENT),
            )
        val latency = t.latency()?.let { (p50, p95) -> "${millis(p50)} (p95 ${millis(p95)})  " } ?: "-  "
        val metrics =
            listOf(
                Span("latency p50 ", MUTED),
                Span(latency),
                Span("symbols returned ", MUTED),
                Span("${t.symbolsReturned} of ${t.symbols} (${t.crossed} across projects)  "),
                Span("fallbacks ", MUTED),
                Span("${t.fallbacks} (${t.caps} capped)", if (t.fallbacks > 0) WARN_S else MUTED),
            )
        val answer = listOf(Span("files touched ", MUTED), Span("${t.filesTouched}  "), Span("projects with hits ", MUTED), Span(t.projectsHit.toString()))
        val spark = store.spark(window, shownNow)
        val trend = { what: String, values: List<Long> -> listOf(Span(sparkLabel(what, spark.spanMs).padEnd(LABEL_PAD), MUTED), Span(blocks(values), ACCENT)) }
        val lines = mutableListOf(windows, kpi, metrics, answer)
        if (sparks) {
            lines += trend("calls", spark.calls)
            lines += trend("tokens saved", spark.saved)
        }
        val tools = t.tools.entries.sortedWith(compareByDescending<Map.Entry<String, rtok.web.ToolTotals>> { it.value.calls }.thenBy { it.key })
        val top = (tools.firstOrNull()?.value?.calls ?: 1L).coerceAtLeast(1)
        for ((tool, v) in tools.take(bars)) {
            val width = ((v.calls * BAR_WIDTH + top - 1) / top).toInt()
            lines += listOf(Span(tool.padEnd(TOOL_PAD)), Span("█".repeat(width), ACCENT), Span(" ${v.calls} · ${liveCompact(v.saved)} saved", MUTED))
        }
        val listed = t.backends.values.sum()
        if (listed > 0) {
            val shares =
                t.backends.entries
                    .sortedBy { it.key }
                    .joinToString("  ") { "${it.key} ${percent(it.value, listed)}" }
            lines += listOf(Span("backend $shares", MUTED))
        }
        return lines
    }

    /**
     * [scope] is the names of the projects the live part is scoped to; a call on any other is marked. [drawn] is the
     * one-project view, empty on the overview, where no node is folded.
     */
    fun render(
        b: Buffer,
        r: Rect,
        scope: Set<String>?,
        drawn: Drawn,
        clock: (Double) -> String,
    ) {
        val t = shown.windowTotals(window, shownNow)
        // The bars yield so the feed keeps its rows; the sparklines yield first.
        val sparks = r.h >= SPARKS_FROM
        val spare = (r.h - if (sparks) ROWS_WITH_SPARKS else ROWS_WITHOUT).coerceAtLeast(0)
        val lines = metricLines(t, minOf(MAX_BARS, spare), sparks).toMutableList()
        lines +=
            listOf("caller [a]" to filter.caller, "tool [t]" to filter.tool, "project [o]" to filter.project)
                .flatMap { (n, v) -> filterSpans(n, v.take(FILTER_CHARS)) }
        val folded = shown.foldedCounters(drawn.nodes, drawn.more, scope, shownNow)
        if (folded.isNotEmpty()) lines += listOf(Span("folded calls  ", MUTED)) + folded.map { Span("${it.label} · ${it.calls} folded  ") }
        val (top, feed) = r.rows(Size.Fixed(lines.size), Size.Fill())
        b.paragraph(top, lines)
        val rows = filterFeed(shown.feed, filter)
        val inner = b.block(feed, "graph calls (${rows.size} of ${shown.feed.size})", MUTED)
        val cells =
            rows.map { c ->
                val project = c.project ?: "-"
                listOf(
                    clock(c.at / THOUSAND),
                    c.tool,
                    c.target ?: "-",
                    c.caller,
                    if (outsideScope(scope, c.project)) "$project $OUTSIDE_SCOPE" else project,
                    resultCell(c),
                )
            }
        b.table(inner, FEED_COLS, cells) { i, col ->
            when {
                !rows[i].ok -> ERR_S
                col == PROJECT_COL && outsideScope(scope, rows[i].project) -> WARN_S
                else -> null
            }
        }
    }
}
