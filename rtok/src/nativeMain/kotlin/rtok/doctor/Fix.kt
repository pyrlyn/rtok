// Port of src/doctor/fix.rs: `rtok doctor --fix` (T331.5, T331.6), the first doctor code that writes a host config. It
// removes only the entries the check classed `broken-hook`, and the extra copies of a `duplicate-hook` or
// `duplicate-mcp`, that it may fix, through the JSONC editor `agents install` uses (so every other byte of the file
// stays; a TOML server table goes through `:toml-aot`), after a backup and with an atomic swap. Without `--yes` it only
// prints the diff it would apply. A file that changed since the check, or whose edit would change anything but the
// selected entries, is skipped and reported, never written. The kept copy of a duplicate, a plugin's files, rtok's own
// entries and every hook that is not plainly broken are never removed.
package rtok.doctor

import rtok.StatPreview
import rtok.agents.isRtokBin
import rtok.config.Config
import rtok.mcp.config.Jsonc
import rtok.mcp.config.JsoncItems
import rtok.mcp.config.RTOK
import rtok.mcp.config.Seg
import rtok.sys.ChangePreview
import rtok.sys.IoException
import rtok.sys.Shlex

/** What happened to one file. */
sealed interface Outcome {
    /** Dry run: the diff is what `--yes` would write. */
    data object Planned : Outcome

    data object Written : Outcome

    /** Left as it was, with the reason (changed since the check, the edit check failed). */
    data class Skipped(
        val why: String,
    ) : Outcome

    /** The backup or the write failed; the file is as it was. */
    data class Failed(
        val why: String,
    ) : Outcome
}

data class FileFix(
    val source: String,
    val removed: List<Problem>,
    val diff: String = "",
    val backup: String? = null,
    val outcome: Outcome = Outcome.Planned,
)

data class FixReport(
    val files: MutableList<FileFix> = mutableListOf(),
    /** Selected entries this command will not remove, with why. */
    val refused: MutableList<Pair<Problem, String>> = mutableListOf(),
    /** Selected findings left after the writes (the re-check). */
    var left: Int = 0,
) {
    /** 1 when a selected hook was not fixed (skipped or failed), else 0. */
    fun exitCode(): Int = if (files.any { it.outcome is Outcome.Skipped || it.outcome is Outcome.Failed }) 1 else 0
}

/** What a run selects: backup generations kept per file, one host (`--agent`; null is every host) and the kinds. */
data class FixOpts(
    val keep: Int,
    val agent: String?,
    val kinds: List<String>,
)

@Suppress("TooManyFunctions")
internal object Fix {
    private const val NOT_LOCATED = "an entry could not be located"

    /** What `--fix` works on, by [Problem.kind]. */
    val KINDS = listOf("broken-hook", "duplicate-hook", "duplicate-mcp")

    /**
     * rtok's own hook entries are installed, updated and removed by `rtok agents`, which knows the plugin rules (T275);
     * a doctor removal would be undone by the next update.
     */
    fun isRtokOwn(command: String): Boolean =
        Shlex.split(command).orEmpty().any { w ->
            isRtokBin(w) || w.substringAfterLast('/').let { n -> if (n.lastIndexOf('.') > 0) n.substringBeforeLast('.') else n } == "rtok-hook"
        }

    /**
     * What install writes again: a hook that runs rtok, or the MCP entry named `rtok`. A copy of the server under
     * another name (`rtok-mcp`) is a hand-written extra and may go (T331.10).
     */
    fun isOwn(x: Problem): Boolean = if (x.kind == "duplicate-mcp") x.path.substringAfterLast('.') == RTOK.name else isRtokOwn(x.command)

    /** `(event, group, hook)` of a `hooks.<event>[g]` or `hooks.<event>[g].hooks[h]` key path. */
    @Suppress("ReturnCount")
    fun parsePath(path: String): Triple<String, Int, Int?>? {
        val rest = path.removePrefix("hooks.").takeIf { path.startsWith("hooks.") } ?: return null
        val open = rest.indexOf('[').takeIf { it >= 0 } ?: return null
        val event = rest.substring(0, open)
        val tail = rest.substring(open + 1)
        val close = tail.indexOf(']').takeIf { it >= 0 } ?: return null
        val g = tail.substring(0, close).toUIntOrNull()?.toInt() ?: return null
        val r = tail.substring(close + 1)
        if (r.isEmpty()) return Triple(event, g, null)
        if (!r.startsWith(".hooks[") || !r.endsWith("]")) return null
        val h =
            r
                .removePrefix(".hooks[")
                .removeSuffix("]")
                .toUIntOrNull()
                ?.toInt() ?: return null
        return Triple(event, g, h)
    }

