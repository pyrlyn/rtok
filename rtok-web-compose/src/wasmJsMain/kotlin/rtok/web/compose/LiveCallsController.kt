// The Graph page's live calls in Compose: the page subscribes to the server's `calls` frames while it is open;
// per-window totals with sparklines and latency, running calls, and the feed of finished calls with filters.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.web.ui.FeedFilter
import rtok.web.ui.callsSubscribe
import rtok.web.ui.compact
import rtok.web.ui.distinct
import rtok.web.ui.filterFeed
import rtok.web.ui.fixed
import rtok.web.ui.flag
import rtok.web.ui.fmt
import rtok.web.ui.num
import rtok.web.ui.obj
import rtok.web.ui.objects
import rtok.web.ui.str

/** The subscription and the latest `calls` frame; the socket owns the server side. */
class LiveCallsController(
    private val app: AppState,
) {
    var view by mutableStateOf<JsonObject?>(null)
        private set
    var filter by mutableStateOf(FeedFilter())
    private var subscribed = false

    init {
        app.on("calls") { view = it.body.obj("calls") }
    }

    /** Subscribe while [wanted] and the link is open; unsubscribe when the page closes. */
    fun want(
        wanted: Boolean,
        open: Boolean,
    ) {
        if (!open) {
            subscribed = false
            return
        }
        if (wanted != subscribed && app.send(callsSubscribe(wanted))) subscribed = wanted
    }
}

/** The live section under the graph page. */
@Composable
fun LiveCalls(c: LiveCallsController) =
    Panel("live calls", "graph tool calls as they happen") {
        val view = c.view
        if (view == null) {
            Muted("waiting for the first calls frame...")
            return@Panel
        }
        Windows(view)
        view.objects("running").forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("info", "running")
                Txt("${r.str("tool")} ${r.str("target") ?: "-"}", bold = true)
                Muted("${r.str("caller")} · ${r.str("project") ?: "-"}")
            }
        }
        Feed(c, view)
    }

@Composable
private fun Windows(view: JsonObject) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        view.objects("windows").forEach { w ->
            val saved = (w.num("before") ?: 0.0) - (w.num("after") ?: 0.0)
            val lat = w.obj("latency")
            Kpi(
                w.str("label") ?: "",
                fmt(w.num("calls")),
                "${fmt(w.num("failed"))} failed · ${compact(saved)} saved" +
                    (lat?.let { " · p50 ${fixed(it.num("p50") ?: 0.0, 0)} ms p95 ${fixed(it.num("p95") ?: 0.0, 0)} ms" } ?: ""),
            ) {
                val spark = w.obj("spark")?.get("calls") as? JsonArray
                Sparkline(spark.orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }, "calls in ${w.str("label")}")
            }
        }
    }

/** A chip row for one feed filter key ("" = all). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pick(
    feed: List<JsonObject>,
    key: String,
    current: String,
    set: (String) -> Unit,
) {
    val p = LocalPalette.current
    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (listOf("") + distinct(feed, key)).forEach { v ->
            val on = v == current
            Box(
                Modifier
                    .background(if (on) p.accent.copy(alpha = 0.18f) else p.surface, RoundedCornerShape(6.dp))
                    .clickable { set(v) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Txt(v.ifEmpty { "all ${key}s" }, color = if (on) p.accent else p.muted, size = 12) }
        }
    }
}

@Composable
private fun Feed(
    c: LiveCallsController,
    view: JsonObject,
) {
    val feed = view.objects("feed")
    val f = c.filter
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Pick(feed, "caller", f.caller) { c.filter = f.copy(caller = it) }
        Pick(feed, "tool", f.tool) { c.filter = f.copy(tool = it) }
        Pick(feed, "project", f.project) { c.filter = f.copy(project = it) }
    }
    Table(
        filterFeed(feed, f),
        listOf(
            Col<JsonObject>("time") { Txt(hms(((it.num("at") ?: 0.0) / 1000).toLong().toDouble())) },
            Col("tool") { Txt(it.str("tool") ?: "") },
            Col("symbol", 2f) { Txt(it.str("target") ?: "-") },
            Col("caller") { Txt(it.str("caller") ?: "") },
            Col("project") { Txt(it.str("project") ?: "-") },
            Col("result", 3f) { r ->
                when {
                    r.flag("interrupted") -> {
                        Pill("warn", "interrupted")
                    }

                    r.flag("ok") -> {
                        Txt("${r.str("backend") ?: "-"} · ${compact((r.num("before") ?: 0.0) - (r.num("after") ?: 0.0))} saved · ${fixed(r.num("ms") ?: 0.0, 0)} ms")
                    }

                    else -> {
                        Txt(r.str("error") ?: "failed", color = LocalPalette.current.fail)
                    }
                }
            },
        ),
        "No calls yet",
    )
}
