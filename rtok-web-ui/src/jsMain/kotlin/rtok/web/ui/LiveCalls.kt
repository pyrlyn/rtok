// Port of web/src/pages/graph3d/live/LiveSection.tsx (without the canvas), CallFeed.tsx and LiveMetrics.tsx:
// the graph page subscribes to the server's `calls` frames while it is open; running calls, per-window
// totals with sparklines and latency, and the filterable feed of finished calls.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.b
import kotlinx.html.div
import kotlinx.html.js.onChangeFunction
import kotlinx.html.li
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.HTMLSelectElement

/** The subscription and the latest `calls` frame; the socket owns the server side. */
object LiveCalls {
    private var subscribed = false
    var view: JsonObject? = null
        private set
    private var filter = FeedFilter()
    private var app: App? = null

    fun install(app: App) {
        this.app = app
        Replies.on("calls") { f ->
            view = f.body.obj("calls")
            app.refresh()
        }
    }

    /** Called on each render: subscribe while [wanted] and the link is open, unsubscribe when the page closes. */
    fun want(
        wanted: Boolean,
        open: Boolean,
    ) {
        if (!open) {
            subscribed = false
            return
        }
        if (wanted != subscribed && app?.send(callsSubscribe(wanted)) == true) subscribed = wanted
    }

    internal fun setFilter(f: FeedFilter) {
        filter = f
        app?.refresh()
    }

    internal fun filter() = filter
}

private const val MS = 1000.0

private fun FlowContent.pick(
    feed: List<JsonObject>,
    key: String,
) {
    val current =
        when (key) {
            "caller" -> LiveCalls.filter().caller
            "tool" -> LiveCalls.filter().tool
            else -> LiveCalls.filter().project
        }
    select {
        attributes["aria-label"] = "filter by $key"
        onChangeFunction = { e ->
            val v = (e.target as HTMLSelectElement).value
            val f = LiveCalls.filter()
            LiveCalls.setFilter(
                when (key) {
                    "caller" -> f.copy(caller = v)
                    "tool" -> f.copy(tool = v)
                    else -> f.copy(project = v)
                },
            )
        }
        option {
            value = ""
            +"all ${key}s"
        }
        distinct(feed, key).forEach { v ->
            option {
                value = v
                if (v == current) selected = true
                +v
            }
        }
    }
}

private fun FlowContent.windows(view: JsonObject) {
    div("grid kpis") {
        view.objects("windows").forEach { w ->
            val saved = (w.num("before") ?: 0.0) - (w.num("after") ?: 0.0)
            val lat = w.obj("latency")
            kpi(
                w.str("label") ?: "",
                fmt(w.num("calls")),
                "${fmt(w.num("failed"))} failed · ${compact(saved)} saved" +
                    (lat?.let { " · p50 ${fixed(it.num("p50") ?: 0.0, 0)} ms p95 ${fixed(it.num("p95") ?: 0.0, 0)} ms" } ?: ""),
            ) {
                val spark = w.obj("spark")?.get("calls") as? kotlinx.serialization.json.JsonArray
                sparkline(spark.orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }, "calls in ${w.str("label")}")
            }
        }
    }
}

private fun FlowContent.running(view: JsonObject) {
    val running = view.objects("running")
    if (running.isEmpty()) return
    ul("plain") {
        attributes["aria-label"] = "running calls"
        running.forEach { r ->
            li {
                pill("info", "running")
                b { +" ${r.str("tool")} " }
                +(r.str("target") ?: "-")
                span("muted") { +" ${r.str("caller")} · ${r.str("project") ?: "-"}" }
            }
        }
    }
}

private fun FlowContent.feed(view: JsonObject) {
    val feed = view.objects("feed")
    div("toolbar") {
        pick(feed, "caller")
        pick(feed, "tool")
        pick(feed, "project")
    }
    dataTable(
        "graph calls",
        filterFeed(feed, LiveCalls.filter()),
        listOf(
            Column("time") { r -> +hms(((r.num("at") ?: 0.0) / MS).toLong().toDouble()) },
            Column("tool") { r -> +(r.str("tool") ?: "") },
            Column("symbol") { r -> +(r.str("target") ?: "-") },
            Column("caller") { r -> +(r.str("caller") ?: "") },
            Column("project") { r -> +(r.str("project") ?: "-") },
            Column("result") { r ->
                when {
                    r.flag("interrupted") -> {
                        pill("warn", "interrupted")
                    }

                    r.flag("ok") -> {
                        +"${r.str("backend") ?: "-"} · ${compact((r.num("before") ?: 0.0) - (r.num("after") ?: 0.0))} saved · ${fixed(r.num("ms") ?: 0.0, 0)} ms"
                    }

                    else -> {
                        span("fail") { +(r.str("error") ?: "failed") }
                    }
                }
            },
        ),
        "No calls yet",
    )
}

/** The live section under the graph page. */
fun FlowContent.liveCalls() {
    val view = LiveCalls.view
    panel("live calls", "graph tool calls as they happen") {
        if (view == null) {
            p("muted") { +"waiting for the first calls frame..." }
            return@panel
        }
        windows(view)
        running(view)
        feed(view)
    }
}
