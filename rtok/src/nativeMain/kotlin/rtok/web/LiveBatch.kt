// Port of src/web/live.rs `CallBatch`, `CallSummary`, `coalesce` and `events_per_poll` (T329.15, T573): one poll's
// graph call events folded into one frame. The poller and `rtok tui`'s reader come with the rest of live.rs.
package rtok.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.config.Graph
import rtok.store.EventPhase
import rtok.store.GraphEvent

/** How often the store is asked for new events, in milliseconds (live.rs `POLL`). */
const val POLL_MS = 250L

/** `[plugins.graph] live_max_events_per_s` as the events one poll lists; the rest of a burst is counted in `omitted` and `summary`. */
fun eventsPerPoll(cfg: Graph): Int {
    val perPoll = cfg.liveMaxEventsPerS.toULong() * POLL_MS.toULong()
    val polls = (perPoll + 999uL) / 1000uL
    return polls.coerceAtMost(Int.MAX_VALUE.toULong()).toInt().coerceAtLeast(1)
}

@Serializable
data class CallSummary(
    val starts: Long = 0,
    val ends: Long = 0,
    val failed: Long = 0,
    /** Sums of the `samples` of the end events: the `Measurement` columns `rtok stats` adds up. */
    @SerialName("est_before") val estBefore: Long = 0,
    @SerialName("est_after") val estAfter: Long = 0,
    /** `lsp_fallback` rows of the ended calls. */
    val fallbacks: Long = 0,
    /** Rows with a `ref_id`: answers cut at `max_tokens`. */
    val caps: Long = 0,
    val symbols: Long = 0,
    /** Ended calls whose scope held more than one project. */
    val crossed: Long = 0,
    @SerialName("symbols_returned") val symbolsReturned: Long = 0,
    @SerialName("files_touched") val filesTouched: Long = 0,
    @SerialName("projects_hit") val projectsHit: Long = 0,
)

/** What one poll found. `summary` counts every event of the poll, including the ones left out of `events`. */
@Serializable
data class CallBatch(
    /** Newest last; a call's start and progress are left out when its end is in the batch. */
    val events: List<GraphEvent> = emptyList(),
    /** Events cut because the batch held more than the poll's cap. */
    val omitted: Long = 0,
    /** Every call with an end in the poll, including ones whose end row was cut. Sorted. */
    val ended: Set<String> = emptySet(),
    val summary: CallSummary = CallSummary(),
    /** The newest event id the batch covers. */
    val head: Long = 0,
    /** What the store calls the sessions of `events`, sorted by session. */
    val callers: Map<String, String> = emptyMap(),
) {
    /** The caller column of [session]: the store's name, else the session's start. */
    fun caller(session: String): String = callers[session] ?: bareCaller(session)
}

private fun UInt?.wide(): Long = this?.toLong() ?: 0L

/** Folds [events] (oldest first) into a frame listing at most [cap] of them, or null when there are none. */
fun coalesce(
    events: List<GraphEvent>,
    cap: Int,
): CallBatch? {
    val head = events.lastOrNull()?.id ?: return null
    val ended =
        events
            .filter { it.phase == EventPhase.End }
            .map { it.call }
            .distinct()
            .sorted()
            .toSet()
    var s = CallSummary()
    for (e in events) {
        when (e.phase) {
            EventPhase.Start -> {
                s = s.copy(starts = s.starts + 1)
            }

            EventPhase.Progress -> {}

            EventPhase.End -> {
                s =
                    s.copy(
                        ends = s.ends + 1,
                        failed = s.failed + if (e.ok) 0 else 1,
                        symbols = s.symbols + e.symbols.wide(),
                        crossed = s.crossed + if ((e.total ?: 0u) > 1u) 1 else 0,
                        symbolsReturned = s.symbolsReturned + e.symbolsReturned.wide(),
                        filesTouched = s.filesTouched + e.filesTouched.wide(),
                        projectsHit = s.projectsHit + e.projectsHit.wide(),
                        estBefore = s.estBefore + e.samples.sumOf { it.estBefore.toLong() },
                        estAfter = s.estAfter + e.samples.sumOf { it.estAfter.toLong() },
                        fallbacks = s.fallbacks + e.samples.count { it.kind == "lsp_fallback" },
                        caps = s.caps + e.samples.count { it.refId != null },
                    )
            }
        }
    }
    val kept = events.filter { it.phase == EventPhase.End || it.call !in ended }
    val omitted = (kept.size - cap).coerceAtLeast(0)
    // Ended rows are history and a running call is state: the oldest ended rows go first.
    val order = kept.indices.filter { kept[it].phase == EventPhase.End } + kept.indices.filter { kept[it].phase != EventPhase.End }
    val cut = order.take(omitted).toSet()
    return CallBatch(
        events = kept.filterIndexed { i, _ -> i !in cut },
        omitted = omitted.toLong(),
        ended = ended,
        summary = s,
        head = head,
    )
}