    /** A finding `--fix` would remove: a broken hook, or a copy of a duplicate that is not the one kept. */
    fun wanted(
        x: Problem,
        kinds: List<String>,
    ) = x.kind in kinds && (x.kind == "broken-hook" || !x.keep)

    /** A finding that `--fix` removes unless the user's selection says otherwise. */
    fun removable(
        x: Problem,
        kinds: List<String>,
    ) = wanted(x, kinds) && x.fixable && !isOwn(x)

    /**
     * [raw] without the entries at [paths], each followed by the removal of the group and the event key it leaves
     * empty. Removal runs from the last entry to the first so earlier indices stay valid. Null when any path leads nowhere.
     */
    @Suppress("ReturnCount")
    internal fun removeEntries(
        raw: String,
        file: String,
        paths: List<String>,
    ): String? {
        val targets = paths.map { parsePath(it) ?: return null }.sortedWith(compareBy<Triple<String, Int, Int?>>({ it.first }, { it.second }, { it.third ?: -1 }).reversed())
        var body = raw
        for ((event, g, hook) in targets) {
            val group = listOf(Seg.Key("hooks"), Seg.Key(event), Seg.Index(g))
            val inner = group + Seg.Key("hooks")
            val target = if (hook != null) inner + Seg.Index(hook) else group
            val (next, found) = runCatching { JsoncItems.removeAt(body, file, target) }.getOrNull() ?: return null
            if (!found) return null
            body = next
            if (hook != null && JsoncItems.isEmptyAt(body, inner)) body = runCatching { JsoncItems.removeAt(body, file, group).first }.getOrNull() ?: return null
            val events = listOf(Seg.Key("hooks"), Seg.Key(event))
            if (JsoncItems.isEmptyAt(body, events)) body = runCatching { JsoncItems.removeAt(body, file, events).first }.getOrNull() ?: return null
        }
        return body
    }

    /** The `(event, matcher, command)` of every hook of [raw], in order. */
    internal fun hookList(raw: String): List<Triple<String, String?, String>>? = Jsonc.parseOrNull(raw)?.let { doc -> Hooks.entries(doc).map { Triple(it.event, it.matcher, it.command) } }

    /**
     * The edit of [raw] that drops [removed], or the reason it cannot be trusted: the new text must still parse and hold
     * exactly the old hooks and servers minus the removed ones.
     */
    @Suppress("ReturnCount")
    private fun edit(
        raw: String,
        file: String,
        removed: List<Problem>,
        locs: List<Loc>,
    ): Result<String> {
        val (mcp, hooks) = removed.partition { it.kind == "duplicate-mcp" }
        val body = if (hooks.isEmpty()) raw else editHooks(raw, file, hooks).getOrElse { return Result.failure(it) }
        if (mcp.isEmpty()) return Result.success(body)
        val at = mcp.map { p -> locs.firstOrNull { it.source == p.source && it.path == p.path } ?: return Result.failure(IllegalStateException(NOT_LOCATED)) }
        return McpFix.remove(body, file, at)
    }

    @Suppress("ReturnCount")
    private fun editHooks(
        raw: String,
        file: String,
        removed: List<Problem>,
    ): Result<String> {
        val body = removeEntries(raw, file, removed.map { it.path }) ?: return Result.failure(IllegalStateException(NOT_LOCATED))
        val expect =
            hookList(raw)?.let { all ->
                val gone = removed.map { Triple(it.event, it.matcher, it.command) }.toMutableList()
                all.filter { h -> !gone.remove(h) }
            }
        val after = hookList(body)
        if (after == null || after != expect) return Result.failure(IllegalStateException("the edit would change more than the selected hooks"))
        return Result.success(body)
    }

    /** The hook and MCP findings of this machine, for one host or all. */
    private fun findings(
        cfg: Config,
        p: Probes,
        agent: String?,
    ): List<Problem> {
        val (all, plugins) = Hooks.checkWithPlugins(cfg, p)
        return (all + McpDupes.check(cfg, p, plugins)).filter { agent == null || it.agent == agent }
    }

