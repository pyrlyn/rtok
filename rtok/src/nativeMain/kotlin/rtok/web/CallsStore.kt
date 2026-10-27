// Port of src/web/calls_store.rs (T329.26, T480, T484): the live calls aggregation. `rtok web` folds each poll's batch
// here and sends the totals in the `calls` frame (CallsView.kt); `rtok tui` folds the same batches with the same store,
// so both surfaces show one computation. Totals come from the batch `summary`, so they equal what `rtok stats` sums.
@file:OptIn(ExperimentalTime::class)

package rtok.web

import kotlinx.serialization.Serializable
import rtok.config.Graph
import rtok.store.EventPhase
import rtok.store.GraphEvent
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** A call with no end this long after its start is shown as interrupted: its process is gone. */
const val INTERRUPT_MS = 120_000L
private const val LONGEST_BUCKET_MS = 900_000L

/** Latencies kept for the percentiles. */
const val LATENCY_KEEP = 1000

/** Slots of a sparkline; the page and the TUI draw the same series. */
const val SPARK_SLOTS = 30

/** The window chips of the page; the last one is "since start" and reads the running total. */
val WINDOWS: List<Pair<String, Long>> =
    listOf("1 min" to 60_000L, "5 min" to 300_000L, "15 min" to LONGEST_BUCKET_MS, "since start" to Long.MAX_VALUE)

/** The mark on a feed row whose project is not in the scope; the page shows the same text. */
const val OUTSIDE_SCOPE = "outside scope"

/** Folded calls are those of this long ago or less, fixed so the TUI and the page count alike. */
const val FOLD_MS = 300_000L

private const val P50 = 0.5
private const val P95 = 0.95

/** Characters of a session id the caller column shows when the store names nobody. */
private const val BARE_CALLER_CHARS = 8
private const val MS_PER_MIN = 60_000.0

@Serializable
data class Running(
    val call: String,
    val tool: String,
    val target: String?,
    val project: String?,
    val session: String,
    /** What the store calls the session (`claude 3f9a1c2e`), else the session's start. */
    val caller: String,
    /** The reader's clock: the interrupt timeout must not depend on the writer's. */
    val at: Long,
)

@Serializable
data class Finished(
    val call: String,
    val tool: String,
    val target: String?,
    val project: String?,
    val session: String,
    val caller: String,
    val ok: Boolean,
    val error: String?,
    val backend: String?,
    val ms: Double?,
    val before: Long,
    val after: Long,
    val at: Long,
    val interrupted: Boolean,
)

/** Calls and tokens saved per slot over a window, oldest first; the slots add up to the window totals. */
@Serializable
data class Spark(
    @kotlinx.serialization.SerialName("span_ms") val spanMs: Long,
    val calls: List<Long>,
    val saved: List<Long>,
)

data class ToolTotals(
    var calls: Long = 0,
    var saved: Long = 0,
)

/** One window's sums. `tools` and `ms` come from the listed events; the rest from the batch `summary`, exact under a burst. */
class Totals {
    var calls = 0L
    var failed = 0L
    var before = 0L
    var after = 0L
    val tools = HashMap<String, ToolTotals>()
    val backends = HashMap<String, Long>()
    var fallbacks = 0L
    var caps = 0L
    var symbols = 0L
    var crossed = 0L
    var symbolsReturned = 0L
    var filesTouched = 0L
    var projectsHit = 0L

    /** Milliseconds of the listed calls, oldest first and at most [LATENCY_KEEP]. */
    val ms = ArrayList<Double>()

    /** Median and 95th percentile of the kept latencies, null before any call has ended. */
    fun latency(): Pair<Double, Double>? {
        if (ms.isEmpty()) return null
        val v = ms.sorted()
        return percentile(v, P50) to percentile(v, P95)
    }

    fun add(from: Totals) {
        calls += from.calls
        failed += from.failed
        before += from.before
        after += from.after
        fallbacks += from.fallbacks
        caps += from.caps
        symbols += from.symbols
        crossed += from.crossed
        symbolsReturned += from.symbolsReturned
        filesTouched += from.filesTouched
        projectsHit += from.projectsHit
        ms.addAll(from.ms)
        val drop = ms.size - LATENCY_KEEP
        if (drop > 0) ms.subList(0, drop).clear()
        for ((tool, t) in from.tools) {
            val own = tools.getOrPut(tool) { ToolTotals() }
            own.calls += t.calls
            own.saved += t.saved
        }
        for ((b, n) in from.backends) backends[b] = (backends[b] ?: 0) + n
    }

    fun copy(): Totals = Totals().also { it.add(this) }
}

/** The value at rank `ceil(p * n)`: a latency that was really measured, not an interpolation. */
private fun percentile(
    sorted: List<Double>,
    p: Double,
): Double {
    val rank = ceil(p * sorted.size).toInt()
    return sorted[rank.coerceIn(1, sorted.size) - 1]
}

