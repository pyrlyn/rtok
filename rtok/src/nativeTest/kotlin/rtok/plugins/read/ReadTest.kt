package rtok.plugins.read

import kotlin.test.*
import rtok.Expand
import rtok.MIN_PREFIX
import rtok.store.*
import rtok.support.Vfs
import rtok.sys.*

class ReadTest {
    @Test fun threeLinesAreNumbered() {
        val (rt, dir) = readRt("three")
        rt.use { writeString("$dir/a.txt", "alpha\nbeta\ngamma\n"); assertEquals("1:alpha\n2:beta\n3:gamma", read(it.ctx(), "$dir/a.txt", "full", null)) }
    }

    @Test fun rangeAppliesToFullAndLinesAndRejectsJunk() {
        val (rt, dir) = readRt("range")
        rt.use { r -> val p = "$dir/r.txt"; writeString(p, "a\nb\nc\nd\n"); val cx = r.ctx()
            assertEquals("2:b\n3:c", read(cx, p, "full", "2-3")); assertEquals("3:c\n4:d", read(cx, p, "lines", "3"))
            assertEquals("1:a", read(cx, p, "lines", "-1"))
            assertFails { read(cx, p, "lines", "x-y") }; assertFails { read(cx, p, "full", "3-2") }
        }
    }

    @Test fun fileErrorsNameThePath() {
        val (rt, dir) = readRt("file-errors")
        rt.use { r -> val canon = canonicalize(dir)!!; val cx = r.ctx()
            val missing = "$canon/nope.rs"
            val e1 = assertFailsWith<ReadException> { read(cx, missing, "full", null) }.message!!
            assertTrue(e1.endsWith(": $missing") && "os error" !in e1, e1)
            createDirAll("$canon/sub")
            val e2 = assertFailsWith<ReadException> { read(cx, "$canon/sub", "map", null) }.message!!
            assertTrue(e2.endsWith(": $canon/sub") && "os error" !in e2, e2)
        }
    }

    @Test fun hundredKbIsCappedWithArchiveId() {
        val (rt, dir) = readRt("big")
        rt.use { r -> val max = r.config.plugins.read.maxChars.toInt(); val blob = "x".repeat(100 * 1024)
            writeString("$dir/big.txt", blob)
            val out = read(r.ctx(), "$dir/big.txt", "full", null)
            assertTrue("archived" in out); assertTrue(out.length <= max); assertTrue(out.length < blob.length)
        }
    }

    /** T535: a capped fresh `full` or `lines` read records one `read_cap` row; one that fits does not. */
    @Test fun aCappedFreshReadRecordsOneRow() {
        for ((mode, range) in listOf("full" to null, "lines" to "1-3000")) {
            val (rt, dir) = readRt("cap-row")
            rt.use { r ->
                writeString("$dir/big.txt", (0 until 3000).joinToString("") { "line$it\n" })
                val out = read(r.ctx(), "$dir/big.txt", mode, range)
                val rows = r.store.listMeasurements("read")
                assertEquals(1, rows.size, "$rows"); val row = rows[0]
                assertEquals("read_cap", row.kind); assertEquals(out.encodeToByteArray().size.toLong(), row.afterBytes)
                assertTrue(row.beforeBytes > row.afterBytes)
                val shown = archiveId(out); assertEquals(MIN_PREFIX, shown.length); assertTrue(row.refId!!.startsWith(shown))
                assertEquals(row.beforeBytes, Expand.fetch(r, shown)!!.size.toLong())
                writeString("$dir/small.txt", "a\nb\n"); read(r.ctx(), "$dir/small.txt", "full", null)
                assertEquals(1, r.store.measurementCount("read"))
            }
        }
    }

    @Test fun capIncludesMarkerInMaxChars() {
        for (max in listOf(500u, 200u)) {
            val (rt, dir) = readRt("cap-marker") { it.copy(plugins = it.plugins.copy(read = it.plugins.read.copy(maxChars = max))) }
            rt.use { r -> val blob = "abcdefghij".repeat(200); writeString("$dir/cap.txt", blob)
                val out = read(r.ctx(), "$dir/cap.txt", "full", null)
                assertTrue(out.length <= max.toInt(), "${out.length}")
                val shown = archiveId(out); assertEquals(MIN_PREFIX, shown.length)
                assertEquals("1:$blob", r.store.getArchive(Expand.resolveId(r, shown), null)!!.decodeToString())
            }
        }
    }

    @Test fun symlinkEscapeIsErr() {
        val (rt, dir) = readRt("symlink")
        rt.use {
            createDirAll("$dir/cwd"); val outside = "${parent(dir)}/rtok-read-symlink-out-${pid()}"
            writeString(outside, "secret\n"); symlink(outside, "$dir/cwd/escape")
            val err = assertFailsWith<ReadException> { resolve("$dir/cwd", "escape", emptyList()) }.message!!
            removeFile(outside); assertTrue("outside cwd" in err, err)
        }
    }

