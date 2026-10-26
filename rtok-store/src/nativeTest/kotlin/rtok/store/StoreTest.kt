package rtok.store

import rtok.db.*

import kotlinx.serialization.json.*
import rtok.plugin.sdk.NewObservation
import rtok.sys.*
import kotlin.test.*

/** Port of tests_store.rs. */
class StoreTest {
    @Test fun noteSearchTreatsQueryTextLiterally() {
        val s = Store.openInMemory(); s.insertNote(null, "note", "parser", "call read( on the file")
        for (q in listOf("read(", "*", "-", "AND", "\"quoted\"", "read()")) assertTrue(s.searchNotes(q, 5u).size <= 1, q)
        assertEquals(1, s.searchNotes("read(", 5u).size); assertTrue(s.searchNotes("   ", 5u).isEmpty())
    }

    @Test fun migrationIsIdempotent() {
        val s = Store.openInMemory(); assertEquals(0, s.migrate())
        s.conn { db -> for (t in listOf("events", "measurements", "archive", "read_cache", "notes", "usage")) db.longOf("SELECT count(*) FROM $t") }
    }

    @Test fun everyMigrationHasItsOwnNumber() {
        val seen = HashMap<String, String>()
        for ((name, _) in EMBEDDED_MIGRATIONS) { val n = name.substringBefore('_'); seen.put(n, name)?.let { fail("$it and $name share the number $n") } }
    }

    @Test fun legacySchemaMigrationsAreNotRerun() {
        val dir = tmpDir("mig-legacy"); val db = "$dir/rtok.db"
        val id = Store.open(db).use { it.insertNote(null, "note", "kept", "survives the bridge") }
        rawDb(db).use { Migrations.revertToLegacyBookkeeping(it) }
        Store.open(db).use { s -> assertEquals(0, s.migrate()); assertEquals("survives the bridge", s.noteRow(id)!!.body) }
        removeDirAll(dir)
    }

    @Test fun concurrentOpensOfAFreshStoreAllMigrate() {
        val dir = tmpDir("mig-race"); removeDirAll(dir); val path = "$dir/rtok.db"
        val errs = parallel(8) { Store.open(path).close() }
        removeDirAll(dir); assertEquals(emptyList(), errs)
    }

    @Test fun purgeWaitsOutAConcurrentWriter() {
        val dir = tmpDir("purge-race"); val db = "$dir/rtok.db"
        Store.open(db).use { s -> s.upsertSession("s", null, null, null, null); s.setCallTs(s.insertCall("s", "mcp", "mcp_call", null, null, null, null, null), 1) }
        val holder = rawDb(db); holder.exec("PRAGMA busy_timeout = 1000"); holder.exec("PRAGMA journal_mode = WAL"); holder.exec("BEGIN IMMEDIATE")
        val w = kotlin.native.concurrent.Worker.start()
        val release = w.execute(kotlin.native.concurrent.TransferMode.SAFE, { holder }) { h -> platform.posix.usleep(1_200_000u); h.exec("COMMIT"); h.close() }
        Store.open(db).use { s -> assertEquals(1, s.runRetention(30u, 3u), "the old call is gone once the lock is released"); assertEquals(0, s.countCalls()) }
        release.result; w.requestTermination().result; removeDirAll(dir)
    }

    private fun dbBeforeMigration(tag: String): Pair<String, Db> {
        val dir = tmpDir("mig-$tag"); val db = Db.open("$dir/rtok.db"); Migrations.runBefore(db, tag); return dir to db
    }

    @Test fun migration0015AddsLifecycleColumnsToAPreviousSchemaDb() {
        val (dir, raw) = dbBeforeMigration("0015")
        raw.execute("INSERT INTO notes (ts, kind, title, body) VALUES (1, 'note', 'old', 'before the lifecycle')"); raw.close()
        Store.open("$dir/rtok.db").use { s ->
            val row = s.noteRow(1)!!; assertEquals(listOf<Any?>("old", null, null, 0), listOf(row.title, row.retired, row.supersededBy, row.pinned))
            assertTrue(s.retireNote(1, null)); assertTrue(s.searchNotes("before", 5u).isEmpty(), "retired note does not search")
        }
        removeDirAll(dir)
    }

