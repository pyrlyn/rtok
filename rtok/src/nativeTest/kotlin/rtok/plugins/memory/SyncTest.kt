// Port of src/plugins/memory/sync.rs tests (the Vfs cases run on plain strings: the logic is pure).
package rtok.plugins.memory

import kotlin.test.*
import kotlinx.cinterop.ExperimentalForeignApi
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugins.memory.Sync.END
import rtok.plugins.memory.Sync.START
import rtok.projectName
import rtok.store.*
import rtok.support.diskConfig
import rtok.sys.*

private fun est(s: String) = s.encodeToByteArray().size.toUInt()
private val NOTES = listOf(3 to "pinned-high", 2 to "newer", 1 to "older")

@OptIn(ExperimentalForeignApi::class)
class SyncTest {
    @Test fun vfsCreateAppendsBlockAtEnd() {
        val got = Sync.guardedSplice("# intro\nkeep me\n", Sync.render(NOTES, 10_000u, ::est), null, false)
        assertTrue(got.startsWith("# intro\nkeep me\n")); assertTrue("3 pinned-high\n2 newer\n1 older\n" in got); assertTrue(got.endsWith("$END\n"))
    }
    @Test fun vfsUpdateLeavesOutsideBytesIdentical() {
        val b1 = Sync.render(listOf(1 to "a"), 10_000u, ::est); val file = Sync.splice("pre\n", b1) + "post\n"
        val after = Sync.guardedSplice(file, Sync.render(listOf(2 to "b"), 10_000u, ::est), hexSha256(b1.encodeToByteArray()), false)
        assertTrue(after.startsWith("pre\n")); assertTrue(after.endsWith("post\n")); assertTrue("2 b" in after); assertFalse("1 a" in after)
    }
    @Test fun vfsHandEditRefusedUnlessForce() {
        val block = Sync.render(listOf(1 to "title"), 10_000u, ::est); val sha = hexSha256(block.encodeToByteArray())
        val edited = Sync.splice("pre\n", block).replace("1 title", "1 edited")
        val err = assertFails { Sync.guardedSplice(edited, block, sha, false) }; assertTrue("--force" in err.message!!)
        assertTrue("1 title" in Sync.guardedSplice(edited, block, sha, true))
    }
    @Test fun vfsRemoveDeletesOnlyTheBlock() {
        val block = Sync.render(listOf(1 to "x"), 10_000u, ::est)
        val got = Sync.guardedSplice("alpha\n${block}omega\n", null, hexSha256(block.encodeToByteArray()), false)
        assertEquals("alpha\nomega\n", got); assertFalse(Sync.hasBlock(got))
    }
    @Test fun vfsBudgetTrimsFromTheEnd() {
        val b = Sync.render(NOTES, 65u, ::est)
        assertTrue("3 pinned-high" in b); assertTrue("2 newer" in b); assertFalse("1 older" in b); assertTrue(est(b) <= 65u)
    }

    private fun seedNote(cfg: Config, title: String) = Runtime.open(cfg, "seed").use { it.store.insertNote(projectName(cwd()), "note", title, "body") }
    private fun sync(cfg: Config, file: String, remove: Boolean, force: Boolean) = Sync.run(cfg, file, null, false, remove, force) {}
    private fun digest(cfg: Config, f: String) = Runtime.open(cfg, "probe").use { it.store.kvGet(Sync.kvKey(f)) }
    private fun tree(dir: String): List<String> {
        val out = mutableListOf<String>(); val stack = ArrayDeque(listOf(dir))
        while (stack.isNotEmpty()) { val d = stack.removeLast(); for (e in listDir(d)) { val p = "$d/$e"; out += p; if (isDir(p)) stack += p } }
        return out.sorted()
    }