    /** Remove every fixable broken hook. [apply] false only plans. [keep] is how many backup generations to keep. */
    fun fixBroken(
        cfg: Config,
        p: Probes,
        w: Writer,
        apply: Boolean,
        keep: Int,
    ) = fixFor(cfg, p, w, apply, keep, null, KINDS.take(1))

    /** [fixBroken] for the [kinds] selected and one host's entries (`--agent`); null is every host. */
    @Suppress("LongParameterList")
    fun fixFor(
        cfg: Config,
        p: Probes,
        w: Writer,
        apply: Boolean,
        keep: Int,
        agent: String?,
        kinds: List<String>,
    ) = fixFound(cfg, p, w, apply, FixOpts(keep, agent, kinds), findings(cfg, p, agent), emptySet())

    /** The findings `--fix` starts from, for the checklist to show and change. */
    fun candidates(
        cfg: Config,
        p: Probes,
        agent: String?,
    ) = findings(cfg, p, agent)

    /** [fixFor] over [found], without the entries whose `(source, path)` is in [skip] (deselected in the checklist). */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun fixFound(
        cfg: Config,
        p: Probes,
        w: Writer,
        apply: Boolean,
        o: FixOpts,
        found: List<Problem>,
        skip: Set<Key>,
    ): FixReport {
        val kinds = o.kinds
        val report = FixReport()
        val byFile = LinkedHashMap<String, MutableList<Problem>>()
        val at = Checklist::key
        // A copy that goes as broken must not be the one kept: the extras then stay, so a hook is never removed from
        // every file at once because it was a duplicate.
        val broken = found.filter { it.kind == "broken-hook" && it.fixable && it.kind in kinds && at(it) !in skip }.map(at).toSet()
        val lost = found.filter { it.kind == "duplicate-hook" && it.keep && at(it) in broken }.mapNotNull { it.group }.toSet()
        val taken = HashSet<Key>()
        for (problem in found.filter { wanted(it, kinds) && at(it) !in skip }) {
            if (problem.kind == "duplicate-hook" && problem.group in lost) continue
            when {
                !problem.fixable -> {
                    val why = if (problem.source.endsWith(".toml") && problem.kind != "duplicate-mcp") "TOML hook files are not edited yet" else "not a file of yours to edit"
                    report.refused += problem to why
                }

                isOwn(problem) -> {
                    report.refused += problem to "rtok's own entry: `rtok agents install` rewrites it"
                }

                !taken.add(at(problem)) -> {
                    Unit
                }

                // Broken and an extra copy at once: one removal.
                else -> {
                    byFile.getOrPut(problem.source) { mutableListOf() } += problem
                }
            }
        }
        val locs = if ("duplicate-mcp" in kinds) McpDupes.locations(cfg, p) else emptyList()
        for ((source, problems) in byFile) report.files += fixFile(p, w, source, problems, apply, o.keep, locs)
        if (apply) report.left = findings(cfg, p, o.agent).count { wanted(it, kinds) }
        return report
    }

    @Suppress("LongParameterList", "ReturnCount")
    private fun fixFile(
        p: Probes,
        w: Writer,
        path: String,
        removed: List<Problem>,
        apply: Boolean,
        keep: Int,
        locs: List<Loc>,
    ): FileFix {
        val fix = FileFix(path, removed)
        val raw =
            try {
                p.fs.read(path)
            } catch (e: ReadError) {
                return fix.copy(outcome = Outcome.Skipped("cannot read $path: ${e.message}"))
            }
        val body = edit(raw, path, removed, locs).getOrElse { return fix.copy(outcome = Outcome.Skipped(it.message.orEmpty())) }
        val planned = fix.copy(diff = ChangePreview.unifiedDiff(path, raw, body))
        if (!apply) return planned
        // The window between the check and this write is short but real: an agent or an editor may have saved the
        // file, and its change must not be overwritten with a copy of the old text.
        if (runCatching { p.fs.read(path) }.getOrNull() != raw) return planned.copy(outcome = Outcome.Skipped("changed since the check"))
        val backup =
            try {
                w.backup(path, keep)
            } catch (e: IoException) {
                return planned.copy(outcome = Outcome.Failed("backup failed: ${e.message}"))
            }
        return try {
            w.write(path, body)
            planned.copy(backup = backup, outcome = Outcome.Written)
        } catch (e: IoException) {
            planned.copy(backup = backup, outcome = Outcome.Failed("write failed: ${e.message}"))
        }
    }

