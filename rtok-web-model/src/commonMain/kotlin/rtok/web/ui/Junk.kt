// Port of web/src/pages/junkState.ts (T330.7): the state of the Hosts page's "Clear safe junk" button. The server owns
// the plan; the page only walks idle -> planning -> ready -> confirming -> applying -> done, and sends the delete
// message from `confirming` alone.
package rtok.web.ui

import kotlinx.serialization.json.JsonObject

enum class JunkPhase { IDLE, PLANNING, READY, CONFIRMING, APPLYING, DONE, ERROR }

/** junkState.ts `JunkState`; [plan] and [result] are the server's `Cleared` objects. */
data class JunkState(
    val phase: JunkPhase = JunkPhase.IDLE,
    val plan: JsonObject? = null,
    val result: JsonObject? = null,
    val error: String? = null,
)

/** junkState.ts `JunkAction`. */
sealed interface JunkEvent {
    data object Plan : JunkEvent

    data class Planned(
        val plan: JsonObject,
    ) : JunkEvent

    data object Ask : JunkEvent

    data object Cancel : JunkEvent

    data object Applying : JunkEvent

    data class Applied(
        val result: JsonObject,
    ) : JunkEvent

    data class Failed(
        val error: String,
    ) : JunkEvent

    data object Reset : JunkEvent
}

/** What the delete would take: planned items that carry the `clear` action. */
fun removable(plan: JsonObject?): List<JsonObject> = plan?.objects("items").orEmpty().filter { it.flag("planned") && it.str("action") == "clear" }

/** [next] when [s] is in one of [from], else [s] unchanged. */
private inline fun guard(
    s: JunkState,
    vararg from: JunkPhase,
    next: () -> JunkState,
): JunkState = if (s.phase in from) next() else s

/** junkState.ts `junkReducer`. */
fun junkReduce(
    s: JunkState,
    e: JunkEvent,
): JunkState =
    when (e) {
        JunkEvent.Plan -> guard(s, JunkPhase.IDLE, JunkPhase.DONE, JunkPhase.ERROR) { JunkState(JunkPhase.PLANNING) }

        is JunkEvent.Planned -> guard(s, JunkPhase.PLANNING) { s.copy(plan = e.plan, phase = JunkPhase.READY, error = null) }

        JunkEvent.Ask -> if (removable(s.plan).isEmpty()) s else guard(s, JunkPhase.READY) { s.copy(phase = JunkPhase.CONFIRMING) }

        JunkEvent.Cancel -> guard(s, JunkPhase.CONFIRMING, JunkPhase.READY) { s.copy(phase = JunkPhase.IDLE, plan = null) }

        JunkEvent.Applying -> guard(s, JunkPhase.CONFIRMING) { s.copy(phase = JunkPhase.APPLYING) }

        is JunkEvent.Applied -> guard(s, JunkPhase.APPLYING) { s.copy(phase = JunkPhase.DONE, result = e.result, plan = null, error = null) }

        // Back to the first button: a plan that failed to apply is stale, so it is asked again.
        is JunkEvent.Failed -> JunkState(JunkPhase.ERROR, error = e.error)

        JunkEvent.Reset -> JunkState()
    }

/** junkState.ts `KindGroup`. */
data class KindGroup(
    val agent: String,
    val kind: String,
    val items: List<JsonObject>,
    val bytes: Double,
)

/** The plan by agent and kind, in the order the server listed them; only planned items count toward [KindGroup.bytes]. */
fun groupPlan(items: List<JsonObject>): List<KindGroup> =
    items
        .groupBy { (it.str("agent") ?: "") to (it.str("kind") ?: "") }
        .map { (key, rows) -> KindGroup(key.first, key.second, rows, rows.filter { it.flag("planned") }.sumOf { it.num("bytes") ?: 0.0 }) }

/** "1 item" / "2 items". */
fun itemCount(n: Int): String = if (n == 1) "1 item" else "$n items"
