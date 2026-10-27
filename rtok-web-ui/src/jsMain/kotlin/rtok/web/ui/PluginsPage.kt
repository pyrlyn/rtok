// Port of web/src/pages/Plugins.tsx: the catalogue with show groups and search, a switch per plugin that
// sends a `set` request (the next snapshot moves it), and the detail of the selected plugin (`?sel=`).
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.js.onClickFunction
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val NO_SAVING = "This plugin does not save tokens, so it records no Measurement rows."
private const val NO_ROWS = "No Measurement rows yet, so no saving is claimed."

/** A switch button: the server owns the state, a click only asks. */
private fun FlowContent.switch(
    app: App,
    p: JsonObject,
) {
    val id = p.str("id") ?: return
    val on = p.flag("enabled")
    button(classes = if (on) "switch on" else "switch") {
        attributes["role"] = "switch"
        attributes["aria-checked"] = on.toString()
        attributes["aria-label"] = "toggle $id"
        onClickFunction = { app.send(pluginToggle(id, !on)) }
        +(if (on) "on" else "off")
    }
}

private fun FlowContent.unknown(
    label: String,
    why: String,
) = span("muted") {
    attributes["title"] = why
    +label
}

private fun FlowContent.selectLink(
    app: App,
    p: JsonObject,
) {
    val id = p.str("id") ?: ""
    button(classes = "link") {
        onClickFunction = { setParam(app, "sel", id, "") }
        b { +(p.str("title") ?: id) }
    }
    span("muted") { +" ${p.strings("surfaces").joinToString(" · ")}" }
}

private fun FlowContent.pluginDetail(
    app: App,
    p: JsonObject,
) {
    val stats = p.obj("stats")
    val saved = savedOf(p)
    panel(p.str("title") ?: "", p.str("id")) {
        div("toolbar") {
            switch(app, p)
            span { +(if (p.flag("enabled")) "enabled" else "disabled") }
            p.strings("surfaces").forEach { pill("info", it) }
        }
        p("muted") { +(p.str("summary") ?: "") }
        val fields = (p["fields"] as? JsonArray).orEmpty().mapNotNull { it as? JsonArray }
        if (fields.isNotEmpty()) {
            kv(fields.map { f -> text(f.getOrNull(0)) to { +text(f.getOrNull(1)) } })
        }
        val rows = stats?.num("rows") ?: 0.0
        if (p.flag("saves_tokens") && stats != null) {
            val before = stats.num("est_before") ?: 0.0
            kv(
                listOf(
                    "rows" to { +fmt(stats.num("rows")) },
                    "est before → after" to { +"${compact(before)} → ${compact(stats.num("est_after"))}" },
                    "saved" to {
                        if (rows > 0 && saved != null) +"${compact(saved)} (${pct(saved / before, 0)})" else unknown("Unknown", NO_ROWS)
                    },
                ),
            )
        } else {
            p("muted small") { +(if (p.flag("saves_tokens")) "no Measurement rows yet" else "does not record Measurement rows") }
        }
        p("muted small") {
            +"toggle sends "
            code { +"""{"set":{"key":"plugins.${p.str("id")}.enabled"}}""" }
        }
    }
}

private fun text(e: kotlinx.serialization.json.JsonElement?): String = (e as? JsonPrimitive)?.content ?: e?.toString() ?: ""

/** The Plugins page. */
val PluginsPage =
    PageView { out, snap, app ->
        val plugins = snap.objects("plugins")
        if (plugins.isEmpty()) {
            out.panel("plugins") { p("muted") { +"No plugins in this frame" } }
            return@PageView
        }
        val show = param("show", "all")
        val rows = plugins.filter { matchesPlugin(it, show, param("q", "")) }
        val selected = plugins.firstOrNull { it.str("id") == param("sel", "") } ?: plugins.first()
        out.div("toolbar") {
            searchBox(app, "plugins-q", "id, title, summary")
            toggles(app, "show", PLUGIN_SHOW.map { it.first })
            span("muted") { +"${rows.size} shown" }
        }
        out.div("split") {
            panel("plugins", "catalogue") {
                dataTable(
                    "plugins",
                    rows,
                    listOf(
                        Column("on") { p -> switch(app, p) },
                        Column("plugin") { p -> selectLink(app, p) },
                        Column("rows", right = true) { p -> +fmt(p.obj("stats")?.num("rows")) },
                        Column("saved", right = true) { p ->
                            val s = savedOf(p)
                            when {
                                s == null || !p.flag("saves_tokens") -> unknown("n/a", NO_SAVING)
                                (p.obj("stats")?.num("rows") ?: 0.0) > 0 -> +compact(s)
                                else -> unknown("Unknown", NO_ROWS)
                            }
                        },
                    ),
                    "No plugin matches. Clear the filter or pick another group.",
                )
            }
            pluginDetail(app, selected)
        }
    }
