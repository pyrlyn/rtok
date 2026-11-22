@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.convert
import kotlin.test.*

private const val MODE_755 = 0x1ed

class WalkTest {
    private fun tree(): String {
        val root = "${tempDir()}/rtok-walk-${pid()}"
        removeDirAll(root)
        for (f in listOf(".git/HEAD", ".gitignore", "a.txt", "out.log", "keep.log", "sub/b.txt", "sub/.gitignore", "sub/gen/c.txt", "target/d.txt")) {
            createDirAll(parent(join(root, f))!!)
            writeString(join(root, f), "x\n")
        }
        writeString(join(root, ".gitignore"), "*.log\n!keep.log\ntarget/\n")
        writeString(join(root, "sub/.gitignore"), "gen/\n")
        return root
    }

    @Test fun honoursNestedGitignoresAndSkipsDotGit() {
        val root = tree()
        val rel = walk(root).map { it.path.removePrefix(root).trimStart('/') }.toList()
        assertEquals(listOf("", ".gitignore", "a.txt", "keep.log", "sub", "sub/.gitignore", "sub/b.txt"), rel)
        removeDirAll(root)
    }

    @Test fun maxDepthAndFilterPrune() {
        val root = tree()
        assertEquals(listOf("", ".gitignore", "a.txt", "keep.log", "sub"), walk(root, maxDepth = 1).map { it.path.removePrefix(root).trimStart('/') }.toList())
        assertFalse(walk(root) { it.path.endsWith("/sub") == false }.any { "sub" in it.path })
        removeDirAll(root)
    }

    /** `follow_links(false)`: a link loop ends the walk at the link, and a linked file is not a file to read. */
    @Test fun symlinksAreYieldedButNeverFollowed() {
        val root = "${tempDir()}/rtok-walk-links-${pid()}"
        removeDirAll(root)
        createDirAll("$root/sub")
        writeString("$root/sub/a.txt", "a")
        writeString("$root/outside.txt", "secret")
        platform.posix.symlink("..", "$root/sub/up")
        platform.posix.symlink(".", "$root/sub/self")
        platform.posix.symlink("../outside.txt", "$root/sub/leak.txt")
        val seen = walk("$root/sub").toList()
        assertEquals(listOf("", "a.txt", "leak.txt", "self", "up"), seen.map { it.path.removePrefix("$root/sub").trimStart('/') })
        assertEquals(listOf("a.txt"), seen.filter { it.isFile }.map { fileName(it.path) })
        assertEquals(1, seen.count { it.isDir })
        removeDirAll(root)
    }

    /** One directory that cannot be listed costs its own subtree, not the whole walk. */
    @Test fun anUnreadableDirectoryIsSkipped() {
        if (platform.posix.geteuid() == 0u) return // root reads through mode 000
        val root = "${tempDir()}/rtok-walk-denied-${pid()}"
        removeDirAll(root)
        createDirAll("$root/locked")
        writeString("$root/locked/hidden.txt", "x")
        writeString("$root/ok.txt", "x")
        platform.posix.chmod("$root/locked", 0u)
        try {
            assertEquals(listOf("", "locked", "ok.txt"), walk(root).map { it.path.removePrefix(root).trimStart('/') }.toList())
        } finally {
            // `mode_t` is 16 bits on Darwin and 32 on Linux: `convert` picks the width of the target.
            platform.posix.chmod("$root/locked", MODE_755.convert()) // so the cleanup can enter it
            removeDirAll(root)
        }
    }
}
