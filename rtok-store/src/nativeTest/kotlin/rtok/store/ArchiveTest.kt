package rtok.store

import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.link
import rtok.db.*
import rtok.sys.*
import kotlin.test.*
import kotlin.time.TimeSource

/** Port of tests_archive.rs. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class ArchiveTest {
    private fun b(s: String) = s.encodeToByteArray()

    @Test fun purgeDropsOldCallsAndDetachesTheirLedgerRows() {
        val s = Store.openInMemory()
        s.upsertSession("s", s.hostId("claude"), null, null, "proxy")

        fun call(kind: String) = s.insertCall("s", "proxy", kind, null, null, null, null, null)
        val old = call("api_request")
        val child = call("plugin_run")
        val fresh = call("hook")
        s.setCallParent(child, old)
        s.insertUsage("s", null, "anthropic", 1, 0, 0, 1, old)
        s.insertMeasurement("s", meas("cmd", "rule", 10u, 5u, 3u, 1u, callId = old))
        s.insertProviderTokens(old, 2, 1, 0, 0, 1)
        s.insertCallIo(old, b("{}"), null, 1024, null)
        s.insertLog("info", "proxy", "x", "m", "s", old, null)
        s.setCallTs(old, 0)
        assertEquals(1, s.purgeCallsOlderThan(1))
        s.conn { db ->
            assertEquals(2L, db.longOf("SELECT count(*) FROM calls WHERE id IN (?, ?)", child, fresh))
            assertEquals(0L, db.longOf("SELECT count(*) FROM calls WHERE parent_id IS NOT NULL"), "the child is detached")
            assertEquals(1L, db.longOf("SELECT count(*) FROM usage WHERE call_id IS NULL"), "usage row kept, detached")
            assertEquals(1L, db.longOf("SELECT count(*) FROM measurements WHERE call_id IS NULL"), "measurements row kept, detached")
            for (t in listOf("tokens", "call_io", "logs")) assertEquals(0L, db.longOf("SELECT count(*) FROM $t"), t)
        }
        assertEquals(0, s.purgeCallsOlderThan(0), "days <= 0 is a no-op")
    }

    private fun seedOneSpilledCall(dir: String): Pair<Store, String> {
        val s = Store.open("$dir/rtok.db")
        s.upsertSession("sess", 1, null, null, "proxy")
        val call = s.insertCall("sess", "proxy", "api_request", 1, null, null, null, null)
        val body = ByteArray(70 * 1024) { 'x'.code.toByte() }
        s.insertCallIo(call, body, null, 64 * 1024, "$dir/archive")
        s.setCallTs(call, 0)
        return s to "$dir/archive/${hexSha256(body)}"
    }

    @Test fun runRetentionPurgesOldCallAndArchive() {
        val dir = tmpDir("retain")
        val (s, arch) = seedOneSpilledCall(dir)
        assertTrue(exists(arch))
        assertEquals(1, s.countCalls())
        assertEquals(1, s.runRetention(1u, 3u))
        assertEquals(0, s.countCalls())
        assertFalse(exists(arch))
        s.close()
        removeDirAll(dir)
    }

    @Test fun archiveSummariesAreStoredOncePerVersionAndPurgedWithTheArchive() {
        val dir = tmpDir("t385")
        val (s, arch) = seedOneSpilledCall(dir)
        val id = arch.substringAfterLast('/')
        assertNull(s.archiveSummary(id, "v1"))
        assertTrue(s.putArchiveSummary(id, "v1", "first"))
        assertFalse(s.putArchiveSummary(id, "v1", "second"))
        assertTrue(s.putArchiveSummary(id, "v2", "other"))
        assertEquals(listOf(id to "first", id to "other"), s.archiveSummaries())
        assertEquals("first", s.archiveSummary(id, "v1"))
        assertEquals("other", s.archiveSummary(id, "v2"))
        assertEquals(1, s.runRetention(1u, 3u))
        assertNull(s.archiveSummary(id, "v1"))
        s.close()
        removeDirAll(dir)
    }

    @Test fun archivesPendingRetentionPreviewsWithoutDeleting() {
        val dir = tmpDir("t182")
        val (s, arch) = seedOneSpilledCall(dir)
        assertEquals(listOf(arch), s.archivesPendingRetention(1u))
        assertTrue(exists(arch), "preview must not delete anything")
        assertEquals(1, s.countCalls())
        assertEquals(emptyList(), s.archivesPendingRetention(0u), "0 = keep forever")
        assertEquals(1, s.runRetention(1u, 3u))
        assertFalse(exists(arch))
        s.close()
        removeDirAll(dir)
    }

    @Test fun archiveDecisionRepeatedIdPersistsPerSession() {
        val dir = tmpDir("t453-id")
        val s = Store.openInMemory()
        val id = s.putArchive("a", b("body"), dir)
        s.putArchiveDecision("tu-1", id, "a", "ptr-a")
        s.putArchiveDecision("tu-1", id, "b", "ptr-b")
        assertEquals("ptr-a" to "ptr-b", s.archiveDecision("a", "tu-1")!!.pointer to s.archiveDecision("b", "tu-1")!!.pointer)
        assertEquals("ptr-a", s.liveZonePointer(id), "deterministic by (tool_use_id, session)")
        assertNull(s.liveZonePointer(s.putArchive("c", b("none"), dir)))
        removeDirAll(dir)
    }

    @Test fun sessionLiveArchivesOrdersByDecisionTimeOnceEach() {
        val dir = tmpDir("t306")
        val s = Store.openInMemory()
        val x = s.putArchive("s1", b("body-x"), dir)
        val y = s.putArchive("s1", b("body-y"), dir)
        s.conn {
            it.execute("UPDATE archive SET ts = 100 WHERE id = ?", x)
            it.execute("UPDATE archive SET ts = 200 WHERE id = ?", y)
        }
        s.putArchiveDecision("tu-x1", x, "s1", "ptr-x1")
        s.putArchiveDecision("tu-y1", y, "s1", "ptr-y1")
        s.putArchiveDecision("tu-x2", x, "s1", "ptr-x2")
        s.conn { db -> for ((tu, ts) in listOf("tu-x1" to 100, "tu-y1" to 200, "tu-x2" to 300)) db.execute("UPDATE archive_decisions SET ts = ? WHERE session = 's1' AND tool_use_id = ?", ts, tu) }
        assertEquals(listOf(x, y), s.sessionLiveArchives("s1").map { it.first })
        removeDirAll(dir)
    }

    @Test fun archiveInSessionHitsOnlyTheWriterSession() {
        val dir = tmpDir("t651")
        val s = Store.openInMemory()
        val sha = s.putArchive("a", b("same-bytes"), dir)
        assertEquals(sha, s.archiveInSession("a", sha, null)!!.first)
        assertNull(s.archiveInSession("b", sha, null))
        removeDirAll(dir)
    }

    @Test fun archiveInSessionScopesByContextWithinOneSession() {
        val dir = tmpDir("t127")
        val s = Store.openInMemory()
        val sha = s.putArchiveFor("s", b("same-bytes"), dir, "agent-a")
        assertNull(s.archiveInSession("s", sha, "agent-b"), "context B never saw what context A archived")
        assertNull(s.archiveInSession("s", sha, null))
        assertEquals(sha, s.archiveInSession("s", sha, "agent-a")!!.first)
        removeDirAll(dir)
    }

    @Test fun forgetSeenEndsDedupForThatContextAndAReprintReclaimsTheRow() {
        val dir = tmpDir("t929")
        val s = Store.openInMemory()
        val main = s.putArchiveFor("s", b("main-body"), dir, null)
        val sub = s.putArchiveFor("s", b("sub-body"), dir, "agent-a")
        s.putReadCache("s", "/a", "h", main)
        s.putReadCache("other", "/a", "h", null)
        s.forgetSeen("s", null)
        assertNull(s.archiveInSession("s", main, null))
        assertNotNull(s.getArchive(main, dir), "still expandable")
        assertNotNull(s.archiveInSession("s", sub, "agent-a"), "another context's window was not compacted")
        assertNull(s.getReadCache("s", "/a"))
        assertNotNull(s.getReadCache("other", "/a"))
        s.putArchiveFor("s", b("main-body"), dir, null)
        assertNotNull(s.archiveInSession("s", main, null))
        removeDirAll(dir)
    }

    @Test fun aForgottenRowIsNotTakenOverByAnotherSession() {
        val dir = tmpDir("t929-owner")
        val s = Store.openInMemory()
        val sha = s.putArchiveFor("a", b("same"), dir, null)
        s.forgetSeen("a", null)
        s.putArchiveFor("b", b("same"), dir, null)
        assertNull(s.archiveInSession("b", sha, null))
        removeDirAll(dir)
    }

    @Test fun archiveInSessionQueryPlanUsesTheSessionTsIndex() {
        val s = Store.openInMemory()
        val plan =
            s
                .conn {
                    it.query(
                        "EXPLAIN QUERY PLAN SELECT archive.id, (SELECT COUNT(*) FROM measurements WHERE measurements.session = archive.session AND measurements.ts > archive.ts) " +
                            "FROM archive WHERE archive.id = 'x' AND archive.session = 's' AND archive.agent_id IS NULL",
                    ) { r -> r.string(3) }
                }.joinToString(" | ")
        assertTrue(plan.contains("USING COVERING INDEX measurements_session_ts") || plan.contains("USING INDEX measurements_session_ts"), plan)
        assertFalse(plan.contains("SCAN measurements"), plan)
    }

    @Test fun archiveInSessionStaysFastWith100kMeasurements() {
        val dir = tmpDir("t210")
        val s = Store.openInMemory()
        val sha = s.putArchive("s-target", b("needle"), dir)
        s.conn { db -> db.transaction { for (i in 0L until 100_000L) db.execute("INSERT INTO measurements (ts, session, plugin, kind, before_bytes, after_bytes, est_before, est_after) VALUES (?, ?, 'cmd', 'rule', 1, 1, 1, 1)", i, if (i % 7 == 0L) "s-target" else "s-other") } }
        val start = TimeSource.Monotonic.markNow()
        val hit = s.archiveInSession("s-target", sha, null)!!
        val elapsed = start.elapsedNow()
        assertEquals(sha, hit.first)
        assertTrue(elapsed.inWholeMilliseconds < 200, "archive_in_session took $elapsed")
        removeDirAll(dir)
    }

    @Test fun expandFreezesEverySessionPointingAtTheArchive() {
        val dir = tmpDir("t453-exp")
        val s = Store.openInMemory()
        val id = s.putArchive("a", b("body"), dir)
        s.putArchiveDecision("tu-1", id, "a", "p")
        s.putArchiveDecision("tu-1", id, "b", "p")
        assertEquals(2, s.markExpanded(id))
        assertEquals(0, s.markExpanded(id), "already frozen")
        assertTrue(s.archiveDecision("a", "tu-1")!!.expanded)
        assertTrue(s.archiveDecision("b", "tu-1")!!.expanded)
        assertEquals(2L to 2L, s.archiveDecisionCounts())
        removeDirAll(dir)
    }

    @Test fun markAndRecordAreAtomic() {
        val dir = tmpDir("t208")
        val s = Store.openInMemory()
        val id = s.putArchive("a", b("body"), dir)
        s.putArchiveDecision("tu-1", id, "a", "p")
        val err = assertFails { s.markExpandedRecorded("a", id, meas("archive", "expand", ULong.MAX_VALUE, 4u, 0u, 1u, refId = id)) }
        assertTrue(err.message!!.contains("before_bytes"), err.message)
        assertFalse(s.archiveDecision("a", "tu-1")!!.expanded, "a rolled-back measurement insert must roll back the freeze too")
        assertEquals(0, s.measurementCount("archive"))
        removeDirAll(dir)
    }

    @Test fun retentionKeepsPluginArchivesWithoutCallIo() {
        val dir = tmpDir("retain-plugin")
        val archive = "$dir/archive"
        Store.open("$dir/rtok.db").use { s ->
            s.upsertSession("sess", 1, null, null, "mcp")
            val withDecision = b("archive with decision")
            val archId = s.putArchive("sess", withDecision, archive)
            s.putArchiveDecision("tu-1", archId, "sess", "pointer")
            s.markExpanded(archId)
            val read = b("read/cmd style archive")
            val readId = s.putArchive("sess", read, archive)
            assertEquals(0, s.runRetention(1u, 3u))
            assertEquals(1L to 1L, s.archiveDecisionCounts())
            assertContentEquals(withDecision, s.getArchive(archId, archive))
            assertContentEquals(read, s.getArchive(readId, archive))
        }
        removeDirAll(dir)
    }

    @Test fun spillArchiveCarriesSession() {
        val dir = tmpDir("arch-sess")
        val s = Store.openInMemory()
        s.upsertSession("sess-a", 1, null, null, null)
        val id = s.insertCall("sess-a", "proxy", "api_request", 1, null, null, null, null)
        s.insertCallIo(id, ByteArray(70 * 1024) { 'x'.code.toByte() }, null, 64 * 1024, dir)
        assertEquals("sess-a", s.conn { it.stringOf("SELECT session FROM archive LIMIT 1") })
        removeDirAll(dir)
    }

    @Test fun spillOverCapWithoutArchiveDirSkipsHashing() {
        val s = Store.openInMemory()
        s.upsertSession("s", 1, null, null, null)
        val id = s.insertCall("s", "hook", "hook", 1, null, null, null, null)
        val big = ByteArray(70 * 1024) { 'x'.code.toByte() }
        s.insertCallIo(id, big, big, 64 * 1024, null)
        assertEquals(listOf<String?>(null, null, null, null), s.conn { it.queryOne("SELECT request_sha256, response_sha256, request_archive, response_archive FROM call_io WHERE call_id = ?", id) { r -> (0..3).map { i -> r.stringOrNull(i) } } })
    }

    @Test fun hexSha256MatchesFips1802AbcVector() = assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hexSha256(b("abc")))

    @Test fun inlineSha256MatchesStoredText() {
        val s = Store.openInMemory()
        s.upsertSession("s", 1, null, null, null)
        val id = s.insertCall("s", "mcp", "mcp_call", 1, null, null, null, null)
        s.insertCallIo(id, b("plain"), null, 1 shl 20, null)
        val (text, sha) = s.conn { it.queryOne("SELECT request_json, request_sha256 FROM call_io WHERE call_id = ?", id) { r -> r.string(0) to r.string(1) } }!!
        assertEquals("plain", text)
        assertEquals(hexSha256(b(text)), sha)
        assertContentEquals(b("plain"), s.callIoRequest(id))
        val bad = byteArrayOf('b'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 0xff.toByte(), 0xfe.toByte(), 'o'.code.toByte(), 'k'.code.toByte())
        val id2 = s.insertCall("s", "mcp", "mcp_call", 1, null, null, null, null)
        s.insertCallIo(id2, bad, null, 1 shl 20, null)
        val (text2, sha2) = s.conn { it.queryOne("SELECT request_json, request_sha256 FROM call_io WHERE call_id = ?", id2) { r -> r.string(0) to r.string(1) } }!!
        assertEquals("bad\uFFFD\uFFFDok", text2)
        assertEquals(hexSha256(bad), sha2)
        assertContentEquals(bad, s.callIoRequest(id2))
    }

    @Test fun insertMeasurementsOnceIsAllOrNone() {
        val s = Store.openInMemory()

        fun row(est: UInt) = meas("memory", "recall", 1u, 1u, est, 1u)
        s.insertMeasurementsOnce("s", listOf(row(1u), row(2u)), null)
        assertEquals(2, s.measurementCount("memory"))
        assertFails { s.insertMeasurementsOnce("s", listOf(row(3u), row(Int.MAX_VALUE.toUInt() + 1u)), null) }
        assertEquals(2, s.measurementCount("memory"))
    }

    @Test fun insertMeasurementRejectsOutOfRangeEstimates() {
        val err = assertFails { Store.openInMemory().insertMeasurement("s", meas("cmd", "rule", 1u, 1u, Int.MAX_VALUE.toUInt() + 1u, 1u)) }
        assertTrue(err.message!!.contains("est_before"), err.message)
    }

    @Test fun memoryRecallTotalsSumsRecallsInTheWindowOnly() {
        val s = Store.openInMemory()

        fun recall(
            session: String,
            plugin: String,
            kind: String,
            b: ULong,
            a: ULong,
        ) = s.insertMeasurement(session, meas(plugin, kind, b, a, 0u, 0u))
        assertEquals(Triple(0uL, 0L, 0L), s.memoryRecallTotals(0))
        recall("s1", "memory", "recall", 1000u, 100u)
        assertEquals(Triple(1uL, 1000L, 100L), s.memoryRecallTotals(0))
        recall("s2", "memory", "recall", 500u, 50u)
        recall("s3", "memory", "recall", 250u, 25u)
        recall("s3", "memory", "save", 9999u, 9999u)
        recall("s3", "read", "recall", 9999u, 9999u)
        assertEquals(Triple(3uL, 1750L, 175L), s.memoryRecallTotals(0))
        val now = unixNow()
        s.conn { it.execute("UPDATE measurements SET ts = ? WHERE session = 's1'", now - 86_400) }
        assertEquals(Triple(2uL, 750L, 75L), s.memoryRecallTotals(now - 3600))
        assertEquals(Triple(0uL, 0L, 0L), s.memoryRecallTotals(now + 3600))
    }

    @Test fun callIoArchivesNamesOnlySpilledBodies() {
        val dir = tmpDir("call-io-arch")
        val s = Store.openInMemory()
        s.upsertSession("sess", 1, null, null, null)

        fun call() = s.insertCall("sess", "proxy", "api_request", 1, null, null, null, null)
        val cap = 16
        val big = ByteArray(64) { 'x'.code.toByte() }
        assertEquals(null to null, s.callIoArchives(9999), "no call_io row")
        val inline = call()
        s.insertCallIo(inline, b("small"), b("tiny"), cap, dir)
        assertEquals(null to null, s.callIoArchives(inline), "inline")
        val reqOnly = call()
        s.insertCallIo(reqOnly, big, b("ok"), cap, dir)
        val (req, res) = s.callIoArchives(reqOnly)
        assertEquals(hexSha256(big), req)
        assertTrue(exists("$dir/$req"), "the id names the archived body")
        assertNull(res)
        val both = call()
        val bigRes = ByteArray(64) { 'y'.code.toByte() }
        s.insertCallIo(both, big, bigRes, cap, dir)
        val (req2, res2) = s.callIoArchives(both)
        assertNotNull(req2)
        assertEquals(hexSha256(bigRes), res2)
        val noDir = call()
        s.insertCallIo(noDir, big, big, cap, null)
        assertEquals(null to null, s.callIoArchives(noDir))
        removeDirAll(dir)
    }

    @Test fun migrationsListMatchesTheDirectory() {
        // The embedded list is generated from rtok-store/migrations at build time; check it against the Rust crate's own order.
        val versions = Migrations.versions()
        assertEquals(versions.sorted(), versions)
        assertEquals(versions.size, versions.toSet().size)
        assertEquals(EMBEDDED_MIGRATIONS.map { it.first.substringBefore('_') }, versions)
    }

    // ---- schema drift: the Kotlin-migrated database against the Rust crate's schema.rs + schema_snapshot.txt ----

    private val RAW_SQL_TABLES =
        listOf(
            "notes_fts",
            "notes_fts_data",
            "notes_fts_idx",
            "notes_fts_docsize",
            "notes_fts_config",
            "observations_fts",
            "observations_fts_data",
            "observations_fts_idx",
            "observations_fts_docsize",
            "observations_fts_config",
            "symbols_fts",
            "symbols_fts_data",
            "symbols_fts_idx",
            "symbols_fts_docsize",
            "symbols_fts_config",
            "doc_items_fts",
            "doc_items_fts_data",
            "doc_items_fts_idx",
            "doc_items_fts_docsize",
            "doc_items_fts_config",
            "__diesel_schema_migrations",
        )

    private class SchemaTable(
        val name: String,
        val pk: List<String>,
        val cols: List<Pair<String, String>>,
    )

    private fun fixture(name: String) = readToString("${getenv("RTOK_FIXTURES")!!.toKString()}/$name")

    private fun schemaTables(): List<SchemaTable> {
        val lines = fixture("schema.rs").lines().map { it.trim() }.iterator()
        val out = ArrayList<SchemaTable>()
        while (lines.hasNext()) {
            if (lines.next() != "diesel::table! {") continue
            val head = lines.next()
            val name = head.split(Regex("\\s+"))[0]
            val pk = head.substring(head.indexOf('(') + 1, head.indexOf(')')).split(',').map { it.trim() }
            val cols = ArrayList<Pair<String, String>>()
            var rename: String? = null
            while (lines.hasNext()) {
                val l = lines.next()
                if (l == "}") break
                if (l.startsWith("#[sql_name = \"") && l.endsWith("\"]")) {
                    rename = l.removePrefix("#[sql_name = \"").removeSuffix("\"]")
                } else if (" -> " in l) {
                    val (c, ty) = l.split(" -> ", limit = 2)
                    cols += (rename ?: c) to ty.trimEnd(',')
                    rename = null
                }
            }
            out += SchemaTable(name, pk, cols)
        }
        return out
    }

    private fun sqliteAffinity(d0: String): String {
        val d = d0.uppercase()
        return when {
            "INT" in d -> "INTEGER"
            "CHAR" in d || "CLOB" in d || "TEXT" in d -> "TEXT"
            "REAL" in d || "FLOA" in d || "DOUB" in d -> "REAL"
            else -> "OTHER"
        }
    }

    private fun dieselAffinity(ty: String) =
        when (ty) {
            "Integer", "BigInt" -> "INTEGER"
            "Text" -> "TEXT"
            "Double" -> "REAL"
            else -> null
        }

    private fun liveSnapshot(db: Db): String =
        db
            .query("SELECT type AS kind, name, tbl_name, sql FROM sqlite_master WHERE type IN ('table', 'index', 'trigger')") {
                listOf(it.string(0), it.string(1), it.string(2), it.stringOrNull(3) ?: "")
            }.sortedWith(compareBy({ it[0] }, { it[1] }))
            .joinToString("") { (k, n, t, sql) -> "$k|$n|$t|${sql.split(Regex("\\s+")).filter { w -> w.isNotEmpty() }.joinToString(" ")}\n" }

    private fun schemaDrift(db: Db): List<String> {
        val out = ArrayList<String>()
        val live = liveSnapshot(db)
        val liveTables =
            live
                .lines()
                .mapNotNull { if (it.startsWith("table|")) it.removePrefix("table|").split('|')[0] else null }
                .distinct()
                .sorted()
        val tables = schemaTables()
        assertTrue(tables.size >= 16, "parsed ${tables.size} table! macros")
        val expected = (tables.map { it.name } + RAW_SQL_TABLES).distinct().sorted()
        if (expected != liveTables) out += "migrated tables $liveTables vs table! ∪ RAW_SQL_TABLES $expected"
        for (t in tables) {
            val cols = db.query("SELECT name, type AS ty, \"notnull\", pk FROM pragma_table_xinfo('${t.name}')") { listOf<Any>(it.string(0), it.string(1), it.int(2), it.int(3)) }
            val liveNames = cols.map { it[0] as String }.distinct().sorted()
            val wantNames =
                t.cols
                    .map { it.first }
                    .distinct()
                    .sorted()
            if (liveNames != wantNames) {
                out += "${t.name}: schema.rs columns $wantNames vs live $liveNames"
                continue
            }
            for ((name, ty) in t.cols) {
                val c = cols.first { it[0] == name }
                val nullable = ty.startsWith("Nullable<")
                val base = if (nullable) ty.removePrefix("Nullable<").trimEnd('>') else ty
                val wantPk = name in t.pk
                val tyOk = dieselAffinity(base)?.let { sqliteAffinity(c[1] as String) == it } ?: true
                val pkOk = wantPk == ((c[3] as Int) > 0)
                val nnOk = wantPk || nullable != ((c[2] as Int) != 0)
                if (!(tyOk && pkOk && nnOk)) out += "${t.name}.$name: schema.rs `$ty` pk=$wantPk vs live `${c[1]}` notnull=${c[2]} pk=${c[3]}"
            }
        }
        val want = fixture("schema_snapshot.txt")
        if (live != want) out += "sqlite_master drifted from schema_snapshot.txt\n--- want\n$want--- live\n$live"
        return out
    }

    @Test fun schemaMatchesTheMigratedTablesAndSnapshot() {
        val m = Store.openInMemory().conn { schemaDrift(it) }
        assertTrue(m.isEmpty(), m.joinToString("\n"))
    }

    private fun driftAfter(vararg sql: String) =
        Store.openInMemory().conn { db ->
            sql.forEach { db.exec(it) }
            schemaDrift(db)
        }

    @Test fun schemaDriftCatchesAChangedDefault() {
        val m = driftAfter("DROP TABLE otel_export", "CREATE TABLE otel_export (stream TEXT PRIMARY KEY, mark BIGINT NOT NULL DEFAULT 1)")
        assertTrue(m.any { "schema_snapshot.txt" in it }, m.toString())
    }

    @Test fun schemaDriftCatchesADroppedIndex() = assertTrue(driftAfter("DROP INDEX usage_call").any { "schema_snapshot.txt" in it })

    @Test fun schemaDriftCatchesAColumnRemovedFromTheLiveTable() {
        val m = driftAfter("DROP TABLE otel_export", "CREATE TABLE otel_export (stream TEXT PRIMARY KEY)")
        assertTrue(m.any { it.startsWith("otel_export:") }, m.toString())
    }

    @Test fun archiveFileWriteReplacesTheTargetAtomically() {
        val dir = tmpDir("t324-atomic")
        val sha = hexSha256(b("complete body"))
        writeString("$dir/$sha", "trunc")
        link("$dir/$sha", "$dir/reader-view")
        val (path, created) = writeArchiveFile(dir, sha, b("complete body"))
        assertFalse(created)
        assertEquals("complete body", readToString(path))
        assertEquals("trunc", readToString("$dir/reader-view"), "written in place, not renamed")
        assertEquals(listOf(sha, "reader-view"), listDir(dir).sorted(), "temp file left behind")
        removeDirAll(dir)
    }
}
