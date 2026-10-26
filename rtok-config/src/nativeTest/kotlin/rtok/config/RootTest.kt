// Port of crates/rtok-config/src/root.rs tests.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.config

import platform.posix.symlink
import rtok.sys.*
import kotlin.test.*

private var next = 0

private fun tmp(name: String): String {
    val dir = "${tempDir()}/rtok-root-$name-${pid()}-${next++}"
    removeDirAll(dir)
    createDirAll(dir)
    return canon(dir)
}

private fun write(
    path: String,
    body: String,
) {
    createDirAll(parent(path)!!)
    writeString(path, body)
}

private fun linked(
    base: String,
    relative: Boolean,
) {
    val main = "$base/main"
    val wt = "$base/wt"
    val admin = "$main/.git/worktrees/wt"
    write("$main/.git/HEAD", "ref: refs/heads/main\n")
    val (commondir, gitdir) = if (relative) "../..\n" to "../main/.git/worktrees/wt" else "$main/.git\n" to admin
    write("$admin/commondir", commondir)
    write("$wt/.git", "gitdir: $gitdir\n")
    write("$wt/src/deep/a.rs", "")
}

class RootTest {
    @Test fun mainCheckoutFoundFromDeepInside() {
        val base = tmp("main")
        write("$base/repo/.git/HEAD", "")
        write("$base/repo/a/b/c/x.rs", "")
        val root = ProjectRoot.find("$base/repo/a/b/c")!!
        assertEquals("$base/repo", root.checkout)
        assertEquals("$base/repo", root.main)
        assertEquals("$base/repo/.git", root.gitDir)
    }

    @Test fun linkedWorktreeHasItsOwnCheckoutAndTheMainOne() {
        for (relative in listOf(false, true)) {
            val base = tmp("linked")
            linked(base, relative)
            val root = ProjectRoot.find("$base/wt/src/deep")!!
            assertEquals("$base/wt", root.checkout)
            assertEquals("$base/main", root.main)
            assertEquals("$base/main/.git", root.gitDir)
        }
    }

    @Test fun submoduleIsItsOwnProject() {
        val base = tmp("sub")
        write("$base/repo/.git/modules/dep/HEAD", "")
        write("$base/repo/vendor/dep/.git", "gitdir: ../../.git/modules/dep\n")
        write("$base/repo/vendor/dep/src/a.rs", "")
        val root = ProjectRoot.find("$base/repo/vendor/dep/src")!!
        assertEquals("$base/repo/vendor/dep", root.checkout)
        assertEquals("$base/repo/vendor/dep", root.main)
    }

    @Test fun outsideAnyRepoIsNone() {
        val base = tmp("none")
        write("$base/scratch/a.txt", "")
        val found = ProjectRoot.find("$base/scratch")
        assertTrue(found == null || !pathStartsWith(found.checkout, base))
    }

    @Test fun containAcceptsWhatStaysInside() {
        val root = tmp("in")
        write("$root/a/b.txt", "")
        assertEquals("$root/a/b.txt", contain(root, "a/b.txt"))
        assertEquals("$root/a/b.txt", contain(root, "a/../a/b.txt"))
        assertEquals(root, contain(root, "."))
        assertEquals(root, contain(root, root))
        assertEquals("$root/a/new/deeper.txt", contain(root, "$root/a/new/deeper.txt"))
    }

    @Test fun containRefusesWhatLeaves() {
        val base = tmp("out")
        val root = "$base/root"
        write("$root/a/b.txt", "")
        write("$base/other/secret", "")
        for (p in listOf("../other/secret", "a/../../other/secret", "$base/other/secret", "$base/root-sibling/x")) {
            val e = assertFailsWith<IoException>(p) { contain(root, p) }
            assertEquals(IoException.Kind.PermissionDenied, e.kind)
            assertTrue(e.message!!.contains(root))
        }
    }

    @Test fun containFollowsSymlinks() {
        val base = tmp("link")
        val root = "$base/root"
        write("$root/real/file.txt", "")
        write("$base/other/secret", "")
        symlink("$base/other", "$root/escape")
        symlink("$root/real", "$root/alias")
        assertFails { contain(root, "escape/secret") }
        assertFails { contain(root, "escape/new/file") }
        createDirAll("$base/other/inner")
        symlink("$base/other/inner", "$root/hop")
        assertFails { contain(root, "hop/../secret") }
        assertFails { contain(root, "hop/../made/up") }
        assertEquals("$root/real/file.txt", contain(root, "alias/file.txt"))
    }
}
