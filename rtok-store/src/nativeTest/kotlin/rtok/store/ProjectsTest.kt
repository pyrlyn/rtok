package rtok.store

import platform.posix.symlink
import rtok.sys.createDirAll
import rtok.sys.isDir
import rtok.sys.removeDirAll
import kotlin.test.*

/** Port of projects.rs tests. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class ProjectsTest {
    private val s = Store.openInMemory()

    /** projects.rs `fixture::Dir`: a scratch tree; `sub` creates and returns a child directory. */
    private class Dir(
        tag: String,
    ) {
        val root = tmpDir("proj-$tag")

        fun sub(name: String) = "$root/$name".also { createDirAll(it) }
    }

    private fun indexRow(root: String) {
        for (path in listOf("a.rs", "stale.rs")) s.index(root, path, symRow("main", 1, true))
        s.setExtractorFingerprint(root, "fp")
        s.markSymbolsStaleIn(root, "stale.rs")
    }

    @Test fun autoAddSkipsUnwalkableRootsAndNeverRenamesAKnownProject() {
        val dir = Dir("auto-add")
        s.autoAddProject("/", Origin.Session, null)
        s.autoAddProject("${dir.root}/gone", Origin.Session, null)
        assertTrue(s.projects().isEmpty())
        val manual = s.registerProject(dir.sub("a"), Origin.Manual)
        s.autoAddProject(dir.sub("a"), Origin.Worktree, "br")
        assertNull(s.project(manual.id)!!.name)
        s.autoAddProject(dir.sub("b"), Origin.Worktree, "br")
        val b = s.projectByRoot(dir.sub("b"))!!
        assertEquals(Origin.Worktree to "br", b.origin to b.displayName())
        s.renameProject(b.id, "mine")
        s.autoAddProject(dir.sub("b"), Origin.Worktree, "br")
        assertEquals("mine", s.project(b.id)!!.name)
        removeDirAll(dir.root)
    }

    @Test fun spellingsOfOneDirectoryAreOneProject() {
        val dir = Dir("dedup")
        val real = dir.sub("real")
        symlink(real, "${dir.root}/link")
        val first = s.registerProject(real, Origin.Session)
        val byDots = s.registerProject("${dir.root}/real/../real", Origin.Manual)
        val byLink = s.registerProject("${dir.root}/link", Origin.Reference)
        assertEquals(first.id to first.id, byDots.id to byLink.id)
        assertEquals(Origin.Session, byLink.origin, "the first origin stands")
        assertEquals(1, s.projects().size)
        assertEquals("real", first.displayName())
        removeDirAll(dir.root)
    }

    @Test fun oneProjectIsSelectedAndAnUnknownIdChangesNothing() {
        val dir = Dir("select")
        val a = s.registerProject(dir.sub("a"), Origin.Manual)
        val b = s.registerProject(dir.sub("b"), Origin.Manual)
        assertNull(s.selectedProject())
        assertTrue(s.selectProject(a.id))
        assertTrue(s.selectProject(b.id))
        assertFalse(s.selectProject(9999))
        assertEquals(listOf(b.id), s.projects().filter { it.selected }.map { it.id })
        removeDirAll(dir.root)
    }

    @Test fun renameSetsAndClearsTheDisplayName() {
        val dir = Dir("rename")
        val p = s.registerProject(dir.sub("a"), Origin.Manual)
        assertTrue(s.renameProject(p.id, "  api  "))
        assertEquals("api", s.project(p.id)!!.displayName())
        assertTrue(s.renameProject(p.id, "   "))
        assertEquals("a", s.project(p.id)!!.displayName())
        assertFalse(s.renameProject(9999, "x"))
        removeDirAll(dir.root)
    }

    @Test fun aDeletedRootIsMissingAndStaysListed() {
        val dir = Dir("missing")
        val root = dir.sub("gone")
        val p = s.registerProject(root, Origin.Manual)
        assertFalse(p.missing())
        removeDirAll(root)
        assertTrue(s.project(p.id)!!.missing())
        assertEquals(1, s.projects().size)
        removeDirAll(dir.root)
    }

    @Test fun aMissingSelectionFallsBackToTheWorkingDirectory() {
        val dir = Dir("resolve")
        val here = dir.sub("here")
        val gone = dir.sub("gone")
        val h = s.registerProject(here, Origin.Manual)
        val g = s.registerProject(gone, Origin.Manual)
        assertEquals(Resolved(null, false), s.resolveProject(dir.sub("elsewhere")))
        val first = s.resolveProject(here)
        assertEquals(h.id, first.project?.id)
        assertFalse(first.fellBack, "nothing was stored, so nothing was replaced")
        s.selectProject(g.id)
        assertEquals(g.id, s.resolveProject(here).project!!.id)
        removeDirAll(gone)
        val back = s.resolveProject(here)
        assertEquals(h.id to true, back.project?.id to back.fellBack)
        assertEquals(h.id, s.selectedProject()!!.id)
        assertEquals(h.id, s.resolveProject(dir.sub("elsewhere")).project?.id, "a live selection stands")
        removeDirAll(dir.root)
    }

    @Test fun removeDropsOnlyThatProjectsIndex() {
        val dir = Dir("remove")
        val a = s.registerProject(dir.sub("a"), Origin.Manual)
        val b = s.registerProject(dir.sub("b"), Origin.Manual)
        indexRow(a.root)
        indexRow(b.root)
        s.fileRankPut(a.root, "{}")
        s.fileRankPut(b.root, "{}")
        s.selectProject(a.id)
        assertTrue(s.removeProject(a.id))
        assertFalse(s.removeProject(a.id))
        assertEquals(0L, s.symbolCount(a.root))
        assertEquals(1L, s.symbolCount(b.root))
        assertTrue(s.symbolStalePaths(a.root).isEmpty())
        assertEquals(listOf("stale.rs"), s.symbolStalePaths(b.root))
        assertNull(s.extractorFingerprint(a.root))
        assertNotNull(s.extractorFingerprint(b.root))
        assertNull(s.fileRankGet(a.root))
        assertNotNull(s.fileRankGet(b.root))
        assertNull(s.selectedProject())
        assertTrue(isDir("${dir.root}/a"), "the files are never touched")
        removeDirAll(dir.root)
    }
}
