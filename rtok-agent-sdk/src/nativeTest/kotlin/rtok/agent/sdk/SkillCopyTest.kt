// Port of crates/rtok-agent-sdk/src/lib.rs skill tests: `skill_plan_covers_install_reinstall_remove_and_foreign`
// and the `skill_copy_*` cases (install/reinstall/remove, edited copy, foreign tree, prefix, byte copy, symlinks).
package rtok.agent.sdk

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.utimbuf
import rtok.sys.EntryKind
import rtok.sys.createDirAll
import rtok.sys.entryKind
import rtok.sys.exists
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.pid
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.symlink
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun tmp(name: String): String {
    val dir = join(tempDir(), "rtok-agent-sdk-skill-$name-${pid()}")
    removeDirAll(dir)
    createDirAll(dir)
    return dir
}

private fun write(
    path: String,
    text: String,
) {
    rtok.sys.parent(path)?.let(::createDirAll)
    writeString(path, text)
}

/** A hub `src/SKILL.md` under [dir]; returns `src`. */
private fun hub(dir: String): String = join(dir, "src").also { write(join(it, "SKILL.md"), "hub body\n") }

private fun view(
    present: Boolean,
    symlink: Boolean,
    prefixed: Boolean,
    marked: Boolean,
    sameBytes: Boolean,
) = SkillView(present, symlink, prefixed, marked, sameBytes)

class SkillCopyTest {
    private val plain = Apply()

    @Test
    fun skillPlanCoversInstallReinstallRemoveAndForeign() {
        val absent = view(false, false, false, false, false)
        assertEquals(SkillPlan.Copy, skillPlan(false, absent))
        assertEquals(SkillPlan.NoChanges, skillPlan(true, absent))
        // Unprefixed + marker, bytes differ: installed, the edit is not clobbered.
        assertEquals(SkillPlan.NoChanges, skillPlan(false, view(true, false, false, true, false)))
        assertEquals(SkillPlan.LeaveForeign, skillPlan(false, view(true, false, false, false, false)))
        assertEquals(SkillPlan.Remove, skillPlan(true, view(true, false, false, true, false)))
        assertEquals(SkillPlan.Remove, skillPlan(true, view(true, false, false, false, true)))
        assertEquals(SkillPlan.NoChanges, skillPlan(true, view(true, false, false, false, false)))
        // The prefix replaces a stale or foreign tree and a symlink; an unprefixed symlink is not ours.
        assertEquals(SkillPlan.Copy, skillPlan(false, view(true, false, true, false, false)))
        assertEquals(SkillPlan.Copy, skillPlan(false, view(true, true, true, false, false)))
        assertEquals(SkillPlan.LeaveForeign, skillPlan(false, view(true, true, false, false, false)))
        assertEquals(SkillPlan.Remove, skillPlan(true, view(true, true, true, false, false)))
        assertEquals(SkillPlan.NoChanges, skillPlan(true, view(true, true, false, false, false)))
    }

    @Test
    fun installReinstallRemoveKeepsForeign() {
        val dir = tmp("cycle")
        val dest = join(dir, "skills/rtok")
        val foreign = join(dir, "skills/other/SKILL.md")
        write(foreign, "# other\n")
        val copy = SkillCopy(hub(dir), dest, null)
        val first = copy.run(plain, false)
        assertTrue(first.startsWith("+ skill"), first)
        assertTrue(isFile(join(dest, OWNED_MARKER)))
        assertEquals(NO_CHANGES, copy.run(plain, false))
        assertEquals("- skill $dest", copy.run(plain, true))
        assertEquals(NO_CHANGES, copy.run(plain, true))
        assertTrue(isFile(foreign))
        removeDirAll(dir)
    }

    /** T246.4: a copy written to after the marker stays unless `--yes`; with no terminal nobody answers. */
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun removeAsksBeforeTakingAnEditedCopy() {
        val dir = tmp("edited")
        val dest = join(dir, "skills/rtok")
        val copy = SkillCopy(hub(dir), dest, null)
        copy.run(plain, false)
        // Age the marker instead of dating the file forward: SKILL.md is now the later write.
        memScoped {
            val times = alloc<utimbuf>()
            times.actime = 0
            times.modtime = 0
            platform.posix.utime(join(dest, OWNED_MARKER), times.ptr)
        }
        val out = copy.run(plain, true)
        assertTrue(out.startsWith("leave ") && out.contains("changed by you"), out)
        assertTrue(isFile(join(dest, "SKILL.md")))
        assertTrue(copy.run(Apply(yes = true), true).startsWith("- skill"))
        assertFalse(exists(dest))
        removeDirAll(dir)
    }