    @Test fun migration0047NumbersOldClaimsInTheirOldOrder() {
        val (dir, raw) = dbBeforeMigration("0047")
        for ((id, since) in listOf("R1" to 100L, "R2" to 100L, "R3" to 50L, "R4" to 200L))
            raw.execute("INSERT INTO task_claims (project, task_id, agent_id, title, since) VALUES ('p', ?, 'a', ?, ?)", id, id, since)
        raw.close()
        Store.open("$dir/rtok.db").use { s ->
            val seen = ArrayList<String>()
            while (true) { val c = s.latestTaskClaim("p", "a") ?: break; s.clearTaskClaim("p", c.taskId); seen += c.taskId }
            assertEquals(listOf("R4", "R1", "R2", "R3"), seen)
        }
        removeDirAll(dir)
    }

    @Test fun migration0052DropsObserveRowsOnly() {
        val (dir, raw) = dbBeforeMigration("0052")
        for ((p, k) in listOf("memory" to "observe", "memory" to "observe", "memory" to "brief", "read" to "observe"))
            raw.execute("INSERT INTO measurements (session, plugin, kind, before_bytes, after_bytes, est_before, est_after) VALUES ('s', ?, ?, 99589, 404, 25000, 100)", p, k)
        raw.close()
        Store.open("$dir/rtok.db").use { s -> assertEquals(listOf(Triple("memory", "brief", 1L), Triple("read", "observe", 1L)), s.measurementTotals().map { Triple(it.plugin, it.kind, it.rows) }) }
        removeDirAll(dir)
    }

    @Test fun migration0050NumbersEndedSessionsAndRewindsTheOldMark() {
        val (dir, raw) = dbBeforeMigration("0050")
        for ((id, ended) in listOf("a" to 100L, "b" to 200L, "c" to 200L, "d" to 300L, "e" to null)) raw.execute("INSERT INTO sessions (id, ended_at) VALUES (?, ?)", id, ended)
        for ((stream, mark) in listOf("sessions" to 200L, "sessions_tail" to 1L)) raw.execute("INSERT INTO otel_export (stream, mark) VALUES (?, ?)", stream, mark)
        raw.close()
        Store.open("$dir/rtok.db").use { s ->
            assertEquals(1, s.otelMark("sessions_seq")); assertEquals(0, s.otelMark("sessions")); assertEquals(0, s.otelMark("sessions_tail"))
            assertEquals(listOf(2L to "b", 3L to "c", 4L to "d"), s.sessionsPendingExport(1, 10).map { it.first to it.second.id })
        }
        removeDirAll(dir)
    }

    @Test fun upsertSameTitleWithANewBodyKeepsTheOldBodyInHistory() {
        val s = Store.openInMemory()
        val (id, created) = s.upsertNote("p", "decision", "auth", "sessions"); assertFalse(created); assertTrue(s.noteVersions(id).isEmpty())
        val (again, updated) = s.upsertNote("p", "decision", "auth", "jwt"); assertTrue(updated); assertEquals(id, again)
        assertEquals(listOf(Triple(1, "auth", "sessions")), s.noteVersions(id)); assertEquals("jwt", s.getNoteBody(id))
        assertEquals(id, s.upsertNote("p", "decision", "auth", "opaque tokens").first)
        assertEquals(listOf(Triple(1, "auth", "sessions"), Triple(2, "auth", "jwt")), s.noteVersions(id))
    }

    @Test fun checkpointUpsertWritesZeroVersionRows() {
        val s = Store.openInMemory()
        val (id, _) = s.upsertNote("p", "checkpoint:s", "compact", "first"); s.upsertNote("p", "checkpoint:s", "compact", "second"); assertTrue(s.noteVersions(id).isEmpty())
        val (sid, _) = s.upsertNote("p", "session:s", "handoff", "one"); s.upsertNote("p", "session:s", "handoff", "two"); assertTrue(s.noteVersions(sid).isEmpty())
    }

    @Test fun sameBodyUpsertWritesZeroVersionRows() {
        val s = Store.openInMemory(); val (id, _) = s.upsertNote(null, "note", "topic", "same")
        assertTrue(s.upsertNote(null, "note", "topic", "same").second); assertTrue(s.noteVersions(id).isEmpty()); assertTrue(s.noteVersions(id + 1).isEmpty())
    }

