// Port of web/src/pages/graph3d/health.ts and alerts.ts: the health ring words, alert grouping and toasts.
package rtok.web.ui

private const val GOOD_FROM = 80.0
private const val WARN_FROM = 50.0
private const val PERCENT = 100.0

/** A score's level for `ProjectRow.scope_health` (cut-offs from health/score.rs). */
fun levelOf(score: Double): String =
    when {
        score >= GOOD_FROM -> "good"
        score >= WARN_FROM -> "warn"
        else -> "bad"
    }

/** The pill tone for a health level. */
fun healthTone(level: String): String =
    when (level) {
        "good" -> "ok"
        "warn" -> "warn"
        "bad", "missing" -> "fail"
        else -> "muted"
    }

/** A health score as the server sends it. */
data class Score(
    val level: String,
    val score: Double?,
    val components: Map<String, Double>,
    val reasons: List<Pair<String, String>>,
)

/** What the ring says: the score, or why there is none. */
fun healthLabel(s: Score): String =
    when (s.level) {
        "indexing", "missing" -> s.level
        else -> (s.score ?: 0.0).toLong().toString()
    }

private val COMPONENTS = listOf("freshness", "backend", "links")

/** `freshness 100% · backend 50% · links 0%`. */
fun componentsLine(s: Score): String = COMPONENTS.joinToString(" · ") { "$it ${kotlin.math.round((s.components[it] ?: 0.0) * PERCENT).toLong()}%" }

/** The breakdown as plain lines for a tooltip. */
fun healthLines(s: Score): List<String> = listOf("health ${healthLabel(s)}", componentsLine(s)) + s.reasons.map { (text, fix) -> "$text; fix: $fix" }

/** One graph alert. */
data class GraphAlert(
    val root: String,
    val project: String,
    val kind: String,
    val detail: String,
    val since: Double,
)

/** The wording of `rtok doctor` (health.rs). */
val ALERT_WORDS =
    linkedMapOf(
        "missing" to "missing",
        "unreachable" to "unreachable",
        "backend_down" to "backend down",
        "link_broken" to "link broken",
    )

/** Alerts of one kind; [since] is the earliest check. */
data class AlertGroup(
    val kind: String,
    val alerts: List<GraphAlert>,
    val since: Double,
    val names: String,
)

/** One group per kind, in [ALERT_WORDS] order. */
fun groupAlerts(alerts: List<GraphAlert>): List<AlertGroup> {
    val by = alerts.groupBy { it.kind }
    return ALERT_WORDS.keys.mapNotNull { kind ->
        by[kind]?.let { g -> AlertGroup(kind, g, g.minOf { it.since }, g.joinToString(", ") { it.project }) }
    }
}

/** `b, c missing since 14:02 (2 projects)`, or the cause for one project; [hm] formats epoch seconds. */
fun describeGroup(
    g: AlertGroup,
    hm: (Double) -> String,
): String {
    val tail = if (g.alerts.size == 1) g.alerts[0].detail else "${g.alerts.size} projects"
    return "${g.names} ${ALERT_WORDS[g.kind]} since ${hm(g.since)} ($tail)"
}

private fun GraphAlert.key() = "$root\u0000$kind"

/** Toasts for what a new snapshot raised (`fail`) and cleared (`ok`). */
fun alertToasts(
    prev: List<GraphAlert>,
    next: List<GraphAlert>,
    hm: (Double) -> String,
): List<Pair<String, String>> {
    val before = prev.map { it.key() }.toSet()
    val after = next.map { it.key() }.toSet()
    val raised = next.filter { it.key() !in before }
    val recovered = prev.filter { it.key() !in after }
    return groupAlerts(raised).map { "fail" to describeGroup(it, hm) } +
        groupAlerts(recovered).map { "ok" to "${it.names} ${ALERT_WORDS[it.kind]}: recovered" }
}
