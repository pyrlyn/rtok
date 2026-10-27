// The Doctor page in Compose: summary tiles, the checks, the report's panels, then the "Fix selected" panel.
// The server builds the fix list, defaults and diff (`/ws` `doctor` plan/apply); the panel only sends what the user
// changed and writes nothing until the explicit confirm, naming the preview it confirms by its token.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import rtok.web.ui.FixEvent
import rtok.web.ui.FixPhase
import rtok.web.ui.FixState
import rtok.web.ui.Frame
import rtok.web.ui.MODULES
import rtok.web.ui.chainHops
import rtok.web.ui.compact
import rtok.web.ui.diffTone
import rtok.web.ui.doctorChecks
import rtok.web.ui.doctorSummary
import rtok.web.ui.fixReduce
import rtok.web.ui.flag
import rtok.web.ui.fmt
import rtok.web.ui.hookBars
import rtok.web.ui.moduleCell
import rtok.web.ui.num
import rtok.web.ui.obj
import rtok.web.ui.objects
import rtok.web.ui.pct
import rtok.web.ui.refOf
import rtok.web.ui.selectedCount
import rtok.web.ui.str
import rtok.web.ui.strings
import kotlin.math.round

/** Requests of this panel count from here, apart from any other page's (same base as the kotlinx.html UI). */
private const val FIX_REQ_BASE = 331_000

/** The fix panel's state and the request it waits on. */
class FixController(
    private val app: AppState,
) {
    var state by mutableStateOf(FixState())
        private set
    private var pending: Int? = null
    private var sentSeq = -1
    private var nextReq = FIX_REQ_BASE

    init {
        app.on("doctorplan") { f -> settle(f) { FixEvent.Planned(state.seq, f.body.obj("plan") ?: JsonObject(emptyMap())) } }
        app.on("doctorfixed") { f -> settle(f) { FixEvent.Applied(f.body.obj("fixed") ?: JsonObject(emptyMap())) } }
        app.on("message") { f -> if (f.req != null && f.req == pending) settle(f) { FixEvent.Failed(f.body.str("text") ?: "failed") } }
    }

    private fun settle(
        f: Frame.Reply,
        event: () -> FixEvent,
    ) {
        if (f.req != pending) return
        pending = null
        dispatch(event())
    }

    fun dispatch(e: FixEvent) {
        state = fixReduce(state, e)
    }

    private fun request(
        action: String,
        confirmed: String? = null,
    ) {
        val req = nextReq++
        pending = req
        val text =
            buildJsonObject {
                put("req", req)
                putJsonObject("doctor") {
                    put("action", action)
                    put("selection", state.selection.json())
                    confirmed?.let { put("confirmed", JsonPrimitive(it)) }
                }
            }.toString()
        if (!app.send(text)) {
            pending = null
            state = fixReduce(state, FixEvent.Failed("not connected"))
        }
    }

    /** Asks for the plan of the current selection once per selection change. */
    fun ensurePlan() {
        if (state.phase == FixPhase.LOADING && sentSeq != state.seq) {
            sentSeq = state.seq
            request("plan")
        }
    }

    fun confirm() {
        dispatch(FixEvent.Applying)
        if (state.phase == FixPhase.APPLYING) request("apply", state.plan?.str("token").orEmpty())
    }
}

