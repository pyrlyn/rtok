package rtok.plugins.read

import kotlinx.serialization.json.*
import rtok.Expand
import rtok.MIN_PREFIX
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.PostToolUse
import rtok.store.*
import rtok.support.Vfs
import rtok.sys.writeString
import kotlin.test.*

class CacheTest {
    private fun edit(path: String) = PostToolUse("Edit", buildJsonObject { put("file_path", path) }, JsonObject(emptyMap()))

    @Test fun twoIdenticalReadsSecondIsShort() {
        val (rt, dir) = readRt("same")
        rt.use { r ->
            writeString("$dir/a.txt", "alpha\nbeta\n")
            assertTrue("1:alpha" in read(r.ctx(), "$dir/a.txt", "full", null))
            val second = read(r.ctx(), "$dir/a.txt", "full", null)
            assertTrue(second.length < 80 && "unchanged" in second, second)
            assertTrue(r.store.measurementCount("read") >= 1)
        }
    }

    /** T929: once a sub-agent shares the session no read is a stub. */
    @Test fun aSessionWithASubagentNeverAnswersAReadWithAStub() {
        val (rt, dir) = readRt("shared")
        rt.use { r ->
            writeString("$dir/a.txt", "alpha\nbeta\n")
            assertTrue("1:alpha" in read(r.ctx(), "$dir/a.txt", "full", null))
            r.store.registerAgent(r.store.hostId("claude")!!, "shared", "child", null, null)
            repeat(2) {
                val again = read(r.ctx(), "$dir/a.txt", "full", null)
                assertTrue("1:alpha" in again && "unchanged" !in again, again)
            }
        }
    }

    @Test fun editFixtureBetweenReadsIsFull() {
        val (rt, dir) = readRt("edit")
        rt.use { r ->
            writeString("$dir/a.txt", "alpha\n")
            read(r.ctx(), "$dir/a.txt", "full", null)
            writeString("$dir/a.txt", "omega\n")
            val s = read(r.ctx(), "$dir/a.txt", "full", null)
            assertTrue("1:omega" in s && "unchanged" !in s, s)
        }
    }

    @Test fun postToolEditClearsHit() {
        val (rt, dir) = readRt("hook")
        rt.use { r ->
            writeString("$dir/a.txt", "alpha\n")
            read(r.ctx(), "$dir/a.txt", "full", null)
            ReadCache.invalidate(edit("$dir/a.txt"), r.ctx())
            // `delta` is on by default, so the cache stays and the re-read is the short hit.
            assertTrue("unchanged" in read(r.ctx(), "$dir/a.txt", "full", null))
        }
    }

    @Test fun twoIdenticalReadsSecondIsShortFromVfs(): Unit =
        vfsRt("same-vfs").use { r ->
            val v = Vfs()
            v.write("ws/a.txt", "alpha\nbeta\n")
            assertTrue("1:alpha" in readWith(r.ctx(), v, "ws", "a.txt", "full", null))
            val s = readWith(r.ctx(), v, "ws", "a.txt", "full", null)
            assertTrue(s.length < 80 && "unchanged" in s, s)
            assertTrue(r.store.measurementCount("read") >= 1)
        }

    @Test fun editFixtureBetweenReadsIsFullFromVfs(): Unit =
        vfsRt("edit-vfs").use { r ->
            val v = Vfs()
            v.write("ws/a.txt", "alpha\n")
            readWith(r.ctx(), v, "ws", "a.txt", "full", null)
            v.write("ws/a.txt", "omega\n")
            val s = readWith(r.ctx(), v, "ws", "a.txt", "full", null)
            assertTrue("1:omega" in s && "unchanged" !in s, s)
        }

    @Test fun postToolEditClearsHitFromVfs(): Unit =
        vfsRt("hook-vfs").use { r ->
            val v = Vfs()
            v.write("ws/a.txt", "alpha\n")
            readWith(r.ctx(), v, "ws", "a.txt", "full", null)
            ReadCache.invalidate(edit("ws/a.txt"), r.ctx())
            assertTrue("unchanged" in readWith(r.ctx(), v, "ws", "a.txt", "full", null))
        }

    private fun manyLines(changed: Int?) = (0 until 80).joinToString("") { if (it == changed) "chg$it\n" else "line$it\n" }