    @Test fun migration0020DropsPreExistingDuplicateNotes() {
        val (dir, raw) = dbBeforeMigration("0020")
        raw.exec("INSERT INTO notes (id, ts, project, kind, title, body) VALUES (1, 1, NULL, 'note', 'dup', 'stale'), (2, 2, NULL, 'note', 'dup', 'fresh'), " +
            "(3, 1, 'rtok', 'note', 'dup', 'stale'), (4, 2, 'rtok', 'note', 'dup', 'fresh')"); raw.close()
        Store.open("$dir/rtok.db").use { s ->
            assertEquals(listOf(2 to "fresh", 4 to "fresh"), s.conn { it.query("SELECT id, body FROM notes ORDER BY id ASC") { r -> r.int(0) to r.string(1) } })
            val err = assertFails { s.conn { it.execute("INSERT INTO notes (project, kind, title, body) VALUES (NULL, 'note', 'dup', 'third')") } }
            assertTrue(err.message!!.contains("UNIQUE"), err.message)
        }
        removeDirAll(dir)
    }

    @Test fun migration0021KeepsOldRowsAndRecordsAKeyedCallOnce() {
        val (dir, raw) = dbBeforeMigration("0021")
        raw.exec("INSERT INTO measurements (ts, session, plugin, kind, before_bytes, after_bytes, est_before, est_after) VALUES (1, 's', 'read', 'delta', 9, 1, 3, 1), (1, 's', 'read', 'delta', 9, 1, 3, 1)"); raw.close()
        Store.open("$dir/rtok.db").use { s ->
            val m = meas("read", "delta", 9u, 1u, 3u, 1u)
            repeat(2) { s.insertMeasurementOnce("s", m, "PreToolUse:t1") }; s.insertMeasurement("s", m); assertEquals(4, s.countMeasurements())
        }
        removeDirAll(dir)
    }

    @Test fun concurrentUpsertNoteYieldsOneRow() {
        val dir = tmpDir("note-race"); val db = "$dir/rtok.db"
        val a = Store.open(db); val b = Store.open(db)
        val stores = listOf(a, b)
        assertEquals(emptyList(), parallel(2) { k -> for (i in 0 until 50) stores[k].upsertNote("rtok", "note", "topic", "body $i") })
        assertEquals(1, a.conn { it.query("SELECT id FROM notes WHERE project = 'rtok' AND kind = 'note' AND title = 'topic'") { r -> r.int(0) } }.size)
        a.close(); b.close(); removeDirAll(dir)
    }

    @Test fun upsertKeepsThePreviousBodyAndSkipsCheckpointsAndSameBody() {
        val s = Store.openInMemory()
        val (id, created) = s.upsertNote("rtok", "decision", "topic", "first body"); assertFalse(created); assertTrue(s.noteVersions(id).isEmpty())
        val (same, updated) = s.upsertNote("rtok", "decision", "topic", "second body"); assertTrue(updated); assertEquals(id, same)
        assertEquals(listOf(Triple(1, "topic", "first body")), s.noteVersions(id)); assertEquals("second body", s.noteRow(id)!!.body)
        s.upsertNote("rtok", "decision", "topic", "second body"); assertEquals(1, s.noteVersions(id).size)
        s.upsertNote("rtok", "decision", "topic", "third body")
        assertEquals(listOf(Triple(1, "topic", "first body"), Triple(2, "topic", "second body")), s.noteVersions(id))
        val (cid, _) = s.upsertNote(null, "checkpoint:s", "compact", "checkpoint a"); s.upsertNote(null, "checkpoint:s", "compact", "checkpoint b"); assertTrue(s.noteVersions(cid).isEmpty())
        val (sid, _) = s.upsertNote(null, "session:s", "handoff", "session a"); s.upsertNote(null, "session:s", "handoff", "session b"); assertTrue(s.noteVersions(sid).isEmpty())
    }

    @Test fun fts5MatchFindsInsertedNote() {
        val s = Store.openInMemory()
        s.conn { it.execute("INSERT INTO notes (project, kind, title, body) VALUES ('rtok', 'decision', 'WAL mode', 'use sqlite wal journal')") }
        assertEquals("WAL mode", s.searchNotes("journal", 5u)[0].title)
    }

    @Test fun openOnDiskUsesWal() {
        val dir = tmpDir("store"); removeDirAll(dir)
        Store.open("$dir/rtok.db").use { s -> assertEquals("wal", s.conn { it.stringOf("PRAGMA journal_mode") }) }
        removeDirAll(dir)
    }

    @Test fun schema0002SeedsHostsAndRejectsBadFk() {
        val s = Store.openInMemory(); assertEquals(0, s.migrate())
        s.conn { db ->
            assertEquals(8L, db.longOf("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('hosts','providers','models','sessions','calls','call_io','tokens','logs')"))
            assertEquals(7L, db.longOf("SELECT count(*) FROM hosts"))
            db.execute("INSERT INTO sessions (id) VALUES ('s1')")
            assertFails("bad host_id must fail FK") { db.execute("INSERT INTO calls (session_id, host_id, surface, kind) VALUES ('s1', 999, 'cli', 'cli')") }
        }
    }