/** The poller's and the panes' clock, in epoch milliseconds. */
fun clockMs(): Long = Clock.System.now().toEpochMilliseconds()

/** The session as a caller column shows it when the store names nobody. */
fun bareCaller(session: String): String = session.codePointPrefix(BARE_CALLER_CHARS)

private fun String.codePointPrefix(n: Int): String {
    var i = 0
    var taken = 0
    while (i < length && taken < n) {
        i += if (this[i].isHighSurrogate() && i + 1 < length) 2 else 1
        taken++
    }
    return substring(0, i)
}

private fun ended(
    e: GraphEvent,
    at: Long,
    caller: String,
) = Finished(
    call = e.call,
    tool = e.tool,
    target = e.target,
    project = e.project,
    session = e.session,
    caller = caller,
    ok = e.ok,
    error = e.error,
    backend = e.backend,
    ms = e.ms,
    before = e.samples.sumOf { it.estBefore.toLong() },
    after = e.samples.sumOf { it.estAfter.toLong() },
    at = at,
    interrupted = false,
)

class CallsStore(
    cfg: Graph = Graph(),
) {
    val running = ArrayList<Running>()

    /** Newest first. */
    val feed = ArrayList<Finished>()

    /** `[plugins.graph] live_feed_rows`: how long the feed grows. */
    val feedRows: Int = cfg.liveFeedRows.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt()

    /** `[plugins.graph] live_heat_window_s`, carried to the page in the view. */
    val heatWindowS: Long = cfg.liveHeatWindowS.toLong()

    /** One per batch, kept for the longest window. */
    private val buckets = ArrayList<Pair<Long, Totals>>()
    val bucketCount: Int get() = buckets.size

    val all = Totals()

    /** Folds one batch in; totals come from the batch `summary`, so a burst cut down still adds up. */
    fun fold(
        batch: CallBatch,
        now: Long,
    ) {
        val ends = batch.events.filter { it.phase == EventPhase.End }
        // From the batch, not its rows: an end the display cap cut still finished its call.
        val done = batch.ended
        running.removeAll { it.call in done }
        for (e in batch.events) {
            if (e.phase == EventPhase.End || e.call in done) continue
            // A progress event refreshes the call but keeps its start time and its place in the list.
            val slot = running.indexOfFirst { it.call == e.call }
            val at = if (slot >= 0) running[slot].at else now
            val r = Running(e.call, e.tool, e.target, e.project, e.session, batch.caller(e.session), at)
            if (slot >= 0) running[slot] = r else running += r
        }
        val sum = batch.summary
        val bucket =
            Totals().apply {
                calls = sum.ends
                failed = sum.failed
                before = sum.estBefore
                after = sum.estAfter
                fallbacks = sum.fallbacks
                caps = sum.caps
                symbols = sum.symbols
                crossed = sum.crossed
                symbolsReturned = sum.symbolsReturned
                filesTouched = sum.filesTouched
                projectsHit = sum.projectsHit
            }
        val rows = ends.map { ended(it, now, batch.caller(it.session)) }
        for (row in rows) {
            val t = bucket.tools.getOrPut(row.tool) { ToolTotals() }
            t.calls += 1
            t.saved += row.before - row.after
            row.backend?.let { bucket.backends[it] = (bucket.backends[it] ?: 0) + 1 }
            row.ms?.let { bucket.ms += it }
        }
        all.add(bucket)
        // A late end for a call already marked interrupted replaces the mark.
        feed.removeAll { it.call in done }
        feed.addAll(0, rows.asReversed())
        truncateFeed()
        buckets += now to bucket
        buckets.removeAll { (at, _) -> now - at > LONGEST_BUCKET_MS }
    }

    /** Moves a call that never ended into the feed, marked, so it stops counting as running. */
    fun sweep(now: Long) {
        val stale = running.filter { now - it.at > INTERRUPT_MS }
        running.removeAll { now - it.at > INTERRUPT_MS }
        val rows =
            stale.asReversed().map {
                Finished(it.call, it.tool, it.target, it.project, it.session, it.caller, false, null, null, null, 0, 0, it.at, true)
            }
        feed.addAll(0, rows)
        truncateFeed()
    }

    private fun truncateFeed() {
        if (feed.size > feedRows) feed.subList(feedRows, feed.size).clear()
    }

    /** Summed from the buckets on every call, never kept as a second counter. */
    fun windowTotals(
        window: Int,
        now: Long,
    ): Totals {
        val ms = WINDOWS[window].second
        if (ms == Long.MAX_VALUE) return all.copy()
        val sum = Totals()
        for ((at, b) in buckets) if (now - at <= ms) sum.add(b)
        return sum
    }

    /** The window's buckets spread over [SPARK_SLOTS] equal slots ending at `now`. */
    fun spark(
        window: Int,
        now: Long,
    ): Spark {
        val spanMs = minOf(WINDOWS[window].second, LONGEST_BUCKET_MS)
        val width = spanMs / SPARK_SLOTS
        val calls = LongArray(SPARK_SLOTS)
        val saved = LongArray(SPARK_SLOTS)
        for ((at, b) in buckets) {
            val age = (now - at).coerceAtLeast(0)
            if (age > spanMs) continue
            // A bucket exactly `spanMs` old lands one past the first slot: it belongs in it.
            val back = (age / width).coerceAtMost(SPARK_SLOTS - 1L).toInt()
            calls[SPARK_SLOTS - 1 - back] += b.calls
            saved[SPARK_SLOTS - 1 - back] += b.before - b.after
        }
        return Spark(spanMs, calls.toList(), saved.toList())
    }

    /**
     * The page's `foldedCounters`: with [more] nodes left out, a recent in-scope call whose target no drawn node is named
     * after counts on the drawn node whose path equals or is a directory prefix of the target (longest first), else on
     * the "+N more" group. Largest first.
     */
    fun foldedCounters(
        drawn: List<DrawnNode>,
        more: Int,
        scope: Set<String>?,
        now: Long,
    ): List<FoldedCount> {
        if (more == 0) return emptyList()
        val byRoot = drawn.indices.sortedByDescending { drawn[it].root.length }
        val counts = LongArray(drawn.size)
        var group = 0L
        val finished = feed.filter { !it.interrupted && now - it.at <= FOLD_MS }.map { it.target to it.project }
        val live = running.filter { now - it.at <= FOLD_MS }.map { it.target to it.project }
        val targets =
            (finished + live).mapNotNull { (target, project) ->
                target?.takeUnless { outsideScope(scope, project) || drawn.any { n -> n.label == it } }
            }
        for (target in targets) {
            val holder =
                byRoot.firstOrNull { i ->
                    val root = drawn[i].root
                    root.isNotEmpty() && (target == root || (target.startsWith(root) && target.substring(root.length).startsWith('/')))
                }
            if (holder != null) counts[holder]++ else group++
        }
        val out =
            drawn.indices
                .filter { counts[it] > 0 }
                .map { FoldedCount(drawn[it].label, counts[it]) }
                .toMutableList()
        if (group > 0) out += FoldedCount("+$more more", group)
        return out.sortedByDescending { it.calls }
    }
}

