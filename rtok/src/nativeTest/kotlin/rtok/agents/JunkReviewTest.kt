// Port of src/agents/junk_review.rs tests.
package rtok.agents

import rtok.agent.sdk.BACKUP_DIR
import rtok.config.AgentsJunk
import rtok.config.JunkExtra
import rtok.support.tmpDir
import rtok.worktree.KEPT_IDLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DAY = 86_400L
private const val MINUTE = 60L
private const val BIG = 100
private const val HEAD = 64
private const val OLD = 31L
private const val YOUNG = 29L
private const val ANCIENT = 40L
private const val THREE = 3
private const val TEN = 10L

class JunkReviewTest {
    private fun cx(dir: String) = JunkCtx(dir, JunkCache.DEFAULT_IDLE, nowNanos())

    private fun roots(home: String) = JunkRoots(home, { null })

    private fun put(
        path: String,
        size: Int = 1,
    ) = putFile(path, ByteArray(size) { 'x'.code.toByte() })

    @Test fun aLogPastKeepLogsDaysIsReviewJunkAndAFresherOneStays() {
        val root = tmpDir("review-logs")
        val debug = "$root/debug"
        put("$debug/old.log", BIG)
        put("$debug/new.log")
        put("$root/single.log")
        ageBy("$debug/old.log", OLD * DAY)
        ageBy("$debug/new.log", YOUNG * DAY)
        ageBy("$root/single.log", ANCIENT * DAY)
        val dirs = listOf(debug, "$root/single.log")

        val items = logItems(dirs, AgentsJunk(), cx(root), LIMIT)

        assertEquals(THREE, items.size, "$items")
        for (gone in listOf("old.log", "single.log")) {
            val i = junkRow(items, gone)
            assertTrue(i.counted && Triple(i.kind, i.cls, i.evidence) == Triple("logs", "review", JunkCache.SECTION_22))
        }
        assertEquals(KEPT_IDLE, junkRow(items, "new.log").kept)
        val week = AgentsJunk(keepLogsDays = 7u)
        assertTrue(logItems(dirs, week, cx(root), LIMIT).all { it.counted }, "29 days is past 7")
    }

    @Test fun onlyBackupGenerationsPastTheCapAreListedAndTheNewestAlwaysStays() {
        val dir = tmpDir("review-backups")
        val bak = "$dir/$BACKUP_DIR"
        listOf("s.json.bak-1", "s.json.bak-2", "s.json.bak-3", "o.json.bak-9", "notes.txt").forEach { put("$bak/$it") }
        val one = listOf(dir)

        val items = backupItems(one, 2u, LIMIT)

        assertEquals(1, items.size, "$items")
        val i = items[0]
        assertTrue(i.path.endsWith("s.json.bak-1") && i.counted)
        assertEquals(Triple("backups", "review", JunkCache.RTOK_OWN), Triple(i.kind, i.cls, i.evidence))
        assertEquals(2, backupItems(one, 1u, LIMIT).size)
        assertTrue(backupItems(one, 0u, LIMIT).isEmpty(), "0 keeps every copy")
    }

    @Test fun legacyBackupGenerationsPastTheCapAreListedToo() {
        val dir = tmpDir("review-legacy-backups")
        listOf("_backup/s.json.bak-1", "_backup/s.json.bak-2").forEach { put("$dir/$it") }
        put("$dir/.backup_configs/s.json.bak-3")

        val items = backupItems(listOf(dir), 1u, LIMIT)

        assertEquals(1, items.size, "$items")
        assertTrue(items[0].path.endsWith("_backup/s.json.bak-1"))
    }

    @Test fun geminiRestorePointsAreSizedAndNeverCounted() {
        val home = tmpDir("review-snapshots")
        put("$home/.gemini/history/abc/HEAD", HEAD)
        put("$home/.gemini/tmp/h1/checkpoints/c.json")
        put("$home/.gemini/tmp/h2/chats/s.json")

        val items = snapshotItems(roots(home), LIMIT)

        assertEquals(2, items.size, "$items")
        for (i in items) {
            assertTrue(!i.counted && i.cls == "never" && i.bytes > 0)
            assertTrue(i.kept!!.contains("/restore"))
        }
    }