    @Test fun vfsSmallChangeIsHunksLargeIsFullMissingArchiveIsFull() {
        vfsRt("delta-vfs").use { r ->
            val v = Vfs()
            v.write("ws/a.txt", manyLines(null))
            readWith(r.ctx(), v, "ws", "a.txt", "full", null)
            v.write("ws/a.txt", manyLines(3))
            val small = readWith(r.ctx(), v, "ws", "a.txt", "full", null)
            assertTrue("@@" in small && "+chg3" in small && "expand " in small, small)
            assertTrue(r.store.measurementCount("read") >= 1)
        }
        vfsRt("delta-large").use { r ->
            val v = Vfs()
            v.write("ws/b.txt", (0 until 40).joinToString("") { "old$it\n" })
            readWith(r.ctx(), v, "ws", "b.txt", "full", null)
            v.write("ws/b.txt", (0 until 40).joinToString("") { "new$it\n" })
            val large = readWith(r.ctx(), v, "ws", "b.txt", "full", null)
            assertTrue("1:new0" in large && "previous " !in large, large)
        }
        vfsRt("delta-miss").use { r ->
            val v = Vfs()
            v.write("ws/c.txt", "keep\n")
            readWith(r.ctx(), v, "ws", "c.txt", "full", null)
            val abs = resolveWith(v, "ws", "c.txt", emptyList())
            r.ctx().putReadCache(ReadCache.key(abs, "full", null), "dead", "no-such-id")
            assertEquals("1:keep", readWith(r.ctx(), v, "ws", "c.txt", "full", null))
        }
    }

    @Test fun vfsCrlfDeltaArchivesCrlfAndUnchangedIsAtMost23Tokens(): Unit =
        vfsRt("delta-crlf").use { r ->
            val v = Vfs()
            val new = (0 until 80).joinToString("") { if (it == 3) "chg3\r\n" else "line$it\r\n" }
            v.write("ws/a.txt", (0 until 80).joinToString("") { "line$it\r\n" })
            readWith(r.ctx(), v, "ws", "a.txt", "full", null)
            v.write("ws/a.txt", new)
            val second = readWith(r.ctx(), v, "ws", "a.txt", "full", null)
            assertTrue("+chg3" in second, second)
            val shown =
                second
                    .substringAfterLast("expand ")
                    .trim()
                    .split(Regex("\\s+"))
                    .first()
            assertEquals(MIN_PREFIX, shown.length, second)
            assertContentEquals(new.encodeToByteArray(), r.store.getArchive(Expand.resolveId(r, shown), null))
            val third = readWith(r.ctx(), v, "ws", "a.txt", "full", null)
            assertTrue("unchanged" in third, third)
            assertTrue(r.ctx().estimate(third, Class.Code) <= 23u)
            assertEquals(third, readWith(r.ctx(), v, "ws", "a.txt", "full", null), "byte-stable unchanged line")
        }

    /** T906: the dedup line prints the 8-char prefix and `expand` returns the bytes; the row keeps the full id. */
    @Test fun dedupReferenceExpandsToTheArchivedBytes() {
        val (rt, dir) = readRt("dedup-ref")
        rt.use { r ->
            val text = (0 until 300).joinToString("") { "line$it\n" }
            writeString("$dir/a.txt", text)
            read(r.ctx(), "$dir/a.txt", "full", null)
            val second = read(r.ctx(), "$dir/a.txt", "full", null)
            assertTrue(second.length < 80, second)
            val id = second.split(' ').last()
            assertEquals(MIN_PREFIX, id.length)
            assertEquals(text, Expand.render(r, id, null, null, 0).decodeToString())
            assertEquals(text, Expand.fetch(r, id)!!.decodeToString())
            assertEquals(1, r.store.measurementCount("read"))
            val stored = r.store.lastMeasurementRef(r.session, "read", "dedup")!!
            assertEquals(64, stored.length)
            assertTrue(stored.startsWith(id))
        }
    }

    @Test fun modeDiffReadsTheFullCache(): Unit =
        vfsRt("mode-diff").use { r ->
            val v = Vfs()
            v.write("ws/a.txt", manyLines(null))
            readWith(r.ctx(), v, "ws", "a.txt", "full", null)
            v.write("ws/a.txt", manyLines(3))
            assertTrue("+chg3" in readWith(r.ctx(), v, "ws", "a.txt", "diff", null))
        }
}
