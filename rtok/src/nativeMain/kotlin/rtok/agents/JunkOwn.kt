// Port of src/agents/junk.rs, the T182 half: rtok's own log siblings past `[log] files` and archives past
// `core.retain_calls_days` (`scan`, `run`, `to_table`), and removal_preview.rs `render`.
package rtok.agents

import rtok.Col
import rtok.config.Config
import rtok.config.HOSTS
import rtok.plugins.graph.Lsp
import rtok.store.Store
import rtok.store.archivesPendingRetention
import rtok.store.convertToIncrementalVacuum
import rtok.store.projects
import rtok.store.runRetention
import rtok.sys.ChangePreview
import rtok.sys.EntryKind
import rtok.sys.canonicalize
import rtok.sys.cwd
import rtok.sys.diskUsageUntil
import rtok.sys.fileName
import rtok.sys.isDir
import rtok.sys.join
import rtok.sys.listDirOrEmpty
import rtok.sys.parent
import rtok.sys.pathStartsWith
import rtok.sys.removeFile
import rtok.sys.symlinkMeta
import rtok.table
import rtok.worktree.notedTable
import kotlin.time.Duration
import kotlin.time.TimeSource

/** What `rtok agents junk clear` does under rtok's own home. */
object JunkOwn {
    /**
     * `rtok.log.<N>` siblings beyond `[log] files`: `rotate()` only drops the one generation past the current cap, so
     * lowering `files` leaves the older ones behind forever.
     */
    private fun staleLogSiblings(cfg: Config): List<String> {
        val dir = parent(cfg.log.path)
        val name = fileName(cfg.log.path)
        if (dir == null || name == null) return emptyList()
        return listDirOrEmpty(dir)
            .filter { n -> n.startsWith("$name.") && (n.substring(name.length + 1).toUIntOrNull() ?: 0u) > cfg.log.files }
            .map { join(dir, it) }
    }

    private fun outcome(
        kind: String,
        path: String,
        note: String,
    ) = JunkOutcome(kind, path, runCatching { symlinkMeta(path).len }.getOrDefault(0L), note = note)

    /** Everything `clear` would touch. Read-only; an unreadable path is skipped (fail open). */
    fun scan(cfg: Config): List<JunkOutcome> = scanWith(cfg, cfg.core.retainCallsDays)

    /** [scan] with the archive retention window [days] (`clear --older-than` may only widen it). */
    fun scanWith(
        cfg: Config,
        days: UInt,
    ): List<JunkOutcome> {
        val out = staleLogSiblings(cfg).map { outcome("log", it, "past `[log] files` = ${cfg.log.files}") }.toMutableList()
        val paths = runCatching { Store.open(cfg.core.dbPath).use { it.archivesPendingRetention(days) } }.getOrNull()
        paths?.forEach { out += outcome("archive", it, "past `core.retain_calls_days` = $days") }
        return out
    }

    /**
     * Dry run without [yes]. With it: removes the stray log siblings, then runs the store retention `proxy`/`mcp` run at
     * session start. Never touches `rtok.db` itself or an archive still referenced by a call.
     */
    fun run(
        cfg: Config,
        yes: Boolean,
    ): List<JunkOutcome> {
        val outcomes = scan(cfg)
        if (!yes) return outcomes
        for (o in outcomes.filter { it.kind == "log" }) {
            runCatching { removeFile(o.path) }
                .onSuccess { o.note = "removed" }
                .onFailure { e ->
                    o.failed = true
                    o.action = "keep"
                    o.note = "failed, kept: ${e.message}"
                }
        }
        val opened =
            runCatching {
                Store.open(cfg.core.dbPath).use { store ->
                    // Fire-and-forget like `run_retention` itself (T75/T182); then T352's one-time conversion.
                    runCatching { store.runRetention(cfg.core.retainCallsDays, cfg.core.retainHookBodiesDays) }
                    runCatching { store.convertToIncrementalVacuum() }
                }
            }
        if (opened.isSuccess) outcomes.filter { it.kind == "archive" }.forEach { it.note = "removed" }
        return outcomes
    }

    /** The deletion preview (removal_preview.rs `render`): one `- path  size  N files` line per path and the totals. */
    fun removalPreview(rows: List<Triple<String, Long, Long>>): String {
        val p = rtok.sys.Preview()
        rows.forEach { (path, bytes, files) -> p.remove(path, bytes, files) }
        return p.render(rtok.sys.PreviewMode.Stat)
    }

    fun toTable(
        outcomes: List<JunkOutcome>,
        yes: Boolean,
    ): String {
        val lines = listOf(listOf("action", "kind", "path", "bytes")) + outcomes.map { listOf(it.action, it.kind, it.path, ChangePreview.humanBytes(it.bytes)) }
        val cols = listOf(Col.left(0), Col.left(0), Col.left(0), Col.right(0))
        val out = StringBuilder(notedTable(cols, lines, outcomes.map { it.note }))
        val cleared = outcomes.filter { it.action == "clear" && !it.failed }
        val n = cleared.size
        val bytes = cleared.sumOf { it.bytes }
        if (!yes && n > 0) {
            out.append('\n')
            out.append(removalPreview(outcomes.filter { it.action == "clear" }.map { Triple(it.path, it.bytes, it.files) }))
        }
        out.append(
            when {
                !yes && n == 0 -> "\nnothing to clear\n"
                !yes -> "\ndry run: $n items, ${ChangePreview.humanBytes(bytes)} to free, nothing changed; rerun with --yes\n"
                else -> "\nfreed ${ChangePreview.humanBytes(bytes)} in $n items\n"
            },
        )
        return out.toString()
    }
}
