package rtok.sys

import kotlin.test.*

class WalkTest {
    private fun tree(): String {
        val root = "${tempDir()}/rtok-walk-${pid()}"
        removeDirAll(root)
        for (f in listOf(".git/HEAD", ".gitignore", "a.txt", "out.log", "keep.log", "sub/b.txt", "sub/.gitignore", "sub/gen/c.txt", "target/d.txt")) {
            createDirAll(parent(join(root, f))!!); writeString(join(root, f), "x\n")
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
}
