// Config, Hosts and Services in Compose: pages whose snapshot field is CLI text, parsed in :rtok-web-model,
// with the raw-text switch kept in `?raw=`.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.web.ui.CONFIG_SOURCES
import rtok.web.ui.HostBlock
import rtok.web.ui.ServiceRow
import rtok.web.ui.fmt
import rtok.web.ui.groupConfig
import rtok.web.ui.hostNote
import rtok.web.ui.humanSecs
import rtok.web.ui.matchesConfig
import rtok.web.ui.moduleState
import rtok.web.ui.obj
import rtok.web.ui.parseConfig
import rtok.web.ui.parseHosts
import rtok.web.ui.parseServices
import rtok.web.ui.serviceCommand
import rtok.web.ui.sourceTone

/** The missing-field alert, a note and the raw/parsed switch around [body]. */
@Composable
fun TextPage(
    app: AppState,
    snap: JsonObject,
    page: String,
    command: String,
    note: String,
    body: @Composable (String) -> Unit,
) {
    val text = (snap[page] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (text == null) {
        Panel(page) { Txt("$page did not answer this tick (Snapshot.$page = null). `$command` has the details.", color = LocalPalette.current.fail) }
        return
    }
    Toolbar {
        Muted(note)
        Toggles(app, "raw", listOf("parsed", "raw text"))
    }
    if (app.param("raw", "parsed") == "raw text") Panel(page, "verbatim /ws string") { Txt(text) } else body(text)
}

@Composable
private fun OtherLines(lines: List<String>) {
    if (lines.isNotEmpty()) Panel("other lines", "not charted yet, verbatim") { Txt(lines.joinToString("\n")) }
}

@Composable
private fun Kv(rows: List<Pair<String, String>>) =
    Column {
        rows.forEach { (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(k, Modifier.width(90.dp))
                Txt(v)
            }
        }
    }

/** The Config page. */
val ConfigPage =
    PageView { snap, app ->
        TextPage(app, snap, "config", "rtok config show --sources", "read-only: a page view never creates files") { text ->
            val entries = parseConfig(text)
            val rows = entries.filter { matchesConfig(it, app.param("source", "all"), app.param("q", "")) }
            Toolbar {
                SearchBox(app, "keys and values")
                Toggles(app, "source", listOf("all") + CONFIG_SOURCES)
                Muted("${rows.size} keys")
            }
            Muted("precedence: default < user (~/.rtok/config.toml) < project (.rtok.toml) < env (RTOK_*) < flag")
            Panel("effective config", "config show --sources") {
                val groups = groupConfig(rows)
                if (groups.isEmpty()) Muted(if (entries.isEmpty()) "No config keys reported" else "No key matches. Clear the filter or pick another layer.")
                groups.forEach { (group, items) ->
                    Txt("[$group]", color = LocalPalette.current.muted, bold = true)
                    items.forEach { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Txt(r.key.removePrefix("$group.").ifEmpty { r.key }, Modifier.width(240.dp))
                            Muted(r.value)
                            Pill(sourceTone(r.source), r.source)
                        }
                    }
                }
            }
        }
    }

@Composable
private fun Host(b: HostBlock) {
    val note = hostNote(b.note)
    Panel(b.name, b.kind, Modifier.width(360.dp)) {
        Pill(note.tone, note.label)
        Kv(listOf("app" to (b.app ?: "not found"), "config" to b.config.ifEmpty { listOf("none") }.joinToString("\n")))
        b.modules.forEach { (name, value) ->
            val s = moduleState(value)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted(name)
                Pill(s.tone, s.label)
                if (s.rest.isNotEmpty()) Txt(s.rest)
            }
        }
        b.skip?.let { Muted(it) }
    }
}

/** The Hosts page: the host blocks, then the junk card. */
@OptIn(ExperimentalLayoutApi::class)
val HostsPage =
    PageView { snap, app ->
        TextPage(app, snap, "hosts", "rtok agents list", "`rtok agents list`, one block per host variant, cached so the 2 s tick never waits") { text ->
            val view = parseHosts(text)
            when {
                view.probing -> {
                    Panel("hosts") { Muted("probing hosts: the first probe runs in the background and the tick never blocks on it") }
                }

                view.blocks.isEmpty() && view.other.isEmpty() -> {
                    Panel("hosts") { Muted("No hosts reported") }
                }

                else -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { view.blocks.forEach { Host(it) } }
                    OtherLines(view.other)
                }
            }
        }
        JunkCard(snap.obj("junk"), app.junk)
    }

@Composable
private fun Service(s: ServiceRow) =
    Panel(s.name, "service", Modifier.width(320.dp)) {
        if (s.running) Pill("ok", "running") else Pill("muted", "stopped")
        Kv(listOf("pid" to (s.pid ?: "none"), "uptime" to humanSecs(s.uptimeSecs), "log" to (s.log ?: "Unknown")))
        Muted("CLI-only: ${serviceCommand(s)}")
    }

/** The Services page: read-only supervisor state and the OpenTelemetry exporter. */
@OptIn(ExperimentalLayoutApi::class)
val ServicesPage =
    PageView { snap, app ->
        TextPage(app, snap, "services", "rtok demon status", "supervisor state in ~/.rtok/demon") { text ->
            val view = parseServices(text)
            if (view.services.isEmpty()) {
                Panel("services") { Muted("No services reported") }
            } else {
                Muted("${view.services.count { it.running }} of ${view.services.size} running")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { view.services.forEach { Service(it) } }
            }
            Panel("OpenTelemetry export", "rtok otel status") {
                val o = view.otel
                if (o == null) {
                    Muted("otel status unavailable")
                } else {
                    Kv(listOf("endpoint" to (o.endpoint ?: "not set")))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Kpi("calls", fmt(o.callsMark?.toDouble()), "${fmt(o.callsPending?.toDouble())} pending")
                        Kpi("logs", fmt(o.logsMark?.toDouble()), "${fmt(o.logsPending?.toDouble())} pending")
                        Kpi("sessions", fmt(o.sessionsMark?.toDouble()), "watermark")
                    }
                    view.lastFlush?.let { Muted(it) }
                }
            }
            OtherLines(view.other)
        }
    }