    private fun ioFixture(dir: String): Pair<Store, Int> {
        removeDirAll(dir); createDirAll(dir)
        val s = Store.openInMemory(); s.upsertSession("s1", 1, null, null, null)
        return s to s.insertCall("s1", "mcp", "mcp_call", 1, null, null, "read", "read")
    }

    @Test fun requestBodiesAreSavedCleanUnlessStoreRaw() {
        val dir = tmpDir("io-clean"); val (s, id) = ioFixture(dir)
        val dirty = "{\"prompt\":\"<system-reminder>\\nx\\n</system-reminder>\\n\\u001b[31mfix\\u001b[0m it  \\r\\n\"}"
        val reply = "{\"out\":\"\\u001b[1mok\\u001b[0m\"}"
        s.insertCallIo(id, dirty.encodeToByteArray(), reply.encodeToByteArray(), 65536, null)
        val (req, res, bytes, sha) = s.conn { it.queryOne("SELECT request_json, response_json, request_bytes, request_sha256 FROM call_io WHERE call_id = ?", id) { r -> listOf(r.string(0), r.string(1), r.long(2), r.string(3)) } }!!
        assertEquals("""{"prompt":"fix it\n"}""", req); assertEquals(reply, res)
        assertEquals((req as String).encodeToByteArray().size.toLong(), bytes); assertEquals(hexSha256(req.encodeToByteArray()), sha)
        s.setStoreRaw(true)
        val rawId = s.insertCall("s1", "mcp", "mcp_call", 1, null, null, "read", "raw")
        s.insertCallIo(rawId, dirty.encodeToByteArray(), null, 65536, null)
        assertEquals(dirty, s.conn { it.stringOf("SELECT request_json FROM call_io WHERE call_id = ?", rawId) })
        removeDirAll(dir)
    }

    @Test fun aWrapperHiddenByNoiseIsRemovedBeforeSaving() {
        val dir = tmpDir("io-hidden"); val (s, id) = ioFixture(dir)
        val dirty = "{\"prompt\":\"<system-\\u001b[0mreminder>hidden</system-reminder>\\nvisible\"}"
        fun saved(id: Int) = s.conn { it.queryOne("SELECT request_json, request_bytes, request_sha256 FROM call_io WHERE call_id = ?", id) { r -> Triple(r.string(0), r.long(1), r.string(2)) } }!!
        s.insertCallIo(id, dirty.encodeToByteArray(), null, 65536, null)
        val (req, bytes, sha) = saved(id)
        assertEquals("""{"prompt":"visible"}""", req); assertEquals(req.length.toLong(), bytes); assertEquals(hexSha256(req.encodeToByteArray()), sha)
        assertContentEquals(req.encodeToByteArray(), Sanitize.body(req.encodeToByteArray()))
        val hook = "{\"session_id\":\"s1\"," + dirty.substring(1)
        val hookId = s.insertCall("s1", "hook", "hook", null, null, null, null, "UserPromptSubmit")
        s.insertHookCallIo(hookId, hook.encodeToByteArray(), null, 65536)
        val (req2, bytes2, sha2) = saved(hookId)
        assertEquals("""{"prompt":"visible"}""", req2); assertEquals(req2.length.toLong() to hexSha256(req2.encodeToByteArray()), bytes2 to sha2)
        assertEquals(buildJsonObject { put("session_id", "s1"); put("prompt", "visible") }, Json.parseToJsonElement(s.callIoRequest(hookId)!!.decodeToString()))
        s.setStoreRaw(true)
        val rawId = s.insertCall("s1", "hook", "hook", null, null, null, null, "UserPromptSubmit")
        s.insertHookCallIo(rawId, hook.encodeToByteArray(), null, 65536)
        assertEquals(hook, s.callIoRequest(rawId)!!.decodeToString())
        removeDirAll(dir)
    }

