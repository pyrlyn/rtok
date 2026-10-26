package rtok.store

import rtok.sys.*
import kotlin.test.*

/** Port of tests_schema.rs (the symbol-root cases live in SymbolsTest). */
class RetentionTest {
    private fun bodies(
        s: Store,
        id: Int,
    ) = s.conn { it.queryOne("SELECT request_json, response_json, request_bytes FROM call_io WHERE call_id = ?", id) { r -> Triple(r.stringOrNull(0), r.stringOrNull(1), r.long(2)) } }!!

    @Test fun retentionClearsOldHookBodiesAndKeepsTheRest() {
        val s = Store.openInMemory()
        s.upsertSession("s", 1, null, null, "hook")
        val old = hookCallWithBody(s, "hook", """{"old":1}""".encodeToByteArray(), 10)
        val fresh = hookCallWithBody(s, "hook", """{"fresh":1}""".encodeToByteArray(), 1)
        val mcp = hookCallWithBody(s, "mcp_call", """{"mcp":1}""".encodeToByteArray(), 10)
        s.runRetention(30u, 3u)
        val (req, res, bytes) = bodies(s, old)
        assertNull(req)
        assertNull(res)
        assertEquals(9, bytes, "byte counts stay")
        assertNotNull(bodies(s, fresh).first)
        assertNotNull(bodies(s, mcp).first)
        assertEquals(listOf("""{"fresh":1}""", ""), s.recentHookInputs("s", 10), "a cleared body reads back empty")
    }

    @Test fun hookBodiesClearAcrossSeveralBatches() {
        val s = Store.openInMemory()
        s.upsertSession("s", 1, null, null, "hook")
        val old = (0 until 5).map { hookCallWithBody(s, "hook", "{}".encodeToByteArray(), 10) }
        val fresh = hookCallWithBody(s, "hook", "{}".encodeToByteArray(), 1)
        assertEquals(2, s.conn { clearHookBodies(it, unixNow() - 3 * 86_400, 2) }, "one batch clears at most `batch` rows")
        assertEquals(3, s.clearHookBodiesInBatches(3, 2))
        assertTrue(old.all { bodies(s, it).first == null })
        assertNotNull(bodies(s, fresh).first)
        assertEquals(0, s.clearHookBodiesInBatches(3, 2))
    }

    @Test fun zeroHookBodyDaysKeepsEveryBody() {
        val s = Store.openInMemory()
        s.upsertSession("s", 1, null, null, "hook")
        val old = hookCallWithBody(s, "hook", "{}".encodeToByteArray(), 10)
        s.runRetention(30u, 0u)
        assertNotNull(bodies(s, old).first)
    }

    @Test fun freshStoreIsIncrementalAndItsFileShrinksAfterRetention() {
        val dir = tmpDir("t352-shrink")
        val db = "$dir/rtok.db"
        Store.open(db).use { s ->
            assertEquals(2, s.autoVacuumMode())
            s.upsertSession("s", 1, null, null, "hook")
            hookCallWithBody(s, "hook", ByteArray(2 shl 20) { 'x'.code.toByte() }, 10)
        }
        val big = fileLen(db)!!
        assertTrue(big > 2 shl 20, "$big")
        Store.open(db).use { it.runRetention(30u, 3u) }
        val small = fileLen(db)!!
        assertTrue(small < big / 2, "$small vs $big")
        removeDirAll(dir)
    }

    @Test fun purgeDropsOldCallsAndTheirArchives() {
        val dir = tmpDir("purge-archive")
        val s = Store.openInMemory()
        s.upsertSession("s", null, null, null, null)
        val id = s.insertCall("s", "mcp", "mcp_call", null, null, null, null, null)
        val big = ByteArray(100) { 'y'.code.toByte() }
        s.insertCallIo(id, big, null, 10, dir)
        s.setCallTs(id, 1)
        val pending = s.archivesPendingRetention(30u)
        assertEquals(1, pending.size)
        assertTrue(exists(pending[0]))
        assertEquals(1, s.purgeCallsOlderThan(30))
        assertFalse(exists(pending[0]))
        assertEquals(0L, s.conn { it.longOf("SELECT count(*) FROM archive") })
        removeDirAll(dir)
    }

    @Test fun retentionDropsSymbolRootsThatAreNoLongerDirectories() {
        val s = Store.openInMemory()
        val live = tmpDir("t352-live")
        val gone = "/rtok-t352-no-such-root"
        for (root in listOf(live, gone, "")) {
            s.index(root, "a.rs", symRow("f", 1, true))
            s.setExtractorFingerprint(root, "fp")
        }
        s.markSymbolsStaleIn(gone, "z.rs")
        s.runRetention(30u, 3u)
        assertEquals(0L, s.symbolCount(gone))
        assertTrue(s.symbolStalePaths(gone).isEmpty())
        assertNull(s.extractorFingerprint(gone))
        assertEquals(1L, s.symbolCount(live))
        assertEquals(1L, s.symbolCount(""), "the empty root stays")
        removeDirAll(live)
    }

    @Test fun retentionDropsHomeAndFilesystemRoots() {
        val s = Store.openInMemory()
        val home = tmpDir("t356-home")
        val project = tmpDir("t356-project")
        for (root in listOf(home, project, "/")) {
            s.index(root, "a.rs", symRow("f", 1, true))
            s.setExtractorFingerprint(root, "fp")
        }
        assertEquals(2, s.dropDeadSymbolRoots(home))
        for (gone in listOf(home, "/")) {
            assertEquals(0L, s.symbolCount(gone), gone)
            assertNull(s.extractorFingerprint(gone), gone)
        }
        assertEquals(1L, s.symbolCount(project))
        removeDirAll(home)
        removeDirAll(project)
    }
}
