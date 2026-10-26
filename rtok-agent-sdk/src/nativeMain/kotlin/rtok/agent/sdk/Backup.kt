// Port of crates/rtok-agent-sdk/src/lib.rs (backup + write subset): undo copies of host files in
// `.backup_configs/<name>.bak-<ts>[-<n>]` and the atomic write every host-file change goes through.
// The rest of the crate (host registration, doctor) is Phase 9.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)

package rtok.agent.sdk

import kotlinx.cinterop.*
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.lstat
import platform.posix.stat
import rtok.sys.*
import kotlin.time.Clock

/** How a host-file change is applied. */
data class Apply(
    /** Describe the change and write nothing. */
    val dryRun: Boolean = false,
    /** Copy each file to `.backup_configs/` before the first write to it. */
    val backup: Boolean = false,
    /** Newest generations kept per file after a backup; `0` keeps all. */
    val backupFiles: Int = 0,
    /** Accept every offer without asking. */
    val yes: Boolean = false,
) {
    /** True when this run may write a file for [report]: a report of only `leave …` / `? …` lines changed nothing (T246). */
    fun writes(report: String): Boolean = !dryRun && report != NO_CHANGES && !report.lines().all { it.startsWith("leave ") || it.startsWith("? ") }
}

const val NO_CHANGES = "no changes"

/** Directory name next to a host config that holds undo copies. */
const val BACKUP_DIR = ".backup_configs"

/** The pre-T911 folder; still rtok's own for the cap. */
const val LEGACY_BACKUP_DIR = "_backup"
val BACKUP_DIRS = listOf(BACKUP_DIR, LEGACY_BACKUP_DIR)

fun isBackupDir(dir: String): Boolean = components(dir).lastOrNull() in BACKUP_DIRS

private fun fileName(p: String) = components(p).lastOrNull() ?: ""

/** Copy [path] beside itself into `.backup_configs/`; null when absent or an identical generation exists. Prunes to [keep]. */
fun backup(
    path: String,
    keep: Int,
): String? {
    if (!exists(path)) return null
    return backupAt(
        path,
        Clock.System
            .now()
            .epochSeconds
            .toULong(),
    )?.also { pruneBackups(it, keep) }
}

/** Delete the oldest generations beside [kept] until [keep] remain; [kept] itself never goes. Best effort. */
fun pruneBackups(
    kept: String,
    keep: Int,
) {
    val dir = parent(kept) ?: return
    val file = fileName(kept)
    if (keep == 0 || !isBackupDir(dir)) return
    val at = file.lastIndexOf(".bak-")
    if (at < 0) return
    val older = generations(dir, file.substring(0, at)).filter { it.second != kept }.sortedWith(GEN_ORDER)
    val excess = maxOf(older.size + 1 - keep, 0)
    older.take(excess).forEach { runCatching { removeFile(it.second) } }
}

/** Per base name, the generations a cap of [keep] has no room for, oldest first. Read-only. */
fun staleBackups(
    dir: String,
    keep: Int,
): List<String> {
    if (keep == 0 || !isBackupDir(dir)) return emptyList()
    val names =
        runCatching { listDir(dir) }
            .getOrDefault(emptyList())
            .mapNotNull { f -> f.lastIndexOf(".bak-").takeIf { it >= 0 }?.let { f.substring(0, it) } }
            .distinct()
            .sorted()
    return names.flatMap { n -> generations(dir, n).sortedWith(GEN_ORDER).let { all -> all.take(maxOf(all.size - keep, 0)).map { it.second } } }
}

private val GEN_ORDER = compareBy<Pair<Pair<ULong, ULong>, String>>({ it.first.first }, { it.first.second }, { it.second })

private fun generations(
    dir: String,
    name: String,
): List<Pair<Pair<ULong, ULong>, String>> {
    val prefix = "$name.bak-"
    return runCatching { listDir(dir) }.getOrDefault(emptyList()).mapNotNull { f ->
        val p = join(dir, f)
        if (!f.startsWith(prefix) || !isRegular(p)) null else generation(f.removePrefix(prefix))?.let { it to p }
    }
}

/** `DirEntry::file_type().is_file()`: a regular file, symlinks not followed. */
private fun isRegular(p: String): Boolean =
    memScoped {
        val st = alloc<stat>()
        lstat(p, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFREG
    }

/** `<ts>` or `<ts>-<n>` as a sortable pair. */
internal fun generation(suffix: String): Pair<ULong, ULong>? {
    val dash = suffix.indexOf('-')
    val ts = if (dash < 0) suffix else suffix.substring(0, dash)
    val n = if (dash < 0) "0" else suffix.substring(dash + 1)

    fun num(s: String) = s.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toULongOrNull()
    return num(ts)?.let { t -> num(n)?.let { t to it } }
}

/** [backup] with the clock passed in. */
internal fun backupAt(
    path: String,
    ts: ULong,
): String? {
    val name = fileName(path)
    val body = readBytes(path)
    val dir = parent(path)?.let { join(it, BACKUP_DIR) } ?: return null
    if (generations(dir, name).any { (_, p) -> fileLen(p) == body.size.toLong() && runCatching { readBytes(p).contentEquals(body) }.getOrDefault(false) }) return null
    createDirAll(dir)
    // Past the highest `-<n>` of this second, so the new copy always sorts newest.
    var n = generations(dir, name).filter { it.first.first == ts }.maxOfOrNull { it.first.second + 1u } ?: 0u

    fun slot(n: ULong) = join(dir, if (n == 0uL) "$name.bak-$ts" else "$name.bak-$ts-$n")
    var bak = slot(n)
    while (exists(bak)) bak = slot(++n)
    writeBytes(bak, body)
    fileMode(path)?.let { platform.posix.chmod(bak, it.convertMode()) }
    return bak
}

private fun Int.convertMode(): platform.posix.mode_t = convert()

/** Write [body] to [path] when [apply] writes for [report]: backup first if asked, then an atomic replace. */
fun write(
    apply: Apply,
    path: String,
    body: String,
    report: String,
) {
    if (!apply.writes(report)) return
    if (apply.backup) backup(path, apply.backupFiles)
    parent(path)?.let { runCatching { createDirAll(it) } }
    writeAtomic(path, body.encodeToByteArray())
}

/** Write to a sibling temp file, then rename over [path] (fs-kit `write::atomic`). */
fun writeAtomic(
    path: String,
    bytes: ByteArray,
) {
    val tmp = "${parent(path) ?: "."}/.${fileName(path)}.tmp-${pid()}"
    writeBytes(tmp, bytes)
    fileMode(path)?.let { platform.posix.chmod(tmp, it.convertMode()) }
    if (platform.posix.rename(tmp, path) != 0) {
        runCatching { removeFile(tmp) }
        throw IoException(IoException.Kind.Other, "$path: rename failed")
    }
}