    @Test fun writeApiRoundTripAndSpill() {
        val dir = tmpDir("io"); val (s, id) = ioFixture(dir)
        s.insertCallIo(id, """{"a":1}""".encodeToByteArray(), """{"ok":true}""".encodeToByteArray(), 65536, dir)
        s.insertTokens(id, "read", "before", "estimate", 10); s.insertTokens(id, "read", "after", "estimate", 4); s.insertTokens(id, "read", "mcp", "mcp", 12)
        s.insertLog("info", "plugin", "read", "ok", "s1", id, "read")
        assertEquals(3L, s.conn { it.longOf("SELECT count(*) FROM tokens WHERE call_id = ?", id) })
        assertEquals(1L, s.conn { it.longOf("SELECT count(*) FROM logs WHERE source = 'plugin' AND call_id = ?", id) })
        val big = ByteArray(70 * 1024) { 'x'.code.toByte() }
        val id2 = s.insertCall("s1", "mcp", "mcp_call", 1, null, null, "read", "big")
        s.insertCallIo(id2, big, null, 64 * 1024, dir)
        val (json, arch) = s.conn { it.queryOne("SELECT request_json, request_archive FROM call_io WHERE call_id = ?", id2) { r -> r.stringOrNull(0) to r.stringOrNull(1) } }!!
        assertNull(json); assertNotNull(arch)
        removeDirAll(dir)
    }

    @Test fun insertCallIoFailureLeavesNoOrphanArchive() {
        val dir = tmpDir("io-orphan"); val (s, id) = ioFixture(dir)
        s.conn { it.execute("INSERT INTO call_io (call_id) VALUES (?)", id) }
        val big = ByteArray(70 * 1024) { 'x'.code.toByte() }
        val err = assertFails { s.insertCallIo(id, big, null, 64 * 1024, dir) }
        assertTrue(err.message!!.contains("UNIQUE"), err.message)
        assertEquals(0L, s.conn { it.longOf("SELECT count(*) FROM archive") }, "a failed call_io insert must not strand an archive row")
        assertFalse(exists("$dir/${hexSha256(big)}"), "the orphan payload file must be removed on rollback")
        removeDirAll(dir)
    }

    @Test fun upsertSessionKeepsNonNullAttribution() {
        val s = Store.openInMemory(); val claude = s.hostId("claude")!!
        s.upsertSession("s1", claude, "rtok", "/tmp/rtok", null); s.upsertSession("s1", claude, null, null, "proxy")
        assertEquals(Triple<String?, String?, String?>("claude", "rtok", "/tmp/rtok"), s.sessionRow("s1"))
        fun source() = s.conn { it.stringOf("SELECT source FROM sessions WHERE id = 's1'") }
        assertEquals("proxy", source()); s.upsertSession("s1", claude, null, null, null); assertEquals("proxy", source(), "source survived a None upsert")
    }

    @Test fun twoApisAreTwoStatsRows() { val s = Store.openInMemory(); seedTwoApis(s); assertEquals(2, s.usageByApi().size) }

    @Test fun usageByModelMergesNullAndLiteralUnknown() {
        val s = Store.openInMemory(); s.upsertSession("s1", null, null, null, "proxy")
        val call = s.insertCall("s1", "proxy", "api_request", null, null, null, null, "/v1/messages")
        s.insertUsage("s1", null, "anthropic", 10, 1, 2, 3, call); s.insertUsage("s1", "unknown", "anthropic", 5, 0, 1, 2, call)
        val rows = s.usageByModel(); assertEquals(1, rows.size); assertEquals(ModelUsage("unknown", 15, 1, 3, 5), rows[0])
    }

    @Test fun sessionTotalsSumsEachSessionExactly() {
        val s = Store.openInMemory(); val claude = s.hostId("claude")!!; val pi = s.hostId("pi")!!
        s.upsertSession("a", claude, "rtok", "/w/rtok", "proxy"); s.upsertSession("b", pi, "rtok", null, null); s.upsertSession("c", pi, null, null, null)
        val (pid, mid) = s.upsertModel("anthropic", "claude-x")
        val callA = s.insertCall("a", "proxy", "api_request", claude, pid, mid, null, "/v1/messages")
        val callB = s.insertCall("b", "proxy", "api_request", pi, null, null, null, "/v1/chat/completions")
        s.insertUsage("a", "claude-x", "anthropic", 10, 1, 2, 3, callA); s.insertUsage("a", "claude-x", "anthropic", 20, 0, 5, 4, callA)
        s.insertUsage("b", "gpt-x", "openai_chat", 7, 2, 0, 1, callB); s.endSession("b", 2500)
        s.conn { db ->
            for ((id, st) in listOf("a" to 1000L, "b" to 2000L, "c" to 3000L)) db.execute("UPDATE sessions SET started_at = ? WHERE id = ?", st, id)
            for ((id, ts) in listOf(1 to 1100L, 2 to 1200L, 3 to 2100L)) db.execute("UPDATE usage SET ts = ? WHERE id = ?", ts, id)
            for ((id, ts) in listOf(callA to 1500L, callB to 2100L)) db.execute("UPDATE calls SET ts = ? WHERE id = ?", ts, id)
        }
        assertEquals(listOf(
            SessionTotals("c", "pi", null, null, null, null, 0, 0, 0, 0, 3000, 3000, null),
            SessionTotals("b", "pi", "rtok", null, "openai_chat", "gpt-x", 7, 2, 0, 1, 2000, 2100, 2500),
            SessionTotals("a", "claude", "rtok", "anthropic", "anthropic", "claude-x", 30, 1, 7, 7, 1000, 1500, null),
        ), s.sessionTotals(0))
        assertEquals(listOf("c", "b"), s.sessionTotals(2000).map { it.id }); assertEquals(listOf("c"), s.sessionTotals(3000).map { it.id })
    }

