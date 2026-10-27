// Port of src/doctor/web.rs: the web doctor page's side of `rtok doctor --fix` (T331.12). It is stateless: every
// request carries the user's choices as changes to the defaults the terminal checklist starts from (the kept-copy swaps
// and the toggled entries), and the server rebuilds the findings, so a request never acts on a stale list. `plan`
// returns the items and the per-file diff and writes nothing; `apply` writes through `fixFound`, with its backup, re-read
// and refusals, and only the exact preview the user confirmed: `plan` returns a [FixPlan.token] of the selected entries
// and their diff, `apply` is given that token back and refuses when the rescan no longer yields it.
package rtok.doctor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rtok.config.Config
import rtok.store.hexSha256

/** An entry as the page names it: the file and the key path inside it. */
@Serializable
data class Ref(
    val source: String,
    val path: String,
)

/** What the user changed since the defaults. */
@Serializable
data class Selection(
    /** Entries made the kept copy of their duplicate. */
    val keep: List<Ref> = emptyList(),
    /** Entries whose selection is the opposite of their default. */
    val toggled: List<Ref> = emptyList(),
)

/** One line of the checklist. */
@Serializable
data class FixItem(
    val source: String,
    val path: String,
    val kind: String,
    val agent: String,
    /** What the entry runs or is called. */
    val label: String,
    val detail: String,
    /** The file that holds the copy kept instead, for a duplicate. */
    @SerialName("kept_in") val keptIn: String?,
    /** In a project's shared file: starts unselected. */
    val shared: Boolean,
    val selected: Boolean,
    /** Whether this copy may be made the kept one. */
    @SerialName("can_keep") val canKeep: Boolean,
)

@Serializable
data class FixPlan(
    val items: List<FixItem>,
    /** The diff of every file the selection would change. */
    val diff: String,
    /** Selected entries the engine will not remove, with why. */
    val refused: List<String>,
    /** Names this preview: `apply` writes only when the token it is given is the one the fresh rescan produces. */
    val token: String,
)

@Serializable
data class Fixed(
    val text: String,
    /** 1 when a selected entry was skipped or failed. */
    val code: Int,
    /** Set when nothing was written because the files no longer match the confirmed preview: the fresh preview. */
    val replan: FixPlan?,
)

internal object DoctorWeb {
    private fun Problem.isAt(r: Ref) = source == r.source && path == r.path

    /** The findings with the kept-copy swaps applied, and the entries left out of the write. */
    private fun prepare(
        cfg: Config,
        p: Probes,
        o: FixOpts,
        sel: Selection,
    ): Pair<MutableList<Problem>, Set<Key>> {
        val found = Fix.candidates(cfg, p, o.agent).toMutableList()
        for (r in sel.keep) {
            val i = found.indexOfFirst { it.isAt(r) }
            // A refused swap leaves the kept copy as it was; the page learns it from the plan.
            if (i >= 0) Checklist.keepCopy(found, Checklist.copyOf(found, i))
        }
        val off = Checklist.defaults(found, o.kinds, Checklist.sharedIn(p.env.cwd(), p.env.home()))
        for (t in sel.toggled) {
            val k = t.source to t.path
            if (!off.remove(k)) off += k
        }
        return found to off
    }

    /** The checklist and the diff of the selection; nothing is written. */
    fun plan(
        cfg: Config,
        p: Probes,
        w: Writer,
        o: FixOpts,
        sel: Selection,
    ): FixPlan {
        val (found, off) = prepare(cfg, p, o, sel)
        return preview(cfg, p, w, o, found, off)
    }

    /** The preview of [found] minus [off]: the one scan both [plan] and [apply] judge. */
    @Suppress("LongParameterList")
    private fun preview(
        cfg: Config,
        p: Probes,
        w: Writer,
        o: FixOpts,
        found: List<Problem>,
        off: Set<Key>,
    ): FixPlan {
        val shared = Checklist.sharedIn(p.env.cwd(), p.env.home())
        val items =
            Checklist.items(found, o.kinds).map { i ->
                val x = found[i]
                val keptIn = found.firstOrNull { it.group != null && it.kind == x.kind && it.group == x.group && it.keep }?.source
                val canKeep = Checklist.keepCopy(found.toMutableList(), Checklist.copyOf(found, i)).isSuccess
                FixItem(x.source, x.path, x.kind, x.agent, Fix.label(x), x.detail, keptIn, shared(x), Checklist.key(x) !in off, canKeep)
            }
        val report = Fix.fixFound(cfg, p, w, false, o, found, off)
        val diff = Fix.diffs(report)
        return FixPlan(items, diff, Fix.refusals(report), tokenOf(items, diff))
    }

    /**
     * A digest of what a confirmation covers: the selected entries by file and key path, and the diff they produce. An
     * entry that appeared, moved or changed after the preview changes it.
     */
    private fun tokenOf(
        items: List<FixItem>,
        diff: String,
    ): String {
        val text = StringBuilder()
        for (i in items.filter { it.selected }) {
            text
                .append(i.source)
                .append('\u0000')
                .append(i.path)
                .append('\u0000')
        }
        text.append('\u0000').append(diff)
        return hexSha256(text.toString().encodeToByteArray())
    }

    /**
     * Write the selection, if [confirmed] (the [FixPlan.token] the user saw) still names it. A rescan that yields another
     * preview writes nothing and returns that preview instead, so an entry that appeared since is never removed on the
     * strength of a confirmation that did not show it.
     */
    @Suppress("LongParameterList")
    fun apply(
        cfg: Config,
        p: Probes,
        w: Writer,
        o: FixOpts,
        sel: Selection,
        confirmed: String,
    ): Fixed {
        val (found, off) = prepare(cfg, p, o, sel)
        val now = preview(cfg, p, w, o, found.toList(), off)
        if (now.token != confirmed) {
            return Fixed(
                "The files changed since the preview, so nothing was written. Review the new preview and confirm again.\n",
                1,
                now,
            )
        }
        val report = Fix.fixFound(cfg, p, w, true, o, found, off)
        return Fixed(Fix.render(report, true), report.exitCode(), null)
    }

    /** What every UI asks of the engine: all kinds, all hosts, the configured backup depth (web page and TUI share it). */
    fun defaultOpts(cfg: Config) = FixOpts(cfg.setup.backupFiles.toInt(), null, Fix.KINDS)

    /** [plan] on this machine. */
    fun planHere(
        cfg: Config,
        sel: Selection,
    ) = plan(cfg, Probes.REAL, RealWriter, defaultOpts(cfg), sel)

    /** [apply] on this machine. */
    fun applyHere(
        cfg: Config,
        sel: Selection,
        confirmed: String,
    ) = apply(cfg, Probes.REAL, RealWriter, defaultOpts(cfg), sel, confirmed)
}
