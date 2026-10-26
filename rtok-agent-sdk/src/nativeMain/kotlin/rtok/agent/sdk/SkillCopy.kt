// Port of crates/rtok-agent-sdk/src/lib.rs `SkillPlan`, `SkillView`, `skill_plan`, `SkillCopy`, `edited_since_marked`.
// A hub skill directory (`skills/rtok/`) copied into a host's documented skill root; a symlink is never followed.
package rtok.agent.sdk

import rtok.sys.EntryKind
import rtok.sys.components
import rtok.sys.createDirAll
import rtok.sys.entryKind
import rtok.sys.fileStat
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.parent

/** What [SkillCopy.run] will do given the destination state; pure so tests drive the installer's decision. */
enum class SkillPlan {
    /** Copy the hub tree and write [OWNED_MARKER], replacing what [SkillView.ours] already holds. */
    Copy,

    /** Already installed, already gone, or a foreign tree on remove. */
    NoChanges,

    /** The destination exists but is not an rtok-owned skill tree. */
    LeaveForeign,

    /** Delete the owned skill directory. */
    Remove,
}

/** Destination facts for [skillPlan]. A symlink's target is neither proof nor deleted (T380). */
data class SkillView(
    val present: Boolean,
    val symlink: Boolean,
    /** The last path component starts with `rtok`: that slot is ours, an unprefixed one may be a user's skill. */
    val prefixed: Boolean,
    val marked: Boolean,
    val sameBytes: Boolean,
) {
    /** Marker, byte-for-byte copy or a `rtok` name; a symlink counts only by its own prefixed name. */
    fun ours(): Boolean =
        when {
            !present -> false
            symlink -> prefixed
            else -> prefixed || marked || sameBytes
        }
}

/** Decide install / reinstall / remove from [view]. */
fun skillPlan(
    remove: Boolean,
    view: SkillView,
): SkillPlan =
    when {
        !view.present -> if (remove) SkillPlan.NoChanges else SkillPlan.Copy

        remove -> if (view.ours()) SkillPlan.Remove else SkillPlan.NoChanges

        view.symlink -> if (view.prefixed) SkillPlan.Copy else SkillPlan.LeaveForeign

        view.sameBytes -> SkillPlan.NoChanges

        // The prefix is the slot: a foreign or stale tree under a `rtok` name is replaced; an unprefixed
        // marked copy stays so an edit there is not clobbered on install.
        view.prefixed -> SkillPlan.Copy

        view.marked -> SkillPlan.NoChanges

        else -> SkillPlan.LeaveForeign
    }

/**
 * The hub skill directory [src] copied to [dest] in a host's skill root. A `rtok…` directory is ours; anything else
 * only with [OWNED_MARKER] or a byte-for-byte copy of [src]. [label] is how [dest] reads to a person.
 */
data class SkillCopy(
    val src: String,
    val dest: String,
    val label: String?,
) {
    private fun prefixed() = components(dest).lastOrNull()?.startsWith("rtok") == true

    /** No link is followed: a user's target is never taken for ours. */
    fun view(): SkillView =
        when (entryKind(dest)) {
            null -> {
                SkillView(present = false, symlink = false, prefixed = prefixed(), marked = false, sameBytes = false)
            }

            EntryKind.Symlink -> {
                SkillView(present = true, symlink = true, prefixed = prefixed(), marked = false, sameBytes = false)
            }

            else -> {
                val dir = isDir(dest)
                SkillView(
                    present = true,
                    symlink = false,
                    prefixed = prefixed(),
                    marked = dir && isFile(join(dest, OWNED_MARKER)),
                    sameBytes = dir && treeCopies(src, dest, keepBytes, ""),
                )
            }
        }

    private fun destDesc() = if (label != null) "$label ($dest)" else dest

    /** Copy or remove the hub skill tree; one report line. A dry run describes the change and touches nothing. */
    fun run(
        apply: Apply,
        remove: Boolean,
    ): String {
        val view = view()
        return when (skillPlan(remove, view)) {
            SkillPlan.NoChanges -> NO_CHANGES
            SkillPlan.LeaveForeign -> "leave $dest (not an rtok skill; remove by hand)"
            SkillPlan.Remove -> removeOwned(apply, view)
            SkillPlan.Copy -> copy(apply, view)
        }
    }

    private fun removeOwned(
        apply: Apply,
        view: SkillView,
    ): String {
        // A symlink has no marker of its own; its target is not looked at.
        if (!view.symlink && editedSinceMarked(dest)) keepEdited(apply, dest)?.let { return it }
        val report = if (apply.dryRun) "- skill ${destDesc()}" else "- skill $dest"
        if (apply.writes(report)) unlinkAt(dest)
        return report
    }

    private fun copy(
        apply: Apply,
        view: SkillView,
    ): String {
        val report = "+ skill → ${destDesc()}"
        if (apply.writes(report)) {
            if (view.present) unlinkAt(dest)
            parent(dest)?.let(::createDirAll)
            copyOwned(src, dest)
        }
        return report
    }
}

/**
 * Something under the owned copy [dest] is newer than its [OWNED_MARKER] (T246.4): the marker is written last, so a
 * later write is the user's. An older rtok's copy is not an edit; an unreadable time proves nothing.
 */
internal fun editedSinceMarked(dest: String): Boolean {
    val marked = fileStat(join(dest, OWNED_MARKER))?.first ?: return false
    return newerThan(dest, marked)
}

private fun newerThan(
    dir: String,
    marked: Long,
): Boolean =
    runCatching { listDir(dir) }.getOrNull().orEmpty().any { name ->
        val p = join(dir, name)
        name != OWNED_MARKER && ((fileStat(p)?.first ?: Long.MIN_VALUE) > marked || (isDir(p) && newerThan(p, marked)))
    }