    /** T590: `..` after a symlink is the parent of the link's target, as a native lookup reads it. */
    @Test fun parentSegmentsAfterASymlinkFollowTheTarget() {
        val (rt, dir) = readRt("t590-parent")
        rt.use { r ->
            val root = canon(dir); createDirAll("$root/a/nested")
            writeString("$root/a/target.txt", "from a\n"); writeString("$root/target.txt", "from root\n"); writeString("$root/only-in-root.txt", "root only\n")
            symlink("$root/a/nested", "$root/link")
            val readAt = { p: String -> read(r.ctx(), "$root/$p", "full", null) }
            assertEquals("from a\n", readToString(resolve(root, "link/../target.txt", emptyList())))
            assertEquals("1:from a", readAt("link/../target.txt"))
            assertTrue(readAt("a/nested/../target.txt").startsWith(ReadCache.DEDUP_PREFIX))
            assertEquals("1:from root", readAt("target.txt"))
            assertFails { readAt("link/../only-in-root.txt") }
            assertTrue(pathStartsWith(resolve(root, "link/../only-in-root.txt", emptyList()), "$root/a"))
            val outside = "${parent(root)}/rtok-t590-outside-${pid()}"; createDirAll("$outside/deep")
            writeString("$outside/secret.txt", "secret\n"); writeString("$root/secret.txt", "inside\n")
            symlink("$outside/deep", "$root/out")
            val err = assertFailsWith<ReadException> { resolve(root, "out/../secret.txt", emptyList()) }
            removeDirAll(outside); assertTrue("outside cwd" in err.message!!)
        }
    }

    @Test fun walkRootOkRejectsFilesystemRootAndAcceptsAProjectDir() {
        assertFails { walkRootOk("/") }
        val dir = rtok.support.tmpDir("walk-root-ok"); walkRootOk(dir); removeDirAll(dir)
    }

    @Test fun normalizeAbsoluteUnixPathStillRoots(): Unit = assertEquals("/tmp/b", normalize("/unused", "/tmp/a/../b"))

    @Test fun dotDotEscapeIsErr() {
        val (rt, _) = readRt("dotdot"); rt.use { assertFails { read(it.ctx(), "../".repeat(32) + "etc/passwd", "full", null) } }
    }

    /** T529: a refusal names the way out and keeps the prefix callers match on. */
    @Test fun aRefusalPointsToTheHostsOwnFileTool() {
        val err = assertFailsWith<ReadException> { resolve("/nonexistent-cwd", "/etc/hosts", emptyList()) }.message!!
        assertTrue(err.startsWith("path outside cwd: /etc/hosts") && "host's own file tool" in err, err)
    }

    @Test fun emptyAllowRootGrantsNothing() { assertFails { resolve("/nonexistent-cwd", "/etc/passwd", listOf("")) } }

    @Test fun resolveAllowsMissingPathUnderACanonicalRoot() {
        val base = rtok.support.tmpDir("read-case"); createDirAll("$base/Proj")
        assertTrue(resolve("$base/Proj", "missing.txt", emptyList()).endsWith("missing.txt"))
        removeDirAll(base)
    }

    private fun numberedFromVfs(raw: String, range: String?): String {
        val lines = raw.rustLines(); val (a, b) = if (range != null) Expand.parseRange(range, lines.size) else 1 to lines.size
        return Expand.sliceLines(lines, a, b).withIndex().joinToString("\n") { (i, l) -> "${a + i}:$l" }
    }

    @Test fun threeLinesNumberedFromVfs() { val v = Vfs(); v.write("a.txt", "alpha\nbeta\ngamma\n"); assertEquals("1:alpha\n2:beta\n3:gamma", numberedFromVfs(v.readToString("a.txt"), null)) }

    @Test fun rangeFromVfsMatchesExpandGrammar() {
        val v = Vfs(); v.write("r.txt", "a\nb\nc\nd\n"); val raw = v.readToString("r.txt")
        assertEquals("2:b\n3:c", numberedFromVfs(raw, "2-3")); assertEquals("3:c\n4:d", numberedFromVfs(raw, "3")); assertEquals("1:a", numberedFromVfs(raw, "-1"))
    }

    @Test fun rangeFromVfsRejectsJunkLikeDiskTwin() { assertFails { Expand.parseRange("x-y", 4) }; assertFails { Expand.parseRange("3-2", 4) } }

    @Test fun emptyFileFromVfsNumbersNothing() { val v = Vfs(); v.write("empty.txt", ""); assertEquals("", numberedFromVfs(v.readToString("empty.txt"), null)) }

    @Test fun spacedPathContentFromVfsNumbersLines() { val v = Vfs(); v.write("My Docs/notes.txt", "one\ntwo\n"); assertEquals("1:one\n2:two", numberedFromVfs(v.readToString("My Docs/notes.txt"), "1-2")) }

    @Test fun threeLinesAreNumberedViaReadFsVfs(): Unit = vfsRt("three-vfs").use { r ->
        val v = Vfs(); v.write("ws/a.txt", "alpha\nbeta\ngamma\n")
        assertEquals("1:alpha\n2:beta\n3:gamma", readWith(r.ctx(), v, "ws", "a.txt", "full", null))
    }

