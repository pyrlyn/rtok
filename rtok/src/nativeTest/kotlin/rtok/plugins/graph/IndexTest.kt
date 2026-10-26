package rtok.plugins.graph

import kotlin.test.*
import rtok.config.Graph
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.*
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class IndexTest {
    private fun put(dir: String, rel: String, text: String) { parent("$dir/$rel")?.let(::createDirAll); writeString("$dir/$rel", text) }
    private fun k(dir: String) = Index.canon(dir)
    private fun Runtime.has(dir: String, name: String) = store.hasSymbolDef(k(dir), name)
    private fun index(rt: Runtime, dir: String, dry: Boolean = false, cfg: Graph = Graph()) = Index.run(Ctx(rt), dir, dry, cfg)
    private fun setMtime(path: String, plusSeconds: Long) {
        check(platform.posix.system("touch -t $(date -v+${plusSeconds}S +%Y%m%d%H%M.%S) '$path'") == 0)
    }

    /** T356: `/` and home are refused by every entry point; a project directory is indexed. */
    @Test fun indexRefusesHomeAndFilesystemRootButAcceptsAProject() = withRuntime("t356-refuse") { rt ->
        val cx = Ctx(rt)
        for (root in listOfNotNull("/", homeDir())) {
            assertTrue(assertFails { Index.run(cx, root, false) }.message!!.contains("no project root"), root)
            assertFails { Index.runChanged(cx, root, emptySet()) }
            assertFails { Index.ensure(cx, root) }
            assertEquals(0, rt.store.symbolCount(k(root)))
        }
        val dir = tmpDir("t356"); put(dir, "a.rs", "pub fn alpha() {}\n")
        assertEquals(1, index(rt, dir).indexed); removeDirAll(dir)
    }

    /** Copied rows keep `file_sha`, so a worktree whose mtimes differ still skips the parse. */
    @Test fun copySymbolRowsSkipsIdenticalBlobs() = withRuntime("copy-symbols") { rt ->
        val dir = tmpDir("copy-symbols"); val a = "$dir/a"; val b = "$dir/b"
        put(a, "lib.rs", "fn kept() {}\n"); put(b, "lib.rs", "fn kept() {}\n")
        assertTrue(index(rt, a).indexed >= 1)
        assertTrue(rt.store.copySymbolRows(k(a), k(b), emptyList()) >= 1)
        setMtime("$b/lib.rs", 5)
        val r = index(rt, b); assertEquals(0, r.indexed, "$r"); assertTrue(r.skipped >= 1, "$r")
        assertEquals(rt.store.symbolDefs(k(a), "kept").map { it.toString() }.size, rt.store.symbolDefs(k(b), "kept").size)
        put(b, "lib.rs", "fn kept() { let _ = 1; }\n")
        assertEquals(1, index(rt, b).indexed); removeDirAll(dir)
    }

    /** T8.3: two repos in one store do not evict or answer for each other. */
    @Test fun twoRootsDoNotEvictEachOther() = withRuntime("roots") { rt ->
        val dir = tmpDir("roots"); val a = "$dir/a"; val b = "$dir/b"
        put(a, "src/main.rs", "fn main() {}\nfn alpha() {}\n"); put(b, "src/main.rs", "fn main() {}\nfn beta() {}\n")
        index(rt, a); val aRows = rt.store.symbolCount(k(a)); index(rt, b)
        assertEquals(aRows, rt.store.symbolCount(k(a)), "indexing b evicted a")
        assertEquals(1, rt.store.symbolDefs(k(a), "main").size)
        assertTrue(rt.has(a, "alpha")); assertFalse(rt.has(a, "beta"), "b answered for a")
        rt.store.markSymbolsStale(canon("$a/src/main.rs"))
        assertFalse(rt.has(a, "alpha")); assertTrue(rt.has(b, "beta"), "a's stale mark dropped b's src/main.rs")
        removeDirAll(dir)
    }

    private fun threeFiles(tag: String) = tmpDir(tag).also { d -> repeat(3) { put(d, "f$it.rs", "fn f$it() {}\n") } }

    /** T35.5: a stale extractor fingerprint drops the root and indexes cold. */
    @Test fun reindexesWhenExtractorFingerprintMismatches() = withRuntime("fp") { rt ->
        val dir = threeFiles("fp")
        assertEquals(3, index(rt, dir).read)
        val warm = index(rt, dir); assertTrue(warm.skipped > 0 || warm.read == 0)
        rt.store.setExtractorFingerprint(k(dir), "deadbeef")
        val re = index(rt, dir); assertEquals(3, re.read); assertEquals(3, re.indexed)
        assertEquals(0, index(rt, dir).read, "third run is warm again"); removeDirAll(dir)
    }

    /** T542: remapping a suffix re-extracts an unchanged file, touched or not. */
    @Test fun remappingAnExtensionDropsTheOldGrammarsRows() {
        for (touch in listOf(false, true)) withRuntime("t542-remap") { rt ->
            val dir = tmpDir("t542-remap"); put(dir, "sample.tpl", "fn old() {}\n")
            val rust = Graph(extensions = mapOf("tpl" to "rust")); val py = Graph(extensions = mapOf("tpl" to "py"))
            index(rt, dir, cfg = rust); assertTrue(rt.has(dir, "old"))
            assertEquals(0, index(rt, dir, cfg = rust).read, "warm")
            if (touch) setMtime("$dir/sample.tpl", 5)
            index(rt, dir, cfg = py); assertFalse(rt.has(dir, "old"), "touch=$touch")
            assertEquals(0, index(rt, dir, cfg = py).read); removeDirAll(dir)
        }
    }

    /** T542: the fingerprint follows the mapping's content, not its order. */
    @Test fun theFingerprintFollowsTheMappingContent() {
        val ab = linkedMapOf("a" to "rust", "b" to "py"); val ba = linkedMapOf("b" to "py", "a" to "rust")
        assertEquals(Index.extractorFingerprint(ab), Index.extractorFingerprint(ba))
        assertNotEquals(Index.extractorFingerprint(ab), Index.extractorFingerprint(mapOf("a" to "rust", "b" to "go")))
        assertNotEquals(Index.extractorFingerprint(ab), Index.extractorFingerprint(emptyMap()))
    }

    /** T542: an event after a remap reparses the file instead of matching its old sha. */
    @Test fun anEventAfterARemapReparsesTheFile() = withRuntime("t542-event") { rt ->
        val dir = tmpDir("t542-event"); put(dir, "sample.tpl", "fn old() {}\n")
        index(rt, dir, cfg = Graph(extensions = mapOf("tpl" to "rust")))
        Index.runChanged(Ctx(rt), dir, setOf("$dir/sample.tpl"), Graph(extensions = mapOf("tpl" to "py")))
        assertFalse(rt.has(dir, "old")); removeDirAll(dir)
    }

    /** T543: a dry run over a stale fingerprint predicts the cold reindex and writes nothing. */
    @Test fun aStaleFingerprintDryRunPredictsTheColdReindex() = withRuntime("t543-dry") { rt ->
        val dir = threeFiles("t543"); index(rt, dir)
        assertEquals(0, index(rt, dir, dry = true).indexed, "warm dry run")
        rt.store.setExtractorFingerprint(k(dir), "deadbeef")
        fun seen() = listOf(rt.store.symbolCount(k(dir)), rt.store.symbolIndexedAt(k(dir)), rt.store.extractorFingerprint(k(dir)), rt.store.fileRankGet(k(dir)))
        val before = seen(); val predicted = index(rt, dir, dry = true)
        assertEquals(before, seen(), "a dry run wrote")
        val actual = index(rt, dir)
        assertEquals(3, predicted.indexed)
        assertEquals(Triple(predicted.indexed, predicted.inserted, predicted.read), Triple(actual.indexed, actual.inserted, actual.read))
        removeDirAll(dir)
    }

    /** T8.4: a warm run opens no file; rewriting identical bytes costs one read and no rows. */
    @Test fun warmRunReadsNothingAndTouchInsertsZero() = withRuntime("stat") { rt ->
        val dir = tmpDir("stat"); repeat(20) { put(dir, "f$it.rs", "fn f$it() {}\n") }
        assertEquals(20, index(rt, dir).read)
        val warm = index(rt, dir); assertEquals(0, warm.read); assertEquals(20, warm.skipped); assertEquals(0, warm.inserted)
        platform.posix.usleep(10_000u); put(dir, "f0.rs", "fn f0() {}\n")
        val touched = index(rt, dir); assertEquals(1, touched.read); assertEquals(0, touched.inserted)
        assertEquals(0, index(rt, dir).read, "new stat was recorded"); removeDirAll(dir)
    }

    /** T36.16: a latin-1 file records an empty-sha sentinel so a warm run opens nothing. */
    @Test fun latin1FileIsNotRereadOnWarmIndex() = withRuntime("latin1") { rt ->
        val dir = tmpDir("latin1"); writeBytes("$dir/bad.rs", "fn latin() {}\n".encodeToByteArray() + byteArrayOf(0xE9.toByte()))
        index(rt, dir); assertEquals(0, index(rt, dir).read)
        assertEquals(Index.EMPTY_SHA, rt.store.symbolStat(k(dir), "bad.rs")?.first); removeDirAll(dir)
    }

    /** T654: a file unreadable at index time is read once access returns. */
    @Test fun aFileUnreadableAtIndexTimeIsReadOnceAccessReturns() = withRuntime("t654") { rt ->
        val dir = tmpDir("t654"); val file = "$dir/locked.rs"; put(dir, "locked.rs", "fn hidden_until_readable() {}\n")
        check(platform.posix.system("chmod 000 '$file'") == 0)
        val unreadable = runCatching { readBytes(file) }.isFailure
        index(rt, dir); val before = fileStat(file)
        check(platform.posix.system("chmod 644 '$file'") == 0); assertEquals(before, fileStat(file), "chmod moved mtime or size")
        if (unreadable) assertFalse(rt.has(dir, "hidden_until_readable"))
        assertEquals(1, index(rt, dir).indexed); assertTrue(rt.has(dir, "hidden_until_readable"))
        val warm = index(rt, dir); assertEquals(0 to 1, warm.read to warm.skipped, "$warm"); removeDirAll(dir)
    }

    /** T276.6: the bar spans the files that pass the stat gate. */
    @Test fun theProgressBarSpansTheFilesTheRunReads() = withRuntime("progress") { rt ->
        val dir = tmpDir("progress"); repeat(7) { put(dir, "f$it.rs", "fn f$it() {}\n") }; put(dir, "notes.md", "not a source file\n")
        val spans = ArrayList<Int>()
        fun run() = Index.runWith(Ctx(rt), dir, false, Graph()) { spans += it; IndexProgress.Hidden }
        assertEquals(7, run().indexed)
        platform.posix.usleep(10_000u); put(dir, "f0.rs", "fn changed() {}\n")
        assertEquals(1, run().indexed); assertEquals(listOf(7, 1), spans); removeDirAll(dir)
    }

    /** Batching: more files than one write transaction still land, in order, with every row. */
    @Test fun aColdRunOverMoreThanOneBatchIndexesEveryFile() = withRuntime("batch") { rt ->
        val n = Index.SYMBOL_BATCH_FILES + 17
        val dir = tmpDir("batch"); repeat(n) { put(dir, "f$it.rs", "fn f$it() {}\n") }
        val r = index(rt, dir); assertEquals(n, r.indexed); assertEquals(n, r.read)
        assertTrue((0 until n).all { rt.has(dir, "f$it") }); removeDirAll(dir)
    }

    /** T474: definitions carry span, content hash, signature and the doc block above them. */
    @Test fun definitionRowsCarrySpanSignatureAndDoc() {
        val src = "/// Adds.\n/// Twice.\npub fn add(a: i32) -> i32 {\n    a\n}\nfn b() { add(1); }\n"
        val rows = Index.rowsOf("x.rs", src, emptyMap())!!
        val add = rows.single { it.isDef && it.name == "add" }
        assertEquals("pub fn add(a: i32) -> i32 {", add.signature)
        assertEquals("/// Adds.\n/// Twice.", add.doc)
        assertEquals(src.indexOf("pub fn"), add.startByte.toInt()); assertEquals(64, add.contentHash.length)
        assertEquals("b", rows.single { !it.isDef && it.name == "add" }.scope)
        assertEquals("", Index.docAbove(src, 1))
        assertTrue(Index.docAbove("/// " + "é".repeat(400) + "\nfn x() {}\n", 2).encodeToByteArray().size <= Index.DOC_CAP)
    }
}