    @Test fun extraPathsBecomeItemsOfTheirKindForTheirHostOnly() {
        val home = tmpDir("review-extra")
        put("$home/scratch/old.tmp")
        put("$home/scratch/new.tmp")
        ageBy("$home/scratch/old.tmp", 2 * DAY)
        ageBy("$home/scratch/new.tmp", MINUTE)
        put("$home/applogs/a.log")
        ageBy("$home/applogs/a.log", ANCIENT * DAY)
        val junk =
            AgentsJunk(
                extra =
                    listOf(
                        JunkExtra("cursor", "cache", "~/Library/Caches/Cursor"),
                        JunkExtra("cursor", "temp", "~/scratch"),
                        JunkExtra("cursor", "logs", "~/applogs"),
                        JunkExtra("cursor", "crash-dumps", "~/dumps"),
                        JunkExtra("codex", "cache", "~/elsewhere"),
                        JunkExtra("cursor", "sessions", "~/sessions"),
                        JunkExtra("cursor", "cache", "relative/dir"),
                    ),
            )

        val (owned, items) = extraItems(junk, "cursor", "$home/.rtok", home, cx(home), LIMIT)

        assertEquals(1, owned.size)
        assertEquals("$home/Library/Caches/Cursor", owned[0].path)
        assertEquals(JunkReview.EXTRA, owned[0].evidence)
        assertEquals(THREE, items.size, "$items")
        assertTrue(items.all { it.evidence == JunkReview.EXTRA })
        assertTrue(!junkRow(items, "new.tmp").counted, "young temp stays")
        assertEquals("temp" to "safe", junkRow(items, "old.tmp").let { it.kind to it.cls })
        assertEquals("logs" to "review", junkRow(items, "a.log").let { it.kind to it.cls })
    }

    @Test fun excludeKeepsAMatchAndAFolderHoldingOneAndABadGlobKeepsAll() {
        val home = tmpDir("review-exclude")
        put("$home/c1/keep.me")
        put("$home/c2/blob")
        put("$home/t/old.tmp")

        fun item(p: String) = junkItem("cache", "$home/$p", JunkCache.SECTION_22, null, LIMIT)

        fun fresh() = mutableListOf(item("c1"), item("c2"), item("t/old.tmp"))
        val rtok = "$home/.rtok"

        val items = fresh()
        exclude(items, listOf("**/keep.me", "~/t/*.tmp"), rtok, home, LIMIT)
        assertEquals(listOf(JunkReview.EXCLUDED, null, JunkReview.EXCLUDED), items.map { it.kept })

        val bad = fresh()
        exclude(bad, listOf("["), rtok, home, LIMIT)
        assertTrue(bad.all { it.kept!!.startsWith("invalid") }, "$bad")
    }

    @Test fun anExtraCrashFolderGivesSafeOldDumpsAndReviewYoungOnes() {
        val home = tmpDir("review-crash")
        put("$home/dumps/old.dmp")
        put("$home/dumps/young.dmp")
        ageBy("$home/dumps/old.dmp", TEN * DAY)
        ageBy("$home/dumps/young.dmp", 2 * DAY)
        val junk = AgentsJunk(extra = listOf(JunkExtra("cursor", "crash-dumps", "~/dumps")))

        fun get(j: AgentsJunk) = extraItems(j, "cursor", "$home/.rtok", home, cx(home), LIMIT).second

        val items = get(junk)

        assertEquals(2, items.size, "$items")
        assertTrue(items.all { it.kind == "crash-dumps" && it.counted && it.evidence == JunkReview.EXTRA })
        assertEquals("safe", junkRow(items, "old.dmp").cls)
        assertEquals("review", junkRow(items, "young.dmp").cls)
        assertTrue(get(junk.copy(crashDumpMinAgeDays = 1u)).all { it.cls == "safe" })
    }

    @Test fun macosReportsNamedForAHostAreListedAndNeverCleared() {
        val home = tmpDir("review-reports")
        val dir = "$home/Library/Logs/DiagnosticReports"
        listOf(
            "Cursor-2026-10-01-1200.ips",
            "cursor-agent_2026-10-01.crash",
            "Cursor Helper-1.diag",
            "Cursorless-1.ips",
            "Cursor-2026.txt",
            "Safari-2026.ips",
        ).forEach { put("$dir/$it") }
        val names = processNames(Hosts.host("cursor")!!)

        val items = diagnosticReports(names, roots(home), LIMIT)

        assertEquals(
            listOf("Cursor Helper-1.diag", "Cursor-2026-10-01-1200.ips", "cursor-agent_2026-10-01.crash"),
            items.map { it.path.substringAfterLast('/') }.sorted(),
        )
        assertTrue(items.all { !it.counted && it.kept == JunkKinds.NOT_DOCUMENTED && it.cls == "review" })
    }
}