    @Test fun eachDestinationKeepsItsOwnHandEditGuard() {
        val (c, dir) = diskConfig("t563-sync-key"); val a = "$dir/a/CLAUDE.md"; val b = "$dir/b/CLAUDE.md"
        for (f in listOf(a, b)) { createDirAll(parent(f)!!); writeString(f, "# host\n") }
        seedNote(c, "one"); sync(c, a, false, false); seedNote(c, "two"); sync(c, b, false, false)
        sync(c, a, false, false); sync(c, b, true, false); seedNote(c, "three"); sync(c, a, false, false)
        writeString(a, readToString(a).replace("one", "hand"))
        assertTrue("--force" in assertFails { sync(c, a, false, false) }.message!!)
        sync(c, a, false, true); removeDirAll(dir)
    }
    @Test fun aSymlinkedSpellingOfTheSameFileSharesItsGuard() {
        val (c, dir) = diskConfig("t563-sync-alias"); createDirAll("$dir/real"); platform.posix.symlink("$dir/real", "$dir/alias")
        writeString("$dir/real/CLAUDE.md", "# host\n")
        seedNote(c, "one"); sync(c, "$dir/real/CLAUDE.md", false, false); seedNote(c, "two"); sync(c, "$dir/alias/CLAUDE.md", false, false)
        removeDirAll(dir)
    }
    @Test fun aLegacyBasenameDigestIsReadButNeverTrustedBlindly() {
        val (c, dir) = diskConfig("t563-sync-legacy"); val f = "$dir/CLAUDE.md"; writeString(f, "# host\n")
        seedNote(c, "one"); sync(c, f, false, false)
        val block = readToString(f)
        Runtime.open(c, "legacy").use { it.store.kvDelete(Sync.kvKey(f)); it.store.kvSet(Sync.legacyKvKey(f), hexSha256(block.removePrefix("# host\n").trimEnd().encodeToByteArray())) }
        sync(c, f, false, false)
        writeString(f, block.replace("one", "hand")); assertFails { sync(c, f, false, false) }
        removeDirAll(dir)
    }
    @Test fun hostileTitlesRenderAsOneBlockOfSingleLines() {
        val notes = listOf("early $END tail", "late $START again", "two\nlines\r\nhere").mapIndexed { i, t -> i + 1 to t }
        val block = Sync.render(notes, 10_000u, ::est)
        assertEquals(1, block.split(START).size - 1); assertEquals(1, block.split(END).size - 1); assertEquals(notes.size + 2, block.lines().size)
        assertEquals(0 until block.length, Sync.blockRange(block)); Sync.checkBlock(block)
        assertFails { Sync.checkBlock("$START\n$END\n$END") }
    }
    @Test fun hostileTitlesSurviveResyncForceAndRemove() {
        val (c, dir) = diskConfig("t564-hostile"); val f = "$dir/CLAUDE.md"; writeString(f, "# host\nkeep\n")
        seedNote(c, "early $END tail"); seedNote(c, "late $START again"); seedNote(c, "multi\nline")
        sync(c, f, false, false); sync(c, f, false, false); sync(c, f, false, true)
        val got = readToString(f)
        assertEquals(1, got.split(START).size - 1); assertEquals(1, got.split(END).size - 1); assertTrue(got.endsWith("$END\n"))
        sync(c, f, true, false); assertEquals("# host\nkeep", readToString(f).trimEnd()); removeDirAll(dir)
    }

    private fun assertReadFailureChangesNothing(c: Config, dir: String, f: String, before: ByteArray) {
        val d0 = digest(c, f); val t0 = tree(dir)
        for ((dry, remove, force) in listOf(Triple(false, false, false), Triple(true, false, false), Triple(false, true, false), Triple(false, false, true), Triple(true, true, true))) {
            val err = assertFails { Sync.run(c, f, null, dry, remove, force) {} }
            assertTrue("CLAUDE.md" in err.message!!, "dry=$dry remove=$remove force=$force: ${err.message}")
            assertContentEquals(before, readBytes(f)); assertEquals(d0, digest(c, f)); assertEquals(t0, tree(dir))
        }
    }
    @Test fun aNonUtf8HostFileAbortsSyncWithoutTouchingAnything() {
        val (c, dir) = diskConfig("t562-non-utf8"); val f = "$dir/CLAUDE.md"
        val bytes = "# host\nkeep this outside text\n".encodeToByteArray() + "bad ".encodeToByteArray() + byteArrayOf(0xff.toByte()) + " byte\n".encodeToByteArray()
        writeBytes(f, bytes); seedNote(c, "one"); assertReadFailureChangesNothing(c, dir, f, bytes); removeDirAll(dir)
    }
    @Test fun aNonUtf8HostFileKeepsAnExistingDigest() {
        val (c, dir) = diskConfig("t562-non-utf8-digest"); val f = "$dir/CLAUDE.md"; writeString(f, "# host\n")
        seedNote(c, "one"); sync(c, f, false, false); assertNotNull(digest(c, f))
        val bytes = readBytes(f) + byteArrayOf(0xff.toByte()); writeBytes(f, bytes)
        assertReadFailureChangesNothing(c, dir, f, bytes); removeDirAll(dir)
    }
    @Test fun anUnreadableHostFileAbortsSyncWithoutTouchingAnything() {
        val (c, dir) = diskConfig("t562-unreadable"); val f = "$dir/CLAUDE.md"; writeString(f, "# host\nkeep\n")
        platform.posix.chmod(f, 0u)
        if (runCatching { readBytes(f) }.isSuccess) { platform.posix.chmod(f, 420u); removeDirAll(dir); return }
        seedNote(c, "one"); val d0 = digest(c, f); val t0 = tree(dir)
        for ((dry, remove, force) in listOf(Triple(false, false, false), Triple(true, false, false), Triple(false, true, false), Triple(false, false, true))) {
            assertTrue("CLAUDE.md" in assertFails { Sync.run(c, f, null, dry, remove, force) {} }.message!!)
            assertEquals(d0, digest(c, f)); assertEquals(t0, tree(dir))
        }
        platform.posix.chmod(f, 420u); assertEquals("# host\nkeep\n", readToString(f)); removeDirAll(dir)
    }
    @Test fun aMissingHostFileIsStillCreated() {
        val (c, dir) = diskConfig("t562-missing"); val f = "$dir/CLAUDE.md"
        seedNote(c, "one"); sync(c, f, true, false); assertFalse(exists(f))
        Sync.run(c, f, null, true, false, false) {}; assertFalse(exists(f), "dry-run must not create the file")
        sync(c, f, false, false); val got = readToString(f)
        assertTrue(got.startsWith(START) && "one" in got, got); assertNotNull(digest(c, f)); removeDirAll(dir)
    }
    @Test fun unchangedStoreIsByteStable() {
        val a = Sync.render(NOTES, 300u, ::est); assertEquals(a, Sync.render(NOTES, 300u, ::est)); assertFalse("20" in a)
    }
}