    /** One line per selected entry the engine will not remove. */
    fun refusals(r: FixReport) = r.refused.map { (p, why) -> "not removed: ${label(p)} `${p.command}` in ${p.source}: $why" }

    /** The diffs of the files a planned run would change, and why a file is skipped. */
    fun diffs(r: FixReport) =
        r.files.joinToString("") { f ->
            when (val o = f.outcome) {
                is Outcome.Skipped -> "${f.source}: skipped: ${o.why}\n"
                else -> f.diff
            }
        }

    /** What a finding is called in the text: a hook by event and matcher, a server by its path. */
    fun label(p: Problem) =
        when {
            p.kind == "duplicate-mcp" -> "mcp ${p.path}"
            p.matcher != null -> "${p.event}[${p.matcher}]"
            else -> p.event
        }

    /** The text `rtok doctor --fix` prints. */
    @Suppress("CyclomaticComplexMethod")
    fun render(
        r: FixReport,
        apply: Boolean,
    ): String {
        if (r.files.isEmpty() && r.refused.isEmpty()) return "nothing to remove\n"
        val out = StringBuilder()
        if (!apply) out.append("dry run: nothing is written. Add --yes to remove the entries below.\n")
        val planned = mutableListOf<Pair<String, String>>()
        for (f in r.files) {
            out.append("${f.source}\n")
            val verb = if (apply) "remove" else "would remove"
            for (p in f.removed) out.append("  $verb ${label(p)} `${p.command}`: ${p.detail}\n")
            f.backup?.let { out.append("  backup $it\n") }
            when (val o = f.outcome) {
                Outcome.Planned -> {
                    planned += f.source to f.diff
                    if (!StatPreview.on()) out.append(f.diff)
                }

                Outcome.Written -> {
                    out.append("  written\n")
                }

                is Outcome.Skipped -> {
                    out.append("  skipped: ${o.why}\n")
                }

                is Outcome.Failed -> {
                    out.append("  failed: ${o.why}\n")
                }
            }
        }
        if (planned.isNotEmpty()) out.append(StatPreview.show(planned, "")).append('\n')
        for (line in refusals(r)) out.append("$line\n")
        if (apply) {
            val done = r.files.filter { it.outcome == Outcome.Written }.sumOf { it.removed.size }
            out.append("$done ${if (done == 1) "entry" else "entries"} removed, ${r.left} left\n")
        }
        return out.toString()
    }

    /** `--fix` against this machine: the report text and the exit code. With a [prompt] and no `--yes` the user picks. */
    fun run(
        cfg: Config,
        apply: Boolean,
        agent: String?,
        kinds: List<String>,
        prompt: Prompt?,
    ): Pair<String, Int> {
        val o = FixOpts(cfg.setup.backupFiles.toInt(), agent, kinds)
        val p = Probes.REAL
        if (prompt != null && !apply) return interactive(cfg, p, RealWriter, o, prompt)
        val r = fixFor(cfg, p, RealWriter, apply, o.keep, agent, kinds)
        return render(r, apply) to r.exitCode()
    }

    /** The checklist, then the write of what the user confirmed. Cancelling writes nothing. */
    fun interactive(
        cfg: Config,
        p: Probes,
        w: Writer,
        o: FixOpts,
        prompt: Prompt,
    ): Pair<String, Int> {
        val found = candidates(cfg, p, o.agent).toMutableList()
        val shared = Checklist.sharedIn(p.env.cwd(), p.env.home())
        if (found.none { removable(it, o.kinds) }) return render(fixFound(cfg, p, w, false, o, found, emptySet()), false) to 0
        val preview = { all: List<Problem>, skip: Set<Key> -> diffs(fixFound(cfg, p, w, false, o, all.toList(), skip)) }
        return when (val c = Checklist.run(found, o.kinds, shared, preview, prompt)) {
            Choice.Cancel -> "cancelled: nothing was written\n" to 0
            is Choice.Apply -> fixFound(cfg, p, w, true, o, found, c.off).let { render(it, true) to it.exitCode() }
        }
    }
}
