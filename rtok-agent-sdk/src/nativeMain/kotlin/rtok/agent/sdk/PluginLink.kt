// Port of crates/rtok-agent-sdk/src/lib.rs `PluginLink` (offer/link/unlink cycle, `health`), `LinkHealth`,
// `CopyFix`/`keep_bytes`, `in_versioned_store`, `tree_copies[_with]`, `install_plugin`, `copy_owned[_with]`,
// `unlink_at`. Unix only: the install is a symlink, or an owned copy when the source sits in a versioned ketch store.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.agent.sdk

import rtok.sys.EntryKind
import rtok.sys.IoException
import rtok.sys.components
import rtok.sys.copyFile
import rtok.sys.createDirAll
import rtok.sys.entryKind
import rtok.sys.exists
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.parent
import rtok.sys.pid
import rtok.sys.readBytes
import rtok.sys.readLink
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.symlink
import rtok.sys.writeBytes

/** How every offer a user has not accepted says where to get the plugin trees. */
const val KETCH_INSTALL = "ketch install pyrlyn/rtok"

/** File an owned plugin copy carries, so remove takes back only what rtok installed. */
const val OWNED_MARKER = ".rtok-owned"

/** Rewrites one plugin file on its way into an owned copy: `(path under the plugin root, source bytes) → bytes written`. */
typealias CopyFix = (String, ByteArray) -> ByteArray

/** The [CopyFix] that changes nothing. */
val keepBytes: CopyFix = { _, bytes -> bytes }

/** [PluginLink.health]'s verdict on the destination. */
sealed interface LinkHealth {
    /** Nothing is installed there. */
    data object Absent : LinkHealth

    /** Something that is not rtok's sits there; left alone. */
    data object Foreign : LinkHealth

    /** Working and current. */
    data object Current : LinkHealth

    /** A symlink whose target is gone; the target as written. */
    data class Dangling(
        val target: String,
    ) : LinkHealth

    /** Works now but will not survive an upgrade, or is an older copy; why. */
    data class Stale(
        val why: String,
    ) : LinkHealth
}

/** A host plugin tree this repo ships, and where that host loads it from (D21 (6)). */
data class PluginLink(
    /** Repo-relative source (`plugins/cursor`), named in every report. */
    val srcRel: String,
    /** Absolute source tree (or file). */
    val src: String,
    /** Where the host expects the plugin. */
    val dest: String,
    /** How the destination reads to a person (`~/.cursor/plugins/local`); null spells the path itself. */
    val label: String?,
    /** The host's name as its users spell it, for the question. */
    val host: String,
) {
    /** Something sits at the destination: a link, a directory, anything. */
    fun linked(): Boolean = entryKind(dest) != null

    /** What sits at the destination is rtok's to remove (T75): a link, a plain file, a marked copy or a byte-complete copy of [src]. */
    fun ours(): Boolean =
        when (entryKind(dest)) {
            null, EntryKind.Other -> false
            EntryKind.Symlink, EntryKind.File -> true
            EntryKind.Dir -> isFile(join(dest, OWNED_MARKER)) || treeCopies(src, dest, keepBytes, "")
        }

    private fun mainDesc() = label ?: dest

    private fun destDesc() = if (label != null) "$label ($dest)" else "$dest ($src)"

    private fun declined() = "offer $srcRel → ${mainDesc()} (accept with --yes) $KETCH_INSTALL"

    /** Offer, link, or unlink; the one-line report. A dry run and a declined offer touch nothing. */
    fun run(
        apply: Apply,
        remove: Boolean,
        fix: CopyFix = keepBytes,
    ): String =
        when {
            apply.dryRun -> dryRun(remove, fix)

            remove -> unlinkPlan().also { if (it.startsWith("- ")) unlinkAt(dest) }

            linked() && upToDate(fix) -> NO_CHANGES

            // Never overwrite ground rtok did not put there, whatever the answer would be (T164).
            linked() && !ours() -> declined()

            // Asked before anything is removed, so a declined reinstall leaves an older working copy in place.
            !accepted(apply, "install $srcRel into ${mainDesc()} for $host?") -> declined()

            // Ours but stale (an older ketch version, a dangling link), or nothing there yet.
            else -> unlinkAt(dest).let { place(fix) }
        }

    /**
     * What [run] would report, decided the same way and touching nothing: a restart flow reads "would change" from
     * this, so an installed, current plugin must say [NO_CHANGES].
     */
    private fun dryRun(
        remove: Boolean,
        fix: CopyFix,
    ): String =
        when {
            remove -> unlinkPlan()
            linked() && upToDate(fix) -> NO_CHANGES
            else -> "offer $srcRel → ${destDesc()} $KETCH_INSTALL"
        }

    private fun unlinkPlan(): String =
        when {
            !linked() -> NO_CHANGES
            !ours() -> "leave $dest (not an rtok link; remove by hand)"
            else -> "- plugin $dest"
        }

    private fun place(fix: CopyFix): String {
        parent(dest)?.let(::createDirAll)
        installPlugin(src, dest, fix)
        return "+ plugin $srcRel → $dest" + (label?.let { " $it" } ?: "")
    }

    /** The destination carries exactly this build's plugin: the same live link target, or a byte-identical copy. */
    internal fun upToDate(fix: CopyFix): Boolean =
        when (entryKind(dest)) {
            EntryKind.Symlink -> exists(dest) && !inVersionedStore(src) && readLink(dest) == src
            EntryKind.File -> isFile(src) && readBytes(dest).contentEquals(readBytes(src))
            EntryKind.Dir -> treeCopies(src, dest, fix, "")
            null, EntryKind.Other -> false
        }

    /** What an rtok-owned destination needs, for `doctor` (T925); a foreign or absent one is nobody's problem here. */
    fun health(fix: CopyFix = keepBytes): LinkHealth =
        when {
            entryKind(dest) == null -> LinkHealth.Absent
            !ours() -> LinkHealth.Foreign
            entryKind(dest) == EntryKind.Symlink -> linkHealth(dest, readLink(dest).orEmpty())
            upToDate(fix) -> LinkHealth.Current
            else -> LinkHealth.Stale("a copy that differs from $src")
        }
}