/** What a sparkline is called: "calls over the last 5 min", as the page names it. */
fun sparkLabel(
    what: String,
    spanMs: Long,
): String = "$what over the last ${(spanMs / MS_PER_MIN).roundToLong()} min"

/** One registry row, as far as the scope needs it. */
data class ScopeRow(
    val id: Int,
    val name: String,
    val selected: Boolean,
    val links: List<Int>,
)

/**
 * The names of the projects the live part is scoped to: the drilled project, else the selected one, with everything its
 * links reach. null when there is none, so no call is "outside" a scope that was never chosen (the page's `scopeNames`).
 */
fun scopeNames(
    rows: List<ScopeRow>,
    drilled: Int?,
): Set<String>? {
    val from = drilled ?: rows.firstOrNull { it.selected }?.id ?: return null
    val seen = HashSet<Int>()
    val todo = ArrayDeque(listOf(from))
    // A link to an id the registry no longer holds is ignored; cycles end at the first revisit.
    while (todo.isNotEmpty()) {
        val id = todo.removeLast()
        val row = rows.firstOrNull { it.id == id } ?: continue
        if (seen.add(id)) todo.addAll(row.links)
    }
    val names = rows.filter { it.id in seen }.mapTo(HashSet()) { it.name }
    return names.ifEmpty { null }
}

/** A call names its project; one the scope lacks is counted and listed but never lights the canvas. */
fun outsideScope(
    scope: Set<String>?,
    project: String?,
): Boolean = scope != null && project != null && project !in scope

/** A node the one-project view draws: its label and the path it stands for. */
data class DrawnNode(
    val label: String,
    val root: String,
)

/** Calls whose own node the cap folded into "+N more", on the node that holds them. */
data class FoldedCount(
    val label: String,
    val calls: Long,
)

/** Empty strings mean "all". */
data class FeedFilter(
    val caller: String = "",
    val tool: String = "",
    val project: String = "",
) {
    fun keeps(r: Finished): Boolean =
        (caller.isEmpty() || r.caller == caller) &&
            (tool.isEmpty() || r.tool == tool) &&
            (project.isEmpty() || project == r.project)
}

fun filterFeed(
    feed: List<Finished>,
    f: FeedFilter,
): List<Finished> = feed.filter(f::keeps)

/** The values a filter can pick, sorted. */
fun distinct(
    feed: List<Finished>,
    pick: (Finished) -> String?,
): List<String> =
    feed
        .mapNotNull(pick)
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