    @Test
    fun leavesAForeignSkillTree() {
        val dir = tmp("foreign")
        val dest = join(dir, "skills/notes")
        write(join(dest, "SKILL.md"), "# foreign\n")
        val out = SkillCopy(hub(dir), dest, null).run(plain, false)
        assertTrue(out.contains("leave"), out)
        assertEquals("# foreign\n", readToString(join(dest, "SKILL.md")))
        removeDirAll(dir)
    }

    /** T380: a destination named `rtok…` is ours, so a foreign tree there is replaced. */
    @Test
    fun overwritesAPrefixedForeignTree() {
        val dir = tmp("prefix")
        val dest = join(dir, "skills/rtok-x")
        write(join(dest, "SKILL.md"), "# foreign\n")
        val copy = SkillCopy(hub(dir), dest, null)
        assertTrue(copy.run(plain, false).startsWith("+ skill"))
        assertEquals("hub body\n", readToString(join(dest, "SKILL.md")))
        assertTrue(isFile(join(dest, OWNED_MARKER)))
        assertTrue(copy.run(plain, true).startsWith("- skill"))
        assertFalse(exists(dest))
        removeDirAll(dir)
    }

    /** T380: an unprefixed directory matching `src` byte for byte is ours without a marker. */
    @Test
    fun removeTakesAnUnprefixedByteCopy() {
        val dir = tmp("bytes")
        val dest = join(dir, "skills/worktrees")
        write(join(dest, "SKILL.md"), "hub body\n")
        val copy = SkillCopy(hub(dir), dest, null)
        assertEquals(NO_CHANGES, copy.run(plain, false))
        assertEquals("- skill $dest", copy.run(plain, true))
        assertFalse(exists(dest))
        removeDirAll(dir)
    }

    /** T380: a prefixed symlink is replaced; its target stays. */
    @Test
    fun unlinksAPrefixedSymlinkAndLeavesItsTarget() {
        val dir = tmp("prefix-link")
        val target = join(dir, "personal")
        write(join(target, "SKILL.md"), "mine\n")
        val dest = join(dir, "skills/rtok-worktrees")
        createDirAll(join(dir, "skills"))
        symlink(target, dest)
        val copy = SkillCopy(hub(dir), dest, null)
        assertTrue(copy.run(plain, false).startsWith("+ skill"))
        assertEquals(EntryKind.Dir, entryKind(dest))
        assertEquals("hub body\n", readToString(join(dest, "SKILL.md")))
        assertEquals("mine\n", readToString(join(target, "SKILL.md")))
        removeDirAll(dir)
    }

    /** T380: an unprefixed symlink is not ours; install and remove both leave the link and its target. */
    @Test
    fun leavesAnUnprefixedSymlink() {
        val dir = tmp("foreign-link")
        val target = join(dir, "personal")
        write(join(target, "SKILL.md"), "mine\n")
        val dest = join(dir, "skills/worktrees")
        createDirAll(join(dir, "skills"))
        symlink(target, dest)
        val copy = SkillCopy(hub(dir), dest, null)
        assertTrue(copy.run(plain, false).contains("leave"))
        assertEquals(EntryKind.Symlink, entryKind(dest))
        assertEquals(NO_CHANGES, copy.run(plain, true))
        assertEquals("mine\n", readToString(join(target, "SKILL.md")))
        removeDirAll(dir)
    }

    /** A dry run names the label and the path and writes nothing. */
    @Test
    fun dryRunDescribesAndTouchesNothing() {
        val dir = tmp("dry")
        val dest = join(dir, "skills/rtok")
        val out = SkillCopy(hub(dir), dest, "~/.x/skills/rtok").run(Apply(dryRun = true), false)
        assertEquals("+ skill → ~/.x/skills/rtok ($dest)", out)
        assertFalse(exists(dest))
        removeDirAll(dir)
    }
}
