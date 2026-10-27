// Port of web/src/pages/DoctorFix.tsx and the Doctor page's shell (T331.12): the "Fix selected" checklist `rtok doctor
// --fix` shows in a terminal. The server builds the list, the defaults and the diff (DoctorWeb's plan and apply over
// `/ws` `doctor`); this panel only sends what the user changed and writes nothing until the explicit confirm. The
// report above it is Doctor.tsx: summary tiles, the checks and the Report's panels.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.div
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Requests of this panel count from here, apart from any other page's. */
private const val FIX_REQ_BASE = 331_000

object DoctorFixCard {
    private var state = FixState()
    private var pending: Int? = null

    /** The plan request for this selection was sent; a re-render does not send it again. */
    private var sentSeq = -1
    private var nextReq = FIX_REQ_BASE
    private var app: App? = null

    fun install(a: App) {
        app = a
        Replies.on("doctorplan") { f -> settle(f) { FixEvent.Planned(state.seq, f.body.obj("plan") ?: JsonObject(emptyMap())) } }
        Replies.on("doctorfixed") { f -> settle(f) { FixEvent.Applied(f.body.obj("fixed") ?: JsonObject(emptyMap())) } }
        Replies.on("message") { f -> if (f.req != null && f.req == pending) settle(f) { FixEvent.Failed(f.body.str("text") ?: "failed") } }
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
        app?.refresh()
    }

