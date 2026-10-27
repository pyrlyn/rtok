// Port of the file predicates of src/agents/junk_kinds.rs: which names are swap, lock, index and backup leftovers,
// and the reason a live owner (a swap file's pid, a held lock) keeps one.
package rtok.agents

import rtok.sys.EntryKind
import rtok.sys.IoException
import rtok.sys.fileName
import rtok.sys.lockHeld
import rtok.sys.processAlive
import rtok.sys.readLink
import rtok.sys.readPrefix

/** Vim, Chrome and Emacs leave these beside what they edit; Emacs' `.#name` is a symlink. */
private fun isSwap(
    name: String,
    kind: EntryKind,
): Boolean {
    val file = kind == EntryKind.File && listOf(".swp", ".swo", ".crswap").any { name.endsWith(it) }
    return file || (kind == EntryKind.Symlink && name.startsWith(".#"))
}

private fun isLock(
    name: String,
    kind: EntryKind,
): Boolean =
    when {
        kind == EntryKind.Symlink -> false
        name == "LOCK" -> kind == EntryKind.File
        else -> name.endsWith(".lock") && name !in JunkKinds.PACKAGE_LOCKS
    }

/** A language server's folder and the journal files of a SQLite database. */
private fun isIndex(
    name: String,
    kind: EntryKind,
): Boolean {
    val journal = kind == EntryKind.File && (name.endsWith("-wal") || name.endsWith("-shm"))
    return journal || (kind == EntryKind.Dir && name == ".rust-analyzer")
}

/** An editor's or a tool's copy of a file (`*.bak`, `*.bak-<ts>`, `*~`). */
private fun isBackup(
    name: String,
    kind: EntryKind,
): Boolean = kind == EntryKind.File && (name.endsWith(".bak") || name.contains(".bak-") || name.endsWith('~'))

internal fun lockOrSwap(
    name: String,
    kind: EntryKind,
): String? =
    when {
        isSwap(name, kind) -> "swap"
        isLock(name, kind) -> "locks"
        isIndex(name, kind) -> "index"
        isBackup(name, kind) -> "backups"
        else -> null
    }

/** Why a file stays because a process may use it, or null when nothing holds it. */
internal fun heldReason(path: String): String? =
    try {
        if (lockHeld(path)) JunkKinds.HELD else null
    } catch (e: IoException) {
        "cannot check whether a process holds it: ${e.message}"
    }

/** The pid a swap file names: Vim's `b0` block (little-endian at byte 24), Emacs' `user@host.PID[:boot]` link. */
private fun swapOwner(path: String): Int? =
    if (fileName(path)?.startsWith(".#") == true) {
        readLink(path)?.substringAfterLast('.')?.substringBefore(':')?.toIntOrNull()
    } else {
        readPrefix(path, SWAP_HEAD_LEN)?.takeIf { it.size == SWAP_HEAD_LEN.toInt() && it.decodeToString(0, 2) == "b0" }?.let { head ->
            (0 until PID_LEN).fold(0) { acc, i -> acc or ((head[PID_OFFSET + i].toInt() and BYTE_MASK) shl (i * BITS)) }
        }
    }

private const val SWAP_HEAD_LEN = 28L
private const val PID_OFFSET = 24
private const val PID_LEN = 4
private const val BITS = 8
private const val BYTE_MASK = 0xff

internal fun swapReason(path: String): String? {
    val pid = swapOwner(path) ?: return JunkKinds.OWNER_UNKNOWN
    return if (processAlive(pid)) "owner process $pid is running" else null
}
