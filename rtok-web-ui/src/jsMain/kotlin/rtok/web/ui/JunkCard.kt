// Port of web/src/pages/Junk.tsx (T330.7): the `junk` card of the Hosts page, what `rtok agents junk list` totals, and
// the "Clear safe junk" button. The server plans and removes (`agents junk clear`'s own plan, re-checks and refusals);
// this card shows the dry run and sends the delete message only from the explicit confirm.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.code
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.js.onClickFunction
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.ul
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The card's state across renders, and the request it waits on (a `message` with that `req` is its failure). */
object JunkCard {
    private var state = JunkState()
    private var pending: Int? = null
    private var nextReq = JUNK_REQ_BASE
    private var app: App? = null

    fun install(a: App) {
        app = a
        Replies.on("junkplan") { f -> settle(f) { JunkEvent.Planned(f.body.obj("plan") ?: JsonObject(emptyMap())) } }
        Replies.on("junkcleared") { f -> settle(f) { JunkEvent.Applied(f.body.obj("cleared") ?: JsonObject(emptyMap())) } }
        Replies.on("message") { f -> if (f.req != null && f.req == pending) settle(f) { JunkEvent.Failed(f.body.str("text") ?: "failed") } }
    }

    private fun settle(
        f: Frame.Reply,
        event: () -> JunkEvent,
    ) {
        if (pending != null && f.req != pending) return
        pending = null
        dispatch(event())
    }

    private fun dispatch(e: JunkEvent) {
        state = junkReduce(state, e)
        app?.refresh()
    }

    /** Sends `{"req":n,"junk":{...}}`; a closed link fails the step at once. */
    private fun request(
        action: String,
        paths: List<String> = emptyList(),
    ) {
        val req = nextReq++
        pending = req
        val text =
            buildJsonObject {
                put("req", req)
                putJsonObject("junk") {
                    put("action", action)
                    if (action == "apply") put("paths", JsonArray(paths.map(::JsonPrimitive)))
                }
            }.toString()
        if (app?.send(text) != true) {
            pending = null
            dispatch(JunkEvent.Failed("not connected"))
        }
    }

    fun plan() {
        dispatch(JunkEvent.Plan)
        if (state.phase == JunkPhase.PLANNING) request("plan")
    }

    fun confirm() {
        val paths = removable(state.plan).mapNotNull { it.str("path") }
        dispatch(JunkEvent.Applying)
        if (state.phase == JunkPhase.APPLYING) request("apply", paths)
    }

    fun event(e: JunkEvent) = dispatch(e)

    val current: JunkState get() = state
}

/** Requests of this card count from here, apart from any other page's. */
private const val JUNK_REQ_BASE = 330_000

private fun b(n: Double?) = bytes(n ?: 0.0)

/** Junk.tsx `Junk`: the totals, then the button row for the current phase. */
fun FlowContent.junkCard(card: JsonObject?) {
    panel("junk", "what `rtok agents junk clear` would remove; nothing is deleted before you confirm") {
        if (card == null) p("muted") { +"measuring folders..." } else junkTotals(card)
        junkActions(JunkCard.current)
    }
}

private fun FlowContent.junkTotals(card: JsonObject) {
    div("junk") {
        card.objects("agents").forEach { a ->
            val name = a.str("name") ?: ""
            section {
                attributes["aria-label"] = "junk of $name"
                p {
                    +name
                    span("muted small") { +" ${b(a.num("total_bytes"))} in its folders" }
                }
                ul("muted small") {
                    a.objects("kinds").forEach { k ->
                        li {
                            span { +"${k.str("kind") ?: ""} " }
                            pill(if (k.str("class") == "safe") "ok" else "muted", k.str("class") ?: "")
                            span { +" ${itemCount(k.num("items")?.toInt() ?: 0)}, ${b(k.num("size_bytes"))}" }
                        }
                    }
                }
                p("small") {
                    +"Freed by "
                    code { +"clear" }
                    +": ${b(a.num("freed_default_bytes"))} · with "
                    code { +"--include review" }
                    +": ${b(a.num("freed_review_bytes"))}"
                }
            }
        }
        p {
            +"total: ${b(card.num("total_bytes"))} in folders · Freed by "
            code { +"clear" }
            +": ${b(card.num("freed_default_bytes"))} · with "
            code { +"--include review" }
            +": ${b(card.num("freed_review_bytes"))}"
        }
    }
}

private fun FlowContent.action(
    label: String,
    disabled: Boolean = false,
    onClick: () -> Unit,
) = button {
    this.disabled = disabled
    onClickFunction = { onClick() }
    +label
}

private fun FlowContent.junkActions(s: JunkState) {
    s.error?.let { p("pill fail") { +it } }
    val plan = s.plan
    val result = s.result
    when {
        s.phase == JunkPhase.DONE && result != null -> {
            junkDone(result)
        }

        plan == null -> {
            action(if (s.phase == JunkPhase.PLANNING) "Planning..." else "Clear safe junk", s.phase == JunkPhase.PLANNING) { JunkCard.plan() }
        }

        else -> {
            junkPlan(plan)
            val n = removable(plan).size
            div("row") {
                if (s.phase == JunkPhase.CONFIRMING || s.phase == JunkPhase.APPLYING) {
                    val busy = s.phase == JunkPhase.APPLYING
                    span { +"Delete ${itemCount(n)} (${b(plan.num("planned_bytes"))})? " }
                    action(if (busy) "Removing..." else "Confirm", busy) { JunkCard.confirm() }
                    action("Cancel", busy) { JunkCard.event(JunkEvent.Cancel) }
                } else {
                    action("Clear ${itemCount(n)}", n == 0) { JunkCard.event(JunkEvent.Ask) }
                    action("Cancel") { JunkCard.event(JunkEvent.Cancel) }
                }
            }
        }
    }
}

private fun FlowContent.junkPlan(plan: JsonObject) {
    val items = plan.objects("items")
    if (items.isEmpty()) {
        p("muted") {
            attributes["role"] = "status"
            +"Nothing to clear."
        }
        return
    }
    div {
        attributes["role"] = "region"
        attributes["aria-label"] = "junk plan"
        groupPlan(items).forEach { g ->
            details("small") {
                summary { +"${g.agent} ${g.kind}: ${itemCount(g.items.size)}, ${bytes(g.bytes)} planned" }
                ul("muted") {
                    g.items.forEach { i ->
                        li {
                            +"${i.str("path")} · ${b(i.num("bytes"))} · ${i.str("reason") ?: ""}"
                            if (!i.flag("planned")) span("pill warn") { +" kept: ${i.str("note") ?: ""}" }
                        }
                    }
                }
            }
        }
        p("small") { +"Dry run: ${b(plan.num("planned_bytes"))} to free, nothing changed." }
    }
}

private fun FlowContent.junkDone(result: JsonObject) {
    val failed = result.objects("items").filter { it.flag("failed") }
    val tone = if (failed.isEmpty()) "ok" else "warn"
    p("pill $tone") {
        +"Freed ${b(result.num("freed_bytes"))} of ${b(result.num("planned_bytes"))} planned"
        if (failed.isNotEmpty()) +"; ${failed.size} not removed"
    }
    failed.forEach { i -> p("small") { +"${i.str("path")}: ${i.str("note") ?: ""}" } }
    action("Done") { JunkCard.event(JunkEvent.Reset) }
}
