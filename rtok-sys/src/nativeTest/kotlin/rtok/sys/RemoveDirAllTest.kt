@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.convert
import platform.posix.chmod
import platform.posix.geteuid
import kotlin.test.*

private const val READ_ONLY_DIR = 0x16d // 0o555

class RemoveDirAllTest {
    /** Runs [block] with the working directory at [dir], then restores it. */
    private fun inDir(
        dir: String,
        block: () -> Unit,
    ) {
        val back = cwd()
        setCwd(dir)
        try {
            block()
        } finally {
            setCwd(back)
        }
    }

    @Test fun removeDirAllRemovesTheTreeButNotWhatALinkInsidePointsAt() {
        val root = scratch()
        val outside = scratch()
        try {
            writeString(join(outside, "keep.txt"), "keep")
            val tree = join(root, "tree")
            createDirAll(join(tree, "a/b"))
            writeString(join(tree, "a/b/f.txt"), "x")
            symlink(outside, join(tree, "a/link"))
            removeDirAll(tree)
            assertNull(entryKind(tree))
            assertEquals("keep", readToString(join(outside, "keep.txt")))
        } finally {
            removeDirAll(root)
            removeDirAll(outside)
        }
    }

    @Test fun removeDirAllOfALinkRemovesTheLinkEvenWithATrailingSlash() {
        val root = scratch()
        try {
            val target = join(root, "target")
            createDirAll(target)
            writeString(join(target, "keep.txt"), "keep")
            val link = join(root, "link")
            symlink(target, link)
            removeDirAll("$link/")
            assertNull(entryKind(link))
            assertEquals("keep", readToString(join(target, "keep.txt")))
        } finally {
            removeDirAll(root)
        }
    }

    @Test fun removeDirAllTakesARelativeNameThatLooksLikeAnOption() {
        val root = scratch()
        try {
            inDir(root) {
                createDirAll("-rf/sub")
                writeString("-rf/sub/f", "x")
                removeDirAll("-rf")
            }
            assertNull(entryKind(join(root, "-rf")))
            assertTrue(isDir(root))
        } finally {
            removeDirAll(root)
        }
    }

    @Test fun removeDirAllRefusesDotDirectories() {
        val root = scratch()
        try {
            createDirAll(join(root, "sub"))
            writeString(join(root, "keep"), "x")
            inDir(root) {
                assertFailsWith<IoException> { removeDirAll(".") }
                assertFailsWith<IoException> { removeDirAll("sub/..") }
            }
            assertFailsWith<IoException> { removeDirAll("$root/sub/../") }
            assertTrue(exists(join(root, "keep")))
        } finally {
            removeDirAll(root)
        }
    }

    @Test fun removeDirAllOfAMissingPathIsANoOpButRemoveTreeFails() {
        val root = scratch()
        try {
            removeDirAll(join(root, "missing"))
            assertFailsWith<IoException> { removeTree(join(root, "missing")) }
        } finally {
            removeDirAll(root)
        }
    }

    @Test fun anEntryThatCannotBeRemovedFailsTheRemoval() {
        // Root ignores directory permissions, so nothing would fail.
        if (geteuid() == 0u) return
        val root = scratch()
        val locked = join(root, "locked")
        try {
            createDirAll(locked)
            writeString(join(locked, "f"), "x")
            chmod(locked, READ_ONLY_DIR.convert())
            val e = assertFailsWith<IoException> { removeDirAll(root) }
            assertEquals(IoException.Kind.PermissionDenied, e.kind)
            assertTrue(exists(join(locked, "f")))
        } finally {
            chmod(locked, PRIVATE_DIR.convert())
            removeDirAll(root)
        }
    }
}
