// Port of src/tui/doctor_fix.rs (T478, D27): the Doctor tab's `doctor --fix` checklist, the counterpart of the web
// page's "Fix selected" panel. It holds no rules of its own: the plan is DoctorWeb.planHere and the write
// DoctorWeb.applyHere, the pair the web page calls, so the selection semantics, the backup, the re-read and the
// refusals are the CLI's. The state is only the user's changes since the defaults (Selection), and every key that
// changes them asks for a new plan, so a write never acts on a stale list.
package rtok.tui

import rtok.config.Config
import rtok.doctor.DoctorWeb
import rtok.doctor.FixPlan
import rtok.doctor.Fixed
import rtok.doctor.Ref
import rtok.doctor.Selection
import tui.Color
import tui.Key
import tui.Line
import tui.Span
import tui.Style

/** The plan and the write, injected so tests run them without the host's real configs. */
class DoctorEngine(
    val plan: (Config, Selection) -> FixPlan,
    val apply: (Config, Selection, String) -> Fixed,
) {
    companion object {
        fun machine() = DoctorEngine(DoctorWeb::planHere, DoctorWeb::applyHere)
    }
}

private val OK_S = Style(fg = Color.Green, bold = true)
private val WARN_S = Style(fg = Color.Yellow)
private val MUTED_S = Style(dim = true)

@Suppress("TooManyFunctions")
class TuiDoctorFix(
    private val engine: DoctorEngine?,
) {
    var isOpen = false
        private set
    private var sel = Selection()
    var plan: FixPlan? = null
        private set
    var cursor = 0
        private set

    /** The apply key was pressed once; the next key decides. */
    var confirm = false
        private set
    private var done: Fixed? = null

    /** Why the last confirmation wrote nothing: the files no longer matched the plan shown. */
    private var stale: String? = null

    fun key(
        k: Key,
        cfg: Config,
    ): Keyed {
        val e = engine
        return when {
            e == null -> {
                Keyed.PASS
            }

            !isOpen -> {
                open(k, e, cfg)
            }

            done != null -> {
                Keyed.TAKEN.also { isOpen = false }
            }

            // Only an explicit `y` writes; every other key declines and is spent doing so.
            confirm -> {
                confirm = false
                if (k == Key.Char("y")) write(e, cfg) else Keyed.TAKEN
            }

            else -> {
                listKey(k, e, cfg)
            }
        }
    }

    /** `f` opens a fresh checklist: the last one's toggles describe a list that may be gone. */
    private fun open(
        k: Key,
        e: DoctorEngine,
        cfg: Config,
    ): Keyed {
        if (k != Key.Char("f")) return Keyed.PASS
        sel = Selection()
        plan = null
        cursor = 0
        confirm = false
        done = null
        isOpen = true
        replan(e, cfg)
        return Keyed.TAKEN
    }

    private fun listKey(
        k: Key,
        e: DoctorEngine,
        cfg: Config,
    ): Keyed {
        when (k) {
            Key.Esc -> isOpen = false
            Key.Up -> cursor = (cursor - 1).coerceAtLeast(0)
            Key.Down -> cursor = (cursor + 1).coerceAtMost(last())
            Key.Char(" ") -> toggle(e, cfg)
            Key.Char("k") -> keep(e, cfg)
            Key.Enter -> confirm = chosen() > 0
            else -> return Keyed.PASS
        }
        return Keyed.TAKEN
    }

    /** Writes what the plan on screen shows; when the files moved on, the fresh plan replaces it and needs its own `y`. */
    private fun write(
        e: DoctorEngine,
        cfg: Config,
    ): Keyed {
        val fixed = e.apply(cfg, sel, plan?.token.orEmpty())
        val fresh = fixed.replan
        if (fresh != null) {
            plan = fresh
            stale = fixed.text
            cursor = cursor.coerceAtMost(last())
            return Keyed.TAKEN
        }
        done = fixed
        plan = null
        return Keyed.WROTE
    }

    private fun replan(
        e: DoctorEngine,
        cfg: Config,
    ) {
        stale = null
        plan = e.plan(cfg, sel)
        cursor = cursor.coerceAtMost(last())
    }

    private fun last() = ((plan?.items?.size ?: 0) - 1).coerceAtLeast(0)

    fun chosen() = plan?.items.orEmpty().count { it.selected }

    private fun current(): Pair<Ref, Boolean>? = plan?.items?.getOrNull(cursor)?.let { Ref(it.source, it.path) to it.canKeep }

    private fun toggle(
        e: DoctorEngine,
        cfg: Config,
    ) {
        val (r, _) = current() ?: return
        sel = sel.copy(toggled = if (r in sel.toggled) sel.toggled - r else sel.toggled + r)
        replan(e, cfg)
    }

    private fun keep(
        e: DoctorEngine,
        cfg: Config,
    ) {
        val (r, can) = current() ?: return
        if (!can) return
        // The defaults move with the kept copy, so old toggles no longer mean what they did.
        sel = Selection(keep = sel.keep + r, toggled = emptyList())
        replan(e, cfg)
    }

    /** The panel that replaces the report while the checklist is open; null when closed. */
    fun lines(): List<Line>? {
        if (!isOpen) return null
        val out = mutableListOf<Line>()
        val d = done
        val p = plan
        if (d != null) {
            out +=
                if (d.code == 0) {
                    listOf(Span("doctor --fix: done (any key closes)", OK_S))
                } else {
                    listOf(Span("doctor --fix: some entries were skipped or failed", WARN_S.copy(bold = true)))
                }
            out += d.text.lines().map { listOf(Span(it)) }
        } else if (p != null) {
            stale?.let { out += listOf(Span(it.trimEnd(), WARN_S)) }
            out +=
                if (confirm) {
                    listOf(Span("Remove ${chosen()} selected entries? y = apply, any other key = cancel", WARN_S.copy(bold = true)))
                } else {
                    listOf(Span("doctor --fix checklist (dry run, nothing written)", Style(bold = true)))
                }
            if (p.items.isEmpty()) out += listOf(Span("nothing to remove", MUTED_S))
            p.items.forEachIndexed { n, i ->
                val text =
                    buildString {
                        append("[${if (i.selected) 'x' else ' '}] ${i.kind} ${i.label} in ${i.source}")
                        if (i.shared) append(" (project file)")
                        i.keptIn?.let { append(" (keeps $it)") }
                    }
                out += listOf(Span(text, if (n == cursor) Style(reverse = true) else Style.Default))
                out += listOf(Span("      ${i.detail}", MUTED_S))
            }
            out += p.refused.map { listOf(Span(it, MUTED_S)) }
            out += listOf(Span(""))
            out += p.diff.lines().map { listOf(Span(it, diffStyle(it))) }
        }
        return out
    }

    private fun diffStyle(l: String) =
        when (l.firstOrNull()) {
            '+' -> Style(fg = Color.Green)
            '-' -> Style(fg = Color.Red)
            else -> MUTED_S
        }
}
