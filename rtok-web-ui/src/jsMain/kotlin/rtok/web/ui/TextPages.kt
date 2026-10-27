// Port of web/src/pages/Config.tsx, Hosts.tsx and parts.tsx TextPage/Missing/Kv/OtherLines: pages whose
// snapshot field is the CLI's text, parsed in commonMain, with a raw-text switch kept in `?raw=`.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.code
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h3
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** parts.tsx `TextPage`: the missing-field alert, a note and the raw/parsed switch. */
internal fun FlowContent.textPage(
    app: App,
    snap: JsonObject,
    page: String,
    command: String,
    note: String,
    body: FlowContent.(String) -> Unit,
) {
    val text = (snap[page] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (text == null) {
        panel(page) {
            p("fail") {
                +"$page did not answer this tick (Snapshot.$page = null). "
                code { +command }
                +" has the details."
            }
        }
        return
    }
    div("toolbar") {
        span("muted small") { +note }
        toggles(app, "raw", listOf("parsed", "raw text"))
    }
    if (param("raw", "parsed") == "raw text") {
        panel(page, "verbatim /ws string") { pre { +text } }
    } else {
        body(text)
    }
}

internal fun FlowContent.otherLines(lines: List<String>) {
    if (lines.isEmpty()) return
    panel("other lines", "not charted yet, verbatim") { pre { +lines.joinToString("\n") } }
}

internal fun FlowContent.kv(rows: List<Pair<String, FlowContent.() -> Unit>>) {
    dl("kv") {
        rows.forEach { (k, v) ->
            dt("muted") { +k }
            dd { v() }
        }
    }
}

/** The Config page. */
val ConfigPage =
    PageView { out, snap, app ->
        out.textPage(app, snap, "config", "rtok config show --sources", "read-only: a page view never creates files") { text ->
            val entries = parseConfig(text)
            val source = param("source", "all")
            val rows = entries.filter { matchesConfig(it, source, param("q", "")) }
            div("toolbar") {
                searchBox(app, "config-q", "keys and values")
                toggles(app, "source", listOf("all") + CONFIG_SOURCES)
                span("muted") { +"${rows.size} keys" }
            }
            p("muted small") { +"precedence: default < user (~/.rtok/config.toml) < project (.rtok.toml) < env (RTOK_*) < flag" }
            panel("effective config", "config show --sources") {
                val groups = groupConfig(rows)
                if (groups.isEmpty()) {
                    p("muted") { +(if (entries.isEmpty()) "No config keys reported" else "No key matches. Clear the filter or pick another layer.") }
                }
                groups.forEach { (group, items) ->
                    section {
                        attributes["aria-label"] = group
                        h3("kicker") { +"[$group]" }
                        dl("kv") {
                            items.forEach { r ->
                                dt { +r.key.removePrefix("$group.").ifEmpty { r.key } }
                                dd {
                                    span("muted") { +r.value }
                                    +" "
                                    pill(sourceTone(r.source), r.source)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

private fun FlowContent.host(b: HostBlock) {
    val note = hostNote(b.note)
    panel(b.name, b.kind) {
        pill(note.tone, note.label)
        kv(
            listOf(
                "app" to { +(b.app ?: "not found") },
                "config" to { if (b.config.isEmpty()) span("muted") { +"none" } else b.config.forEach { c -> div { +c } } },
            ),
        )
        if (b.modules.isNotEmpty()) {
            ul("plain") {
                b.modules.forEach { (name, value) ->
                    val s = moduleState(value)
                    li {
                        span("muted") { +"$name " }
                        pill(s.tone, s.label)
                        if (s.rest.isNotEmpty()) code { +" ${s.rest}" }
                    }
                }
            }
        }
        b.skip?.let { p("muted small") { +it } }
    }
}

/** The Hosts page; the junk card follows once junk reaches the snapshot. */
val HostsPage =
    PageView { out, snap, app ->
        out.textPage(app, snap, "hosts", "rtok agents list", "`rtok agents list`, one block per host variant, cached so the 2 s tick never waits") { text ->
            val view = parseHosts(text)
            when {
                view.probing -> {
                    panel("hosts") { p("muted") { +"probing hosts: the first probe runs in the background and the tick never blocks on it" } }
                }

                view.blocks.isEmpty() && view.other.isEmpty() -> {
                    panel("hosts") { p("muted") { +"No hosts reported" } }
                }

                else -> {
                    div("grid cards") { view.blocks.forEach { host(it) } }
                    otherLines(view.other)
                }
            }
        }
        // Hosts.tsx renders the junk card under the hosts; not ready until junk reaches the snapshot.
        pendingPage("junk", "rtok agents junk report").render(out, snap, app)
    }

private fun FlowContent.service(s: ServiceRow) {
    panel(s.name, "service") {
        if (s.running) pill("ok", "running") else pill("muted", "stopped")
        kv(
            listOf(
                "pid" to { +(s.pid ?: "none") },
                "uptime" to { +humanSecs(s.uptimeSecs) },
                "log" to { +(s.log ?: "Unknown") },
            ),
        )
        p("muted small") {
            +"CLI-only: "
            code { +serviceCommand(s) }
        }
    }
}

private fun FlowContent.otel(
    o: OtelView,
    lastFlush: String?,
) {
    kv(listOf("endpoint" to { +(o.endpoint ?: "not set") }))
    div("grid kpis") {
        kpi("calls", fmt(o.callsMark?.toDouble()), "${fmt(o.callsPending?.toDouble())} pending")
        kpi("logs", fmt(o.logsMark?.toDouble()), "${fmt(o.logsPending?.toDouble())} pending")
        kpi("sessions", fmt(o.sessionsMark?.toDouble()), "watermark")
    }
    lastFlush?.let { p("muted small") { +it } }
}

/** The Services page (Services.tsx): read-only supervisor state and the OpenTelemetry exporter. */
val ServicesPage =
    PageView { out, snap, app ->
        out.textPage(app, snap, "services", "rtok demon status", "supervisor state in ~/.rtok/demon") { text ->
            val view = parseServices(text)
            if (view.services.isEmpty()) {
                panel("services") { p("muted") { +"No services reported" } }
            } else {
                p("muted small") { +"${view.services.count { it.running }} of ${view.services.size} running" }
                div("grid cards") { view.services.forEach { service(it) } }
            }
            panel("OpenTelemetry export", "rtok otel status") {
                view.otel?.let { otel(it, view.lastFlush) } ?: p("muted") { +"otel status unavailable" }
            }
            otherLines(view.other)
        }
    }