    /** Sends `{"req":n,"doctor":{...}}`; a closed link fails the step at once. A newer request supersedes an older one. */
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
        if (app?.send(text) != true) {
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

    /** The token names the preview the user is looking at: the server writes only that one. */
    fun confirm() {
        dispatch(FixEvent.Applying)
        if (state.phase == FixPhase.APPLYING) request("apply", state.plan?.str("token").orEmpty())
    }

    val current: FixState get() = state
}

private fun FlowContent.act(
    label: String,
    disabled: Boolean = false,
    onClick: () -> Unit,
) = button {
    this.disabled = disabled
    onClickFunction = { onClick() }
    +label
}

/** DoctorFix.tsx `DoctorFix`. */
fun FlowContent.doctorFix() {
    DoctorFixCard.ensurePlan()
    val s = DoctorFixCard.current
    panel("fix", "removes broken and duplicate entries; every file is backed up first") { fixBody(s) }
}

private fun FlowContent.fixBody(s: FixState) {
    val plan = s.plan
    val result = s.result
    when {
        s.phase == FixPhase.DONE && result != null -> {
            p("pill ${if (result.num("code") == 0.0) "ok" else "warn"}") { pre("small") { +(result.str("text") ?: "") } }
            act("Check again") { DoctorFixCard.dispatch(FixEvent.Again) }
        }

        plan == null && s.phase == FixPhase.ERROR -> {
            p("pill fail") { +(s.error ?: "failed") }
        }

        plan == null -> {
            p("muted") {
                attributes["role"] = "status"
                +"Looking for entries to fix…"
            }
        }

        else -> {
            fixPlan(s, plan)
        }
    }
}

private fun FlowContent.fixPlan(
    s: FixState,
    plan: JsonObject,
) {
    val busy = s.phase == FixPhase.APPLYING
    val items = plan.objects("items")
    if (items.isEmpty()) p("muted") { +"Nothing to fix." }
    ul("fix-list") { items.forEach { fixRow(it, busy) } }
    plan.strings("refused").forEach { p("small pill warn") { +it } }
    val diff = plan.str("diff").orEmpty()
    if (diff.isNotEmpty()) {
        pre("small diff") {
            attributes["aria-label"] = "diff"
            diff.split("\n").forEach { line -> div(diffTone(line)) { +line.ifEmpty { " " } } }
        }
    }
    s.error?.let { p("pill fail") { +it } }
    val n = selectedCount(plan)
    div("row") {
        if (s.phase == FixPhase.CONFIRMING || busy) {
            span { +"Write $n ${if (n == 1) "entry" else "entries"}? " }
            act(if (busy) "Writing..." else "Confirm", busy) { DoctorFixCard.confirm() }
            act("Cancel", busy) { DoctorFixCard.dispatch(FixEvent.Cancel) }
        } else {
            // A change asks for a new diff; the button waits because what it would confirm is not known until then.
            act(if (s.phase == FixPhase.LOADING) "Planning..." else "Fix selected ($n)", n == 0 || s.phase != FixPhase.READY) {
                DoctorFixCard.dispatch(FixEvent.Ask)
            }
        }
    }
}

private fun kotlinx.html.UL.fixRow(
    item: JsonObject,
    busy: Boolean,
) = li("row") {
    val ref = refOf(item)
    checkBoxInput {
        attributes["aria-label"] = "${item.str("label")} in ${item.str("source")}"
        checked = item.flag("selected")
        disabled = busy
        onChangeFunction = { DoctorFixCard.dispatch(FixEvent.Toggle(ref)) }
    }
    div {
        p {
            span { +"${item.str("label")} " }
            pill("warn", item.str("kind").orEmpty())
            if (item.flag("shared")) pill("info", "shared")
        }
        p("muted small") { +"${item.str("agent")} · ${item.str("source")}" }
        p("muted small") { +item.str("detail").orEmpty() }
        item.str("kept_in")?.let { p("muted small") { +"kept in $it" } }
    }
    if (item.flag("can_keep")) act("Keep this copy", busy) { DoctorFixCard.dispatch(FixEvent.Keep(ref)) }
}

/** Doctor.tsx `DoctorBody`: the summary tiles, the checks, the report's panels once it landed, then the fix panel. */
val DoctorPage =
    PageView { out, snap, _ ->
        val d = snap.obj("doctor")
        val checks = doctorChecks(d, snap.flag("doctor_pending"))
        out.div("grid kpis") { doctorSummary(checks).forEach { (s, n) -> kpi(s.second, n.toString(), s.first) } }
        out.div("grid wide") {
            panel("checks", "derived from the doctor report; it has no verdict of its own", cls = "span7") {
                ul("fix-list") {
                    checks.forEach { c ->
                        li("check") {
                            pill(toneOf(c.st), c.st)
                            b { +" ${c.label}" }
                            div("muted small") { +c.detail }
                        }
                    }
                }
            }
            d?.let { reportPanels(it) }
        }
        out.doctorFix()
    }

private fun FlowContent.reportPanels(d: JsonObject) {
    panel("hooks", "${fmt(d.num("hooks_total"))} total", cls = "span5") {
        val bars = hookBars(d)
        if (bars.isEmpty()) p("muted") { +"No hooks installed." }
        bars.forEach { (name, n, share) ->
            div("bar-row small") {
                span("muted") { +name }
                div("bar") { div("fill") { attributes["style"] = "width:${share * PERCENT}%" } }
                span { +fmt(n) }
            }
        }
    }
    panel("proxy chains", cls = "span5") {
        chain("anthropic", d.str("proxy"))
        chain("openai", d.str("proxy_openai"))
        if (d.flag("mcp_tool_search_disabled")) {
            val t = d.obj("mcp_tool_search")
            p("small pill warn") { +"mcp_tool_search ${t?.str("state")} (${t?.str("source") ?: "default"})" }
        }
    }
    val mcp = d.objects("mcp")
    panel("MCP servers", "${mcp.size} probed", cls = "span7") {
        if (mcp.isEmpty()) p("muted") { +"No MCP server probed." }
        ul("fix-list") {
            mcp.forEach { s ->
                li {
                    b { +(s.str("name") ?: "") }
                    span("muted small") { +"  ${fmt(s.num("tools"))} tools · ~${fmt(s.num("desc_tokens"))} desc tok" }
                    div("muted small") { +(s.str("cmd") ?: "") }
                }
            }
        }
    }
    environment(d)
    d.obj("instructions")?.let { i ->
        panel("instructions", cls = "span7") {
            ul("fix-list") {
                i.objects("rows").forEach { r ->
                    li {
                        b { +(r.str("name") ?: "") }
                        if (r.flag("warn")) pill("warn", "warn")
                        span("muted small") { +"  ${fmt(r.num("tokens"))} tok" }
                        div("muted small") { +(r.str("path") ?: "") }
                    }
                }
            }
        }
    }
    panel("agents × modules", cls = "span5") { agents(d.objects("agents")) }
}

private const val PERCENT = 100
private const val BYTES_PER_TOKEN = 4

private fun FlowContent.environment(d: JsonObject) =
    panel("environment", cls = "span5") {
        val rs = d.obj("read_share")
        val skills = d.obj("skills")
        kv(
            listOf(
                "BASH_MAX_OUTPUT_LENGTH" to { +(d.str("bash_max_output_length") ?: "(unset)") },
                "autoCompactWindow" to { +(d.str("auto_compact_window") ?: "(unset)") },
                "read share" to {
                    if (rs == null) {
                        +"-"
                    } else {
                        +"${pct(rs.num("share") ?: 0.0)} "
                        span("muted") { +"grep ${compact(rs.num("grep_tokens"))} · glob ${compact(rs.num("glob_tokens"))} · read ${compact(rs.num("read_tokens"))}" }
                    }
                },
                "skills desc bytes" to {
                    val bytes = skills?.num("desc_bytes")
                    if (bytes == null) {
                        +"-"
                    } else {
                        +"${fmt(bytes)} "
                        span("muted") { +"≈ ${fmt(kotlin.math.round(bytes / BYTES_PER_TOKEN))} tok per request" }
                    }
                },
            ),
        )
    }

private fun FlowContent.chain(
    label: String,
    chain: String?,
) = div("row small") {
    span("muted") { +"$label " }
    val hops = chainHops(chain)
    if (hops.isEmpty()) span("muted") { +"not set" }
    hops.forEachIndexed { i, h ->
        if (i > 0) span { +" → " }
        pill("info", h)
    }
}

private fun FlowContent.agents(agents: List<JsonObject>) =
    ul("fix-list") {
        agents.forEach { a ->
            li {
                p {
                    b { +(a.str("host") ?: "") }
                    span("muted") { +" ${a.str("kind") ?: ""}" }
                }
                div("grid modules") {
                    MODULES.forEach { m ->
                        val c = moduleCell(a, m)
                        div("card small") {
                            c.note?.let { attributes["title"] = it }
                            span("muted") { +"$m " }
                            pill(c.tone, c.text)
                            c.note?.let { div("muted") { +it } }
                        }
                    }
                }
            }
        }
    }