/** The Doctor page. */
val DoctorPage =
    PageView { snap, app ->
        val d = snap.obj("doctor")
        val checks = doctorChecks(d, snap.flag("doctor_pending"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            doctorSummary(checks).forEach { (s, n) -> Kpi(s.second, n.toString(), s.first) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(7f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel("checks", "derived from the doctor report; it has no verdict of its own") {
                    checks.forEach { c ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(c.st, c.st)
                            Txt(c.label, bold = true)
                        }
                        Muted(c.detail)
                    }
                }
                d?.let { McpAndInstructions(it) }
            }
            Column(Modifier.weight(5f), verticalArrangement = Arrangement.spacedBy(12.dp)) { d?.let { SidePanels(it) } }
        }
        FixPanel(app.fix)
    }

@Composable
private fun SidePanels(d: JsonObject) {
    Panel("hooks", "${fmt(d.num("hooks_total"))} total") {
        val bars = hookBars(d)
        if (bars.isEmpty()) Muted("No hooks installed.")
        bars.forEach { (name, n, share) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(name, Modifier.width(120.dp))
                Box(Modifier.weight(1f)) { ShareBar(share) }
                Txt(fmt(n))
            }
        }
    }
    Panel("proxy chains") {
        Chain("anthropic", d.str("proxy"))
        Chain("openai", d.str("proxy_openai"))
        if (d.flag("mcp_tool_search_disabled")) {
            val t = d.obj("mcp_tool_search")
            Pill("warn", "mcp_tool_search ${t?.str("state")} (${t?.str("source") ?: "default"})")
        }
    }
    Environment(d)
    Panel("agents × modules") {
        d.objects("agents").forEach { a ->
            Txt("${a.str("host") ?: ""}  ${a.str("kind") ?: ""}", bold = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODULES.forEach { m ->
                    val c = moduleCell(a, m)
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Muted(m)
                            Pill(c.tone, c.text)
                        }
                        c.note?.let { Muted(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpAndInstructions(d: JsonObject) {
    val mcp = d.objects("mcp")
    Panel("MCP servers", "${mcp.size} probed") {
        if (mcp.isEmpty()) Muted("No MCP server probed.")
        mcp.forEach { s ->
            Txt("${s.str("name") ?: ""}   ${fmt(s.num("tools"))} tools · ~${fmt(s.num("desc_tokens"))} desc tok", bold = true)
            Muted(s.str("cmd") ?: "")
        }
    }
    d.obj("instructions")?.let { i ->
        Panel("instructions") {
            i.objects("rows").forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(r.str("name") ?: "", bold = true)
                    if (r.flag("warn")) Pill("warn", "warn")
                    Muted("${fmt(r.num("tokens"))} tok")
                }
                Muted(r.str("path") ?: "")
            }
        }
    }
}

@Composable
private fun Chain(
    label: String,
    chain: String?,
) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Muted(label)
    val hops = chainHops(chain)
    if (hops.isEmpty()) Muted("not set")
    hops.forEachIndexed { i, h ->
        if (i > 0) Txt("→")
        Pill("info", h)
    }
}

@Composable
private fun FixPanel(c: FixController) {
    SideEffect { c.ensurePlan() }
    val s = c.state
    Panel("fix", "removes broken and duplicate entries; every file is backed up first") {
        val plan = s.plan
        val result = s.result
        when {
            s.phase == FixPhase.DONE && result != null -> {
                Txt(result.str("text") ?: "", color = LocalPalette.current.tone(if (result.num("code") == 0.0) "ok" else "warn"))
                Action("Check again") { c.dispatch(FixEvent.Again) }
            }

            plan == null && s.phase == FixPhase.ERROR -> {
                Pill("fail", s.error ?: "failed")
            }

            plan == null -> {
                Muted("Looking for entries to fix…")
            }

            else -> {
                FixPlan(c, s, plan)
            }
        }
    }
}

@Composable
private fun FixPlan(
    c: FixController,
    s: FixState,
    plan: JsonObject,
) {
    val busy = s.phase == FixPhase.APPLYING
    val p = LocalPalette.current
    val items = plan.objects("items")
    if (items.isEmpty()) Muted("Nothing to fix.")
    items.forEach { FixRow(c, it, busy) }
    plan.strings("refused").forEach { Pill("warn", it) }
    val diff = plan.str("diff").orEmpty()
    if (diff.isNotEmpty()) Column { diff.split("\n").forEach { line -> Txt(line.ifEmpty { " " }, color = p.tone(diffTone(line)).takeIf { diffTone(line) != "muted" } ?: p.muted, size = 12) } }
    s.error?.let { Pill("fail", it) }
    FixActions(c, s, selectedCount(plan))
}

@Composable
private fun Environment(d: JsonObject) {
    Panel("environment") {
        val rs = d.obj("read_share")
        val bytes = d.obj("skills")?.num("desc_bytes")
        Txt("BASH_MAX_OUTPUT_LENGTH ${d.str("bash_max_output_length") ?: "(unset)"}")
        Txt("autoCompactWindow ${d.str("auto_compact_window") ?: "(unset)"}")
        Txt(
            "read share " +
                if (rs == null) {
                    "-"
                } else {
                    "${pct(rs.num("share") ?: 0.0)}  grep ${compact(rs.num("grep_tokens"))} · glob ${compact(rs.num("glob_tokens"))} · read ${compact(rs.num("read_tokens"))}"
                },
        )
        Txt("skills desc bytes " + if (bytes == null) "-" else "${fmt(bytes)}  ≈ ${fmt(round(bytes / 4))} tok per request")
    }
}

@Composable
private fun FixRow(
    c: FixController,
    item: JsonObject,
    busy: Boolean,
) {
    val ref = refOf(item)
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.clickable(!busy) { c.dispatch(FixEvent.Toggle(ref)) }) { Txt(if (item.flag("selected")) "☑" else "☐", size = 16) }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(item.str("label").orEmpty())
                Pill("warn", item.str("kind").orEmpty())
                if (item.flag("shared")) Pill("info", "shared")
            }
            Muted("${item.str("agent")} · ${item.str("source")}")
            Muted(item.str("detail").orEmpty())
            item.str("kept_in")?.let { Muted("kept in $it") }
        }
        if (item.flag("can_keep")) Action("Keep this copy", !busy) { c.dispatch(FixEvent.Keep(ref)) }
    }
}

@Composable
private fun FixActions(
    c: FixController,
    s: FixState,
    n: Int,
) {
    val busy = s.phase == FixPhase.APPLYING
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.phase == FixPhase.CONFIRMING || busy) {
            Txt("Write $n ${if (n == 1) "entry" else "entries"}?")
            Action(if (busy) "Writing..." else "Confirm", !busy) { c.confirm() }
            Action("Cancel", !busy) { c.dispatch(FixEvent.Cancel) }
        } else {
            Action(if (s.phase == FixPhase.LOADING) "Planning..." else "Fix selected ($n)", n > 0 && s.phase == FixPhase.READY) { c.dispatch(FixEvent.Ask) }
        }
    }
}
