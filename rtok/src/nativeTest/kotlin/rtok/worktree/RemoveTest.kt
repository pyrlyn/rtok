// Port of the src/worktree/remove.rs and src/removal_preview.rs tests.
package rtok.worktree

import rtok.RemovalPreview
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals

private const val KB2 = 2048L
private const val KB1 = 1024L
private const val THREE = 3L

class RemoveTest {
    @Test fun aTaskIdNamesTheBranchOrTheLockTask() {
        val cases =
            listOf(
                Triple("t286-worktree-remove", null, true),
                Triple("T286", null, true),
                Triple("t2860", null, false),
                Triple(null, "me | T286 | 2026-09-27", true),
                Triple("main", null, false),
            )
        for ((branch, locked, hit) in cases) {
            assertEquals(hit, WorktreeRemove.isTask(WorktreeRecord("", branch = branch, locked = locked), "t286"), "$branch $locked")
        }
    }

    @Test fun aRemovalPrintsItsLineAndTheTotals() {
        val out = RemovalPreview.render(listOf(Triple("/w/a", KB2, THREE), Triple("/w/b", KB1, 1L)))
        assertEquals("- /w/a  2.0 KB  3 files\n- /w/b  1.0 KB  1 file\n2 paths, 4 files, -3.0 KB\n", out)
        assertEquals("", RemovalPreview.render(emptyList()))
    }

    @Test fun filesCountsFilesNotDirectories() {
        val dir = tmpDir("removal-files")
        createDirAll("$dir/a/b")
        writeString("$dir/a/x", "x")
        writeString("$dir/a/b/y", "y")
        assertEquals(2L, RemovalPreview.sizeOf(dir).second)
    }
}
