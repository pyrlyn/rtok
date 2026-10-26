// Port of src/plugins/memory/files.rs tests.
package rtok.plugins.memory

import kotlin.test.*
import kotlinx.cinterop.ExperimentalForeignApi
import rtok.plugin.sdk.NoteHit
import rtok.support.tmpDir
import rtok.support.worktreeLayout
import rtok.sys.*

private fun root(tag: String): String = tmpDir("t374-files-$tag").also {
    createDirAll("$it/src/proxy"); writeString("$it/src/proxy/semantic_cache.rs", "x"); writeString("$it/Cargo.toml", "x")
}

@OptIn(ExperimentalForeignApi::class)
class FilesTest {
    @Test fun onlyExistingFilesUnderTheRootCount() {
        val r = root("mentioned")
        val text = "see `src/proxy/semantic_cache.rs:120-130`, (Cargo.toml). Not src/missing.rs, nor ../etc/passwd, nor /etc/hosts, nor e.g. this."
        assertEquals(listOf("src/proxy/semantic_cache.rs", "Cargo.toml"), mentioned(r, r, text))
        assertEquals(listOf("src/proxy/semantic_cache.rs"), mentioned(r, r, "$r/src/proxy/semantic_cache.rs#L9"))
        removeDirAll(r)
    }

    @Test fun leadingDotsNameHiddenAndDotRelativeFiles() {
        val r = root("dots")
        for (f in listOf(".env", "src/lib.rs", ".config/file", "config/file")) { parent("$r/$f")?.let(::createDirAll); writeString("$r/$f", "x") }
        fun got(t: String) = mentioned(r, r, t)
        assertEquals(listOf(".env"), got("set `.env`."))
        assertEquals(listOf("src/lib.rs"), got("see ./src/lib.rs:12-30, then"))
        assertEquals(listOf(".config/file"), got("in .config/file."))
        assertEquals(listOf("config/file"), got("in config/file#L3!"))
        assertEquals(listOf(".env", ".config/file"), got("[.env](.env), (.config/file)"))
        removeFile("$r/config/file"); assertEquals(listOf(".config/file"), got(".config/file"))
        removeFile("$r/.config/file"); assertTrue(got(".config/file").isEmpty())
        assertTrue(got("../.env and .../x").isEmpty())
        removeDirAll(r)
    }

    @Test fun climbingAndForeignPathsAreRefused() {
        val base = tempDir(); val r = "$base/p"
        assertNull(relative(r, r, "src/../../x"))
        assertNull(relative(r, r, "$base/other/a.rs"))
        assertEquals("a/b.rs", relative(r, r, "$r/a/b.rs"))
        assertEquals("a.rs", relative(r, r, "./a.rs"))
        assertNull(relative(r, r, r))
    }

    @Test fun projectRootIsTheLinkedWorktreeCheckout() {
        val dir = tmpDir("t660-memory-root"); val (main, wt) = worktreeLayout(dir)
        createDirAll("$wt/src")
        assertEquals(wt, projectRoot("$wt/src")); assertEquals(main, projectRoot(main)); assertEquals(dir, projectRoot(dir))
        removeDirAll(dir)
    }

    @Test fun aSymlinkOutOfTheRootIsRefused() {
        val r = root("symlink"); val outside = "$r-outside"
        createDirAll(outside); writeString("$outside/secret.txt", "x")
        platform.posix.symlink(outside, "$r/escape"); platform.posix.symlink("$r/src", "$r/inside")
        assertNull(relative(r, r, "escape/secret.txt"))
        assertNull(relative(r, r, "$r/escape/secret.txt"))
        assertTrue(mentioned(r, r, "see escape/secret.txt").isEmpty())
        assertEquals("inside/proxy/semantic_cache.rs", relative(r, r, "inside/proxy/semantic_cache.rs"))
        removeDirAll(r); removeDirAll(outside)
    }

    @Test fun aLongBodyIsStattedUpToTheCandidateCap() {
        val r = root("cap")
        val filler = (0 until MAX_CANDIDATES).joinToString("") { "no/$it.rs " }
        assertTrue(mentioned(r, r, "$filler Cargo.toml").isEmpty(), "past the cap nothing is statted")
        removeDirAll(r)
    }

    @Test fun keyPathReadsBothKeyShapes() {
        assertEquals("src/a.rs", keyPath("src/a.rs\tfull\t")); assertEquals("src/a.rs", keyPath("read\tsrc/a.rs\t1:9"))
        assertNull(keyPath("bash")); assertNull(keyPath("read"))
    }

    private fun hit(id: Int) = NoteHit(id, "n$id", "")
    @Test fun boostPrefersTheLinkedNoteAndLeavesPlainTextHitsAlone() {
        val text = listOf(hit(1), hit(2), hit(3))
        assertEquals(2, boost(text, emptyList(), 2).size)
        assertEquals(listOf(2, 1, 3), boost(text, listOf(hit(2)), 3).map { it.id })
    }
}