    @Test fun recentCallsIsNewestFirstBoundedAndLinked() {
        val s = Store.openInMemory(); val claude = s.hostId("claude")!!
        s.upsertSession("s", claude, null, null, "proxy"); val (pid, mid) = s.upsertModel("anthropic", "claude-x")
        val hook = s.insertCall("s", "hook", "hook", null, null, null, null, "Stop")
        val run = s.insertCall("s", "hook", "plugin_run", null, null, null, "cmd", null); s.setCallParent(run, hook)
        val api = s.insertCall("s", "proxy", "api_request", claude, pid, mid, null, "/v1/messages"); s.setCallMs(api, 12.5)
        s.insertUsage("s", "claude-x", "anthropic", 10, 1, 2, 3, api); s.insertUsage("s", "claude-x", "anthropic", 20, 0, 5, 4, api)
        val rows = s.recentCalls(10); assertEquals(listOf(api, run, hook), rows.map { it.id })
        val top = rows[0]
        assertEquals("anthropic", top.api); assertEquals(listOf<Long?>(20, 0, 5, 4), listOf(top.input, top.cacheCreate, top.cacheRead, top.output))
        assertEquals(12.5, top.ms); assertEquals("claude", top.host); assertEquals("anthropic", top.provider); assertEquals("claude-x", top.model); assertNull(top.parentId)
        assertEquals(hook, rows[1].parentId); assertNull(rows[1].api); assertNull(rows[2].api)
        assertEquals(listOf(api), s.recentCalls(1).map { it.id })
    }

    @Test fun anObservationCallKeyHoldsPastTheNarrativeWindow() {
        val s = Store.openInMemory(); val obs = NewObservation("s", null, "command_run", "t", "n", "d", emptyList())
        fun insert(once: String?) = s.insertObservationOnce(obs, once)
        assertNotNull(insert("PostToolUse:a")); assertNull(insert("PostToolUse:a"), "inside the window")
        s.setObservationTs("s", 0); assertNull(insert("PostToolUse:a"), "the same call, late"); assertNotNull(insert("PostToolUse:b"), "another call, late")
        assertNull(insert(null), "no key: the window still holds")
        s.setObservationTs("s", 0); assertNotNull(insert(null), "no key, late"); s.setObservationTs("s", 0); assertNotNull(insert(null), "keyless rows never collide")
    }

    @Test fun fileLinkedObservationsAreDistinctBeforeTheLimit() {
        val s = Store.openInMemory()
        fun add(project: String?, dedup: String, files: List<String>) = s.insertObservation(NewObservation("s", project, "file_read", "t", dedup, dedup, files))!!
        val older = add("p", "older", listOf("a.rs")); val newer = add("p", "newer", listOf("a.rs", "b.rs")); val other = add("q", "other", listOf("a.rs", "b.rs"))
        val paths = listOf("a.rs", "b.rs")
        fun ids(project: String?, paths: List<String>, limit: UInt) = s.observationsForFiles(project, paths, limit).map { it.id }
        assertEquals(listOf(newer, older), ids("p", paths, 2u)); assertEquals(listOf(newer, older), ids("p", paths.take(1), 5u))
        assertEquals(listOf(newer), ids("p", paths.drop(1), 5u)); assertEquals(listOf(other), ids("q", paths, 5u)); assertTrue(ids("p", emptyList(), 5u).isEmpty())
    }
}
