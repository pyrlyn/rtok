// Port of src/agents/junk_review.rs: the T330.5.1 kinds on the `cache` item model: `logs` (the §22 log folders,
// review), `backups` (rtok's own `.backup_configs` generations past `setup.backup_files`, review), `snapshots` (Gemini
// CLI's restore points, never), the `[agents.junk] extra` paths and the `exclude` globs. Nothing here deletes:
// `junk_clear` plans, re-checks and removes (T330.4).
package rtok.agents

import rtok.agent.sdk.BACKUP_DIRS
import rtok.agent.sdk.staleBackups
import rtok.config.AgentsJunk
import rtok.hook.expandWith
import rtok.measure.usage.stem
import rtok.sys.EntryKind
import rtok.sys.isDir
import rtok.sys.listDirOrEmpty
import rtok.sys.symlinkMeta
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

object JunkReview {
    /** The evidence of a path the user named in `[agents.junk] extra` (D36). */
    const val EXTRA = "[agents.junk] extra"

    const val EXCLUDED = "excluded by [agents.junk] exclude"

    internal const val SNAPSHOT = "snapshot: never cleared; Gemini CLI restores it with /restore"

    /** Crash reports macOS writes for a crashed process. */
    internal val REPORT_EXT = listOf("ips", "crash", "dmp", "diag")
}

private fun days(n: UInt): Duration = n.toLong().days

/** `[agents.junk] temp_min_age_hours` as a window. */
fun junkHours(n: UInt): Duration = n.toLong().hours

private fun List<JunkItem>.classed(cls: String) = map { it.copy(cls = cls) }

/** Entries of the §22 log folders (or a single §22 log file) older than `keep_logs_days`. */
fun logItems(
    dirs: List<String>,
    junk: AgentsJunk,
    cx: JunkCtx,
    limit: Duration,
): List<JunkItem> = agedItems("logs", dirs, JunkCache.SECTION_22, days(junk.keepLogsDays), cx, limit).classed("review")

/**
 * rtok's own copies in the `.backup_configs` (and legacy `_backup`) folder of each of [dirs] that `setup.backup_files`
 * has no room for: a cap lowered after the copies were taken. The newest copy of a file always stays.
 */
fun backupItems(
    dirs: List<String>,
    keep: UInt,
    limit: Duration,
): List<JunkItem> {
    val k = if (keep > Int.MAX_VALUE.toUInt()) Int.MAX_VALUE else keep.toInt()
    val stale = dirs.flatMap { d -> BACKUP_DIRS.flatMap { b -> staleBackups(join(d, b), k) } }
    return stale.map { junkItem("backups", it, JunkCache.RTOK_OWN, null, limit) }.classed("review")
}

/**
 * Gemini CLI's restore points (`research.md` §22.1): the shadow git repo of each project under `history/` and each
 * project's `tmp/<hash>/checkpoints`. Class `never`: listed for their size with the host's own way back, never cleared.
 */
fun snapshotItems(
    roots: JunkRoots,
    limit: Duration,
): List<JunkItem> {
    val gemini = roots.resolve("{home}/.gemini")

    fun children(d: String) = listDirOrEmpty(d).map { join(d, it) }
    val dirs = children(join(gemini, "history")) + children(join(gemini, "tmp")).map { join(it, "checkpoints") }
    return dirs
        .filter { isDir(it) && !isSymlink(it) }
        .map { junkItem("snapshots", it, JunkCache.SECTION_22, JunkReview.SNAPSHOT, limit).copy(cls = "never") }
}

/**
 * The entries of an `extra` crash folder. Dumps can hold memory with secrets, so they are only sized and removed, never
 * read. One older than `[agents.junk] crash_dump_min_age_days` is `safe`; a younger one (or one whose age cannot be
 * read) is `review`.
 */
fun crashDumpItems(
    dirs: List<String>,
    junk: AgentsJunk,
    cx: JunkCtx,
    limit: Duration,
): List<JunkItem> {
    val old = days(junk.crashDumpMinAgeDays).inWholeNanoseconds
    val items = agedItems("crash-dumps", dirs, JunkReview.EXTRA, Duration.ZERO, cx, limit)

    fun age(i: JunkItem): Long? = newest(i.path)?.time?.let { t -> (cx.policy.now - t).takeIf { it >= 0 } }
    return items.map { i -> i.copy(cls = if ((age(i) ?: -1) > old) "safe" else "review") }
}

/** The lowercase names a host's processes go by in a crash report: its binaries and the file stem of its desktop apps. */
fun processNames(host: Host): List<String> {
    val stems =
        host.variants
            .flatMap { it.apps }
            .map(::stem)
            .filter { it.isNotEmpty() }
    val bins = host.variants.flatMap { it.bins }
    return (bins + stems).map { it.lowercase() }
}

/**
 * macOS `~/Library/Logs/DiagnosticReports` files named for one of [names] (`Cursor-2026-…ips`, `claude_2026-…crash`).
 * §22 has no row for them, so each is listed and kept (D36).
 */
fun diagnosticReports(
    names: List<String>,
    roots: JunkRoots,
    limit: Duration,
): List<JunkItem> {
    val dir = roots.resolve("{home}/Library/Logs/DiagnosticReports")

    fun named(raw: String): Boolean {
        val file = raw.lowercase()
        val ext = file.lastIndexOf('.').takeIf { it >= 0 }?.let { file.substring(it + 1) }
        val follows = { n: String -> file.removePrefix(n).takeIf { file.startsWith(n) }?.firstOrNull() }
        return ext in JunkReview.REPORT_EXT && names.any { n -> follows(n)?.let { it in "-_. " } == true }
    }
    return listDirOrEmpty(dir)
        .filter { named(it) && runCatching { symlinkMeta(join(dir, it)).kind == EntryKind.File }.getOrDefault(false) }
        .map {
            junkItem("crash-dumps", join(dir, it), JunkKinds.NO_EVIDENCE, JunkKinds.NOT_DOCUMENTED, limit).copy(cls = "review")
        }
}

/**
 * The `[agents.junk] extra` entries of [agent] (a host id or `rtok`): a `cache` folder joins the owned caches (emptied,
 * its top folder kept); a `temp` or `logs` folder gives its entries by that kind's age rule. A relative path or an
 * unknown kind, which `config validate` refuses, is ignored here rather than guessed at.
 */
@Suppress("LongParameterList")
fun extraItems(
    junk: AgentsJunk,
    agent: String,
    rtokHome: String,
    home: String,
    cx: JunkCtx,
    limit: Duration,
): Pair<List<OwnedCache>, List<JunkItem>> {
    val owned = ArrayList<OwnedCache>()
    val items = ArrayList<JunkItem>()
    for (e in junk.extra.filter { it.host == agent }) {
        val path = expandWith(e.path, rtokHome, home)
        if (!path.startsWith('/')) continue
        val one = listOf(path)
        when (e.kind) {
            "cache" -> owned += OwnedCache(path, JunkReview.EXTRA, idleRule = false)
            "temp" -> items += agedItems("temp", one, JunkReview.EXTRA, junkHours(junk.tempMinAgeHours), cx, limit)
            "logs" -> items += agedItems("logs", one, JunkReview.EXTRA, days(junk.keepLogsDays), cx, limit).classed("review")
            "crash-dumps" -> items += crashDumpItems(one, junk, cx, limit)
        }
    }
    return owned to items
}
