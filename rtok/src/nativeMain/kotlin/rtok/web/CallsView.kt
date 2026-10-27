// Port of src/web/calls_view.rs (T484): the `calls` frame. The poller folds every batch into one [CallsStore] and sends
// this view of it, so the page renders totals instead of computing them and shows what `rtok tui` shows.
package rtok.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ToolView(
    val tool: String,
    val calls: Long,
    val saved: Long,
)

@Serializable
data class Latency(
    val p50: Double,
    val p95: Double,
)

@Serializable
data class WindowView(
    val label: String,
    val calls: Long,
    val failed: Long,
    val before: Long,
    val after: Long,
    /** Most calls first. */
    val tools: List<ToolView>,
    val backends: Map<String, Long>,
    val fallbacks: Long,
    val caps: Long,
    val symbols: Long,
    val crossed: Long,
    @SerialName("symbols_returned") val symbolsReturned: Long,
    @SerialName("files_touched") val filesTouched: Long,
    @SerialName("projects_hit") val projectsHit: Long,
    /** null before any call in the window has ended. */
    val latency: Latency?,
    val spark: Spark,
) {
    companion object {
        fun of(
            label: String,
            t: Totals,
            spark: Spark,
        ): WindowView =
            WindowView(
                label = label,
                calls = t.calls,
                failed = t.failed,
                before = t.before,
                after = t.after,
                // Sorted on the name first and the sort is stable, so equal bars do not swap places.
                tools =
                    t.tools.entries
                        .sortedBy { it.key }
                        .map { (tool, v) -> ToolView(tool, v.calls, v.saved) }
                        .sortedByDescending { it.calls },
                backends =
                    t.backends.entries
                        .sortedBy { it.key }
                        .associate { it.key to it.value },
                fallbacks = t.fallbacks,
                caps = t.caps,
                symbols = t.symbols,
                crossed = t.crossed,
                symbolsReturned = t.symbolsReturned,
                filesTouched = t.filesTouched,
                projectsHit = t.projectsHit,
                latency = t.latency()?.let { (p50, p95) -> Latency(p50, p95) },
                spark = spark,
            )
    }
}

/** The state of the live calls panel at `now`. */
@Serializable
data class CallsView(
    /** The server's clock in epoch milliseconds: the page ages rows by `now - at` on this clock. */
    val now: Long,
    val running: List<Running>,
    /** Newest first. */
    val feed: List<Finished>,
    @SerialName("heat_window_s") val heatWindowS: Long,
    /** One per chip, in the order of [WINDOWS]; the last is "since start". */
    val windows: List<WindowView>,
) {
    companion object {
        fun of(
            store: CallsStore,
            now: Long,
        ): CallsView =
            CallsView(
                now = now,
                running = store.running.toList(),
                feed = store.feed.toList(),
                heatWindowS = store.heatWindowS,
                windows = WINDOWS.mapIndexed { i, (label, _) -> WindowView.of(label, store.windowTotals(i, now), store.spark(i, now)) },
            )
    }
}
