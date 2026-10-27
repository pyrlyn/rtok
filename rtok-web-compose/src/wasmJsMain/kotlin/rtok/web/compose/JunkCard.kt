// The `junk` card of the Hosts page in Compose: what `rtok agents junk list` totals and the "Clear safe junk" flow.
// The server plans and removes; the card shows the dry run and sends the delete only from the explicit confirm.
// Compose UI: @Composable functions are PascalCase and layout sizes are literal dp/sp values.
@file:Suppress("FunctionNaming", "MagicNumber")

package rtok.web.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import rtok.web.ui.Frame
import rtok.web.ui.JunkEvent
import rtok.web.ui.JunkPhase
import rtok.web.ui.JunkState
import rtok.web.ui.bytes
import rtok.web.ui.flag
import rtok.web.ui.groupPlan
import rtok.web.ui.itemCount
import rtok.web.ui.junkReduce
import rtok.web.ui.num
import rtok.web.ui.obj
import rtok.web.ui.objects
import rtok.web.ui.removable
import rtok.web.ui.str

/** Requests of this card count from here, apart from any other page's (same base as the kotlinx.html UI). */
private const val JUNK_REQ_BASE = 330_000

/** The card's state and the request it waits on (a `message` with that `req` is its failure). */
class JunkController(
    private val app: AppState,
) {
    var state by mutableStateOf(JunkState())
        private set
    private var pending: Int? = null
    private var nextReq = JUNK_REQ_BASE

    init {
        app.on("junkplan") { f -> settle(f) { JunkEvent.Planned(f.body.obj("plan") ?: JsonObject(emptyMap())) } }
        app.on("junkcleared") { f -> settle(f) { JunkEvent.Applied(f.body.obj("cleared") ?: JsonObject(emptyMap())) } }
        app.on("message") { f -> if (f.req != null && f.req == pending) settle(f) { JunkEvent.Failed(f.body.str("text") ?: "failed") } }
    }

    private fun settle(
        f: Frame.Reply,
        event: () -> JunkEvent,
    ) {
        if (pending != null && f.req != pending) return
        pending = null
        event(event())
    }

    fun event(e: JunkEvent) {
        state = junkReduce(state, e)
    }

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
        if (!app.send(text)) {
            pending = null
            event(JunkEvent.Failed("not connected"))
        }
    }

    fun plan() {
        event(JunkEvent.Plan)
        if (state.phase == JunkPhase.PLANNING) request("plan")
    }

    fun confirm() {
        val paths = removable(state.plan).mapNotNull { it.str("path") }
        event(JunkEvent.Applying)
        if (state.phase == JunkPhase.APPLYING) request("apply", paths)
    }
}

private fun b(n: Double?) = bytes(n ?: 0.0)

/** A text button; [enabled] false greys it out. */
@Composable
fun Action(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Box(
        Modifier
            .background(p.accent.copy(alpha = if (enabled) 0.18f else 0.06f), RoundedCornerShape(6.dp))
            .clickable(enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { Txt(label, color = if (enabled) p.accent else p.muted) }
}

/** The junk card. */
@Composable
fun JunkCard(
    card: JsonObject?,
    c: JunkController,
) = Panel("junk", "what `rtok agents junk clear` would remove; nothing is deleted before you confirm") {
    if (card == null) Muted("measuring folders...") else JunkTotals(card)
    JunkActions(c)
}

@Composable
private fun JunkTotals(card: JsonObject) {
    card.objects("agents").forEach { a ->
        Txt("${a.str("name") ?: ""}  ${b(a.num("total_bytes"))} in its folders", bold = true)
        a.objects("kinds").forEach { k ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted(k.str("kind") ?: "")
                Pill(if (k.str("class") == "safe") "ok" else "muted", k.str("class") ?: "")
                Muted("${itemCount(k.num("items")?.toInt() ?: 0)}, ${b(k.num("size_bytes"))}")
            }
        }
        Txt("Freed by clear: ${b(a.num("freed_default_bytes"))} · with --include review: ${b(a.num("freed_review_bytes"))}")
    }
    Txt(
        "total: ${b(card.num("total_bytes"))} in folders · Freed by clear: ${b(card.num("freed_default_bytes"))} · " +
            "with --include review: ${b(card.num("freed_review_bytes"))}",
    )
}

@Composable
private fun JunkActions(c: JunkController) {
    val s = c.state
    s.error?.let { Pill("fail", it) }
    val plan = s.plan
    val result = s.result
    when {
        s.phase == JunkPhase.DONE && result != null -> {
            val failed = result.objects("items").filter { it.flag("failed") }
            Pill(
                if (failed.isEmpty()) "ok" else "warn",
                "Freed ${b(result.num("freed_bytes"))} of ${b(result.num("planned_bytes"))} planned" +
                    if (failed.isNotEmpty()) "; ${failed.size} not removed" else "",
            )
            failed.forEach { Muted("${it.str("path")}: ${it.str("note") ?: ""}") }
            Action("Done") { c.event(JunkEvent.Reset) }
        }

        plan == null -> {
            Action(if (s.phase == JunkPhase.PLANNING) "Planning..." else "Clear safe junk", s.phase != JunkPhase.PLANNING) { c.plan() }
        }

        else -> {
            JunkPlan(plan)
            val n = removable(plan).size
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.phase == JunkPhase.CONFIRMING || s.phase == JunkPhase.APPLYING) {
                    val busy = s.phase == JunkPhase.APPLYING
                    Txt("Delete ${itemCount(n)} (${b(plan.num("planned_bytes"))})?")
                    Action(if (busy) "Removing..." else "Confirm", !busy) { c.confirm() }
                    Action("Cancel", !busy) { c.event(JunkEvent.Cancel) }
                } else {
                    Action("Clear ${itemCount(n)}", n > 0) { c.event(JunkEvent.Ask) }
                    Action("Cancel") { c.event(JunkEvent.Cancel) }
                }
            }
        }
    }
}

@Composable
private fun JunkPlan(plan: JsonObject) {
    val items = plan.objects("items")
    if (items.isEmpty()) {
        Muted("Nothing to clear.")
        return
    }
    groupPlan(items).forEach { g ->
        Txt("${g.agent} ${g.kind}: ${itemCount(g.items.size)}, ${bytes(g.bytes)} planned", bold = true)
        g.items.forEach { i ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted("${i.str("path")} · ${b(i.num("bytes"))} · ${i.str("reason") ?: ""}")
                if (!i.flag("planned")) Pill("warn", "kept: ${i.str("note") ?: ""}")
            }
        }
    }
    Txt("Dry run: ${b(plan.num("planned_bytes"))} to free, nothing changed.")
}
