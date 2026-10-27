// Port of web/src/pages/fixState.ts (T331.12): the state of the Doctor page's "Fix selected" panel. The server owns the
// checklist and its defaults, so the page keeps only what the user changed since them: a request is always that delta.
package rtok.web.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class FixPhase { LOADING, READY, CONFIRMING, APPLYING, DONE, ERROR }

/** An entry as the server names it: the file and the key path inside it. */
data class FixRef(
    val source: String,
    val path: String,
) {
    fun json() = JsonObject(mapOf("source" to JsonPrimitive(source), "path" to JsonPrimitive(path)))
}

data class FixSelection(
    val keep: List<FixRef> = emptyList(),
    val toggled: List<FixRef> = emptyList(),
) {
    fun json() = JsonObject(mapOf("keep" to JsonArray(keep.map { it.json() }), "toggled" to JsonArray(toggled.map { it.json() })))
}

/** fixState.ts `FixState`; [plan] and [result] are the server's `Plan` and `Fixed`. [seq] drops a late plan. */
data class FixState(
    val selection: FixSelection = FixSelection(),
    val plan: JsonObject? = null,
    val phase: FixPhase = FixPhase.LOADING,
    val result: JsonObject? = null,
    val error: String? = null,
    val seq: Int = 0,
)

sealed interface FixEvent {
    data class Toggle(
        val ref: FixRef,
    ) : FixEvent

    data class Keep(
        val ref: FixRef,
    ) : FixEvent

    data class Planned(
        val seq: Int,
        val plan: JsonObject,
    ) : FixEvent

    data object Ask : FixEvent

    data object Cancel : FixEvent

    data object Applying : FixEvent

    data class Applied(
        val fixed: JsonObject,
    ) : FixEvent

    data class Failed(
        val error: String,
    ) : FixEvent

    data object Again : FixEvent
}

fun refOf(item: JsonObject) = FixRef(item.str("source").orEmpty(), item.str("path").orEmpty())

fun selectedCount(plan: JsonObject?): Int = plan?.objects("items").orEmpty().count { it.flag("selected") }

/** fixState.ts `fixReducer`. */
fun fixReduce(
    s: FixState,
    e: FixEvent,
): FixState =
    when (e) {
        is FixEvent.Toggle -> {
            reselect(s, s.selection.copy(toggled = flip(s.selection.toggled, e.ref)))
        }

        // The server recomputes the defaults after a swap, so the old toggles no longer say what they did.
        is FixEvent.Keep -> {
            reselect(s, FixSelection(s.selection.keep + e.ref, emptyList()))
        }

        is FixEvent.Planned -> {
            if (e.seq != s.seq) s else s.copy(plan = e.plan, phase = FixPhase.READY, error = null)
        }

        FixEvent.Ask -> {
            ask(s)
        }

        FixEvent.Cancel -> {
            if (s.phase == FixPhase.CONFIRMING) s.copy(phase = FixPhase.READY) else s
        }

        FixEvent.Applying -> {
            if (s.phase == FixPhase.CONFIRMING) s.copy(phase = FixPhase.APPLYING) else s
        }

        // A `replan` means nothing was written: the fresh preview replaces the old one and needs its own confirm.
        is FixEvent.Applied -> {
            applied(s, e.fixed)
        }

        is FixEvent.Failed -> {
            s.copy(phase = FixPhase.ERROR, error = e.error)
        }

        FixEvent.Again -> {
            FixState(seq = s.seq + 1)
        }
    }

private fun ask(s: FixState) = if (selectedCount(s.plan) > 0 && s.phase == FixPhase.READY) s.copy(phase = FixPhase.CONFIRMING) else s

private fun flip(
    t: List<FixRef>,
    r: FixRef,
) = if (r in t) t - r else t + r

private fun applied(
    s: FixState,
    fixed: JsonObject,
): FixState {
    val fresh = fixed.obj("replan")
    return if (fresh != null) {
        s.copy(plan = fresh, phase = FixPhase.READY, error = fixed.str("text")?.trim())
    } else {
        s.copy(phase = FixPhase.DONE, result = fixed, error = null)
    }
}

/** A change while confirming withdraws the confirmation: what was confirmed is no longer what the diff shows. */
private fun reselect(
    s: FixState,
    sel: FixSelection,
): FixState = if (s.phase == FixPhase.APPLYING || s.phase == FixPhase.DONE) s else s.copy(selection = sel, phase = FixPhase.LOADING, seq = s.seq + 1)

/** DoctorFix.tsx `diffTone`: added and removed lines, not the file headers. */
fun diffTone(line: String): String =
    when {
        line.startsWith("+") && !line.startsWith("+++") -> "ok"
        line.startsWith("-") && !line.startsWith("---") -> "fail"
        else -> "muted"
    }
