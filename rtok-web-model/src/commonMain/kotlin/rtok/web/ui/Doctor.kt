// The pure part of web/src/pages/Doctor.tsx (T15.6): the summary tiles over the checks, the proxy chain's hops, the
// agents × modules cells. The page renders these from the snapshot's `doctor` Report.
package rtok.web.ui

import kotlinx.serialization.json.JsonObject

/** Doctor.tsx `SUMMARY`: state, tile label, tile tone. */
val DOCTOR_SUMMARY = listOf(Triple("pass", "pass", "ok"), Triple("warn", "warn", "warn"), Triple("fail", "fail", "fail"), Triple("skip", "not set", "default"))

/** model.ts `MODULES`: the columns of the agents × modules grid. */
val MODULES = listOf("hooks", "mcp", "proxy", "plugin")

/** The tile counts in [DOCTOR_SUMMARY] order. */
fun doctorSummary(checks: List<Check>): List<Pair<Triple<String, String, String>, Int>> = DOCTOR_SUMMARY.map { s -> s to checks.count { it.st == s.first } }

/** Doctor.tsx `Chain`: the hops of `a → b → c`; empty when unset. */
fun chainHops(chain: String?): List<String> =
    chain
        ?.takeIf { it.isNotEmpty() }
        ?.split("→")
        ?.map { it.trim() }
        .orEmpty()

/** One agents × modules cell: the pill text, its tone, and the note shown under a module that is not on. */
data class ModuleCell(
    val text: String,
    val tone: String,
    val note: String?,
)

/** Doctor.tsx `moduleTone`; a module the agent does not list is `-`. */
fun moduleCell(
    agent: JsonObject,
    name: String,
): ModuleCell {
    val m = agent.objects("modules").firstOrNull { it.str("name") == name } ?: return ModuleCell("-", "muted", null)
    val note = m.str("note")?.takeIf { it.isNotEmpty() }
    return when (m.str("state")) {
        "installed" -> ModuleCell("on", "ok", null)
        "not_installed" -> ModuleCell("off", "warn", note)
        else -> ModuleCell("n/a", "muted", note)
    }
}

/** The hooks bars: event, count, width share of the busiest event. */
fun hookBars(d: JsonObject): List<Triple<String, Double, Double>> {
    val events =
        d
            .obj("hooks_by_event")
            ?.keys
            ?.map { it to (d.obj("hooks_by_event")?.num(it) ?: 0.0) }
            .orEmpty()
    val top = maxOf(1.0, events.maxOfOrNull { it.second } ?: 0.0)
    return events.map { (k, n) -> Triple(k, n, n / top) }
}