private fun linkHealth(
    dest: String,
    target: String,
): LinkHealth =
    when {
        !exists(dest) -> LinkHealth.Dangling(target)
        inVersionedStore(target) -> LinkHealth.Stale("links into $target, which an upgrade removes")
        else -> LinkHealth.Current
    }

/** [path] sits under a ketch version directory, `…/store/<package>/v<version>/…`, which an upgrade removes (T925). */
fun inVersionedStore(path: String): Boolean =
    components(path).windowed(STORE_PATH_PARTS).any { (store, _, ver) ->
        store == "store" && ver.startsWith('v') && ver.drop(1).firstOrNull()?.isAsciiDigit() == true
    }

/** `store`, `<package>`, `v<version>`. */
private const val STORE_PATH_PARTS = 3

private fun Char.isAsciiDigit() = this in '0'..'9'

/** Remove [dest] itself; a symlink goes without its target. */
internal fun unlinkAt(dest: String) {
    when (entryKind(dest)) {
        EntryKind.Symlink, EntryKind.File -> removeFile(dest)
        EntryKind.Dir -> removeDirAll(dest)
        null, EntryKind.Other -> Unit
    }
}

/**
 * Every file under [src] sits in [dest] with the ([fix]ed) same bytes; extra files in [dest] are allowed.
 * An empty or unreadable [src] proves nothing. [rel] is [src]'s path under the plugin root.
 */
internal fun treeCopies(
    src: String,
    dest: String,
    fix: CopyFix,
    rel: String,
): Boolean {
    val names = if (isDir(src)) runCatching { listDir(src) }.getOrNull().orEmpty() else emptyList()
    return names.isNotEmpty() &&
        names.all { name ->
            val from = join(src, name)
            val there = join(dest, name)
            val r = if (rel.isEmpty()) name else "$rel/$name"
            if (isDir(from)) isDir(there) && treeCopies(from, there, fix, r) else sameFixedBytes(from, there) { fix(r, it) }
        }
}

private fun sameFixedBytes(
    from: String,
    there: String,
    fix: (ByteArray) -> ByteArray,
): Boolean {
    val a = runCatching { readBytes(from) }.getOrNull()
    val b = runCatching { readBytes(there) }.getOrNull()
    return a != null && b != null && fix(a).contentEquals(b)
}

/** A symlink, or an owned copy when [src] lives in a versioned ketch store whose directory an upgrade removes (T925). */
private fun installPlugin(
    src: String,
    dest: String,
    fix: CopyFix,
) {
    if (!inVersionedStore(src)) return symlink(src, dest)
    // Staged beside [dest] and renamed in: a copy cut short (a full disk) never sits at [dest] without its
    // marker, where every later run would take it for a foreign tree.
    val tmp = join(parent(dest) ?: ".", ".${components(dest).lastOrNull().orEmpty()}.tmp-${pid()}")
    unlinkAt(tmp)
    try {
        copyOwned(src, tmp, fix)
    } catch (e: IoException) {
        runCatching { unlinkAt(tmp) }
        throw e
    }
    if (platform.posix.rename(tmp, dest) != 0) {
        runCatching { unlinkAt(tmp) }
        throw IoException(IoException.Kind.Other, "$dest: rename failed")
    }
}

/** Copy [src] into [dest] (links followed) and leave [OWNED_MARKER] last; a single-file plugin is one plain copy. */
internal fun copyOwned(
    src: String,
    dest: String,
    fix: CopyFix = keepBytes,
) {
    if (isFile(src)) return copyFile(src, dest)
    copyDir(src, dest, fix, "")
    writeBytes(join(dest, OWNED_MARKER), ByteArray(0))
}

private fun copyDir(
    src: String,
    dest: String,
    fix: CopyFix,
    rel: String,
) {
    createDirAll(dest)
    for (name in listDir(src)) {
        val from = join(src, name)
        val to = join(dest, name)
        val r = if (rel.isEmpty()) name else "$rel/$name"
        if (isDir(from)) {
            copyDir(from, to, fix, r)
        } else {
            copyFile(from, to)
            val bytes = readBytes(from)
            val fixed = fix(r, bytes)
            if (!fixed.contentEquals(bytes)) writeBytes(to, fixed)
        }
    }
}