    @Test fun rangeAppliesViaReadFsVfs(): Unit = vfsRt("range-vfs").use { r ->
        val v = Vfs(); v.write("ws/r.txt", "a\nb\nc\nd\n"); val cx = r.ctx()
        assertEquals("2:b\n3:c", readWith(cx, v, "ws", "r.txt", "full", "2-3")); assertEquals("3:c\n4:d", readWith(cx, v, "ws", "r.txt", "lines", "3"))
        assertEquals("1:a", readWith(cx, v, "ws", "r.txt", "lines", "-1"))
        assertFails { readWith(cx, v, "ws", "r.txt", "lines", "x-y") }; assertFails { readWith(cx, v, "ws", "r.txt", "full", "3-2") }
    }

    @Test fun hundredKbIsCappedViaReadFsVfs(): Unit = vfsRt("big-vfs").use { r ->
        val v = Vfs(); val blob = "x".repeat(100 * 1024); v.write("ws/big.txt", blob)
        val out = readWith(r.ctx(), v, "ws", "big.txt", "full", null)
        assertTrue("archived" in out && out.length <= r.config.plugins.read.maxChars.toInt() && out.length < blob.length)
    }

    @Test fun capIncludesMarkerViaReadFsVfs() {
        for (max in listOf(500u, 200u)) vfsRt("cap-marker-vfs") { it.copy(maxChars = max) }.use { r ->
            val v = Vfs(); val blob = "abcdefghij".repeat(200); v.write("ws/cap.txt", blob)
            val out = readWith(r.ctx(), v, "ws", "cap.txt", "full", null)
            assertTrue(out.length <= max.toInt()); val shown = archiveId(out); assertEquals(MIN_PREFIX, shown.length)
            assertEquals("1:$blob", r.store.getArchive(Expand.resolveId(r, shown), null)!!.decodeToString())
        }
    }

    @Test fun symlinkEscapeViaReadFsVfs() {
        val v = Vfs(); v.write("/outside/secret", "secret\n"); v.symlink("ws/escape", "/outside/secret")
        assertTrue("outside cwd" in assertFailsWith<ReadException> { resolveWith(v, "ws", "escape", emptyList()) }.message!!)
    }

    @Test fun mapListsFunMain() {
        val (rt, dir) = readRt("mapmain")
        rt.use { writeString("$dir/main.kt", "fun main() {\n    println(1)\n}\n" + "// pad\n".repeat(20))
            assertTrue("fun main" in read(it.ctx(), "$dir/main.kt", "map", null)) }
    }

    @Test fun strippedDropsCommentsKeepsBodyAndUnknownIsFull() {
        val (rt, dir) = readRt("stripped")
        rt.use { r ->
            writeString("$dir/a.rs", "/// gone\nfn keep() {}\n")
            val out = read(r.ctx(), "$dir/a.rs", "stripped", null); assertTrue("gone" !in out && "fn keep" in out, out)
            assertTrue(r.store.measurementCount("read") >= 1)
            writeString("$dir/a.txt", "// gone\nkeep\n")
            val txt = read(r.ctx(), "$dir/a.txt", "stripped", null); assertTrue("gone" in txt && "keep" in txt, txt)
        }
    }

    /** T670: `density:X` keeps informative lines, writes one `density` row, refuses a share outside (0, 1]. */
    @Test fun densityModeTrimsRepeatsAndRecordsARow() {
        val (rt, dir) = readRt("density")
        rt.use { r ->
            val body = buildString { for (i in 0 until 80) { append("    let value = self.cache.get(&key).cloned();\n"); if (i == 40) append("    let quota = billing::remaining_quota(account_id)?;\n") } }
            writeString("$dir/d.rs", body)
            val out = read(r.ctx(), "$dir/d.rs", "density:0.1", null)
            assertTrue("billing::remaining_quota" in out && out.length < body.length / 4, out)
            assertEquals(1, r.store.measurementCount("read"))
            assertTrue("density" in assertFails { read(r.ctx(), "$dir/d.rs", "density:2", null) }.message!!)
        }
    }

    /** T122: a whole-file archive never answers a ranged read. */
    @Test fun rangedReadIsNotAPointerToTheWholeFile() {
        val (rt, dir) = readRt("t122_ranges")
        rt.use { r ->
            val content = (1..100).joinToString("") { "Line $it\n" }; writeString("$dir/m.txt", content)
            r.store.putArchive(r.session, content.encodeToByteArray(), r.config.core.archiveDir)
            val o1 = read(r.ctx(), "$dir/m.txt", "lines", "10-20"); val o2 = read(r.ctx(), "$dir/m.txt", "lines", "30-40")
            assertTrue("identical to" !in o1 && "10:Line 10" in o1 && "Line 30" !in o1, o1)
            assertTrue("30:Line 30" in o2 && "Line 10" !in o2, o2)
        }
    }
}
