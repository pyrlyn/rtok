// Port of src/agents/junk.rs tests (rtok's own home: log siblings, dry run, no store, the report and its text) and
// src/worktree/mod.rs `noted_table`.
package rtok.agents

import rtok.Col
import rtok.config.Config
import rtok.support.diskConfig
import rtok.support.installDb
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.parent
import rtok.sys.writeBytes
import rtok.worktree.notedTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TWELVE = 12L
private const val FILES = 5u

class JunkTest {
    private fun logDir(cfg: Config): String = parent(cfg.log.path)!!.also(::createDirAll)

    private fun config(
        tag: String,
        files: UInt,
    ): Pair<Config, String> {
        installDb()
        val (cfg, dir) = diskConfig(tag)
        return cfg.copy(log = cfg.log.copy(files = files)) to dir
    }

    private fun put(
        path: String,
        text: String,
    ) = writeBytes(path, text.encodeToByteArray())

    @Test fun staleLogSiblingsAreFoundPastTheCapAndCurrentOnesAreNot() {
        val (cfg, _) = config("junk-log-siblings", 2u)
        val logs = logDir(cfg)
        put("$logs/rtok.log.1", "a")
        put("$logs/rtok.log.2", "bb")
        put("$logs/rtok.log.6", "stale")
        put("$logs/rtok.log.rotate.lock", "")
        assertEquals(listOf("$logs/rtok.log.6"), JunkOwn.scan(cfg).filter { it.kind == "log" }.map { it.path })
    }

    @Test fun dryRunListsWithoutDeletingAndYesRemoves() {
        val (cfg, _) = config("junk-dry-run", 1u)
        val stale = "${logDir(cfg)}/rtok.log.5"
        put(stale, "junk")
        assertEquals(1, JunkOwn.run(cfg, false).size)
        assertTrue(exists(stale), "dry run must not delete anything")
        assertEquals("removed", JunkOwn.run(cfg, true)[0].note)
        assertFalse(exists(stale))
    }

    @Test fun noDbAtTheConfiguredPathIsSkippedNotFatal() {
        val (cfg, _) = config("junk-no-db", FILES)
        assertEquals(0, JunkOwn.scan(cfg).size)
    }

    @Test fun reportSumsTheKindsTheClearPlanListsAndTheTextPrintsThem() {
        val (cfg, dir) = config("junk-report", 1u)
        val logs = logDir(cfg)
        put("$logs/rtok.log.4", "1234")
        put("$logs/rtok.log.5", "12345678")
        // Fixture roots, not the environment: that would size this machine's real caches.
        val report = Junk.reportWith(cfg, JunkRoots(dir, { null }), JunkOptions(), JunkCache.AGENT_SCAN_LIMIT)
        val planned = JunkOwn.scan(cfg).sumOf { it.bytes }
        assertEquals(TWELVE, planned)
        assertEquals(planned, report.freedDefaultBytes)
        val freed = report.agents.map { it.freedDefaultBytes }
        assertTrue(freed.zipWithNext().all { (a, b) -> a >= b }, "$freed")
        val rtok = report.agents.first { it.name == "rtok" }
        assertEquals("log" to 2, rtok.kinds[0].kind to rtok.kinds[0].items)
        assertTrue(rtok.folders.any { it.path == logs })
        val text = JunkList.toList(report, JunkView(exact = true))
        assertTrue(text.contains("log (safe): 2 items, 12"), text)
        assertTrue(text.contains("Freed by `clear`: 12"), text)
    }

    @Test fun theDryRunTableEndsWithThePreviewAndTheTotals() {
        val (cfg, _) = config("junk-table", 1u)
        put("${logDir(cfg)}/rtok.log.3", "abc")
        val text = JunkOwn.toTable(JunkOwn.run(cfg, false), false)
        assertTrue(text.startsWith("action kind path"), text)
        assertTrue(text.contains("past `[log] files` = 1"), text)
        assertTrue(text.contains("1 path, 1 file"), text)
        assertTrue(text.endsWith("nothing changed; rerun with --yes\n"), text)
        assertTrue(JunkOwn.toTable(emptyList(), false).endsWith("\nnothing to clear\n"))
    }

    @Test fun aNotedTableAppendsOneNotePerLine() {
        val out = notedTable(listOf(Col.left(0), Col.right(0)), listOf(listOf("a", "n"), listOf("bb", "10")), listOf("x"))
        assertEquals("a   n  note\nbb 10  x\n", out)
    }

    @Test fun aFolderLinkIsAnOsc8LinkOnlyForAnAbsolutePath() {
        assertEquals("\u001b]8;;file:///a%20b\u001b\\t\u001b]8;;\u001b\\", JunkList.folderLink("/a b", "t"))
        assertEquals("t", JunkList.folderLink("rel", "t"))
        assertEquals(listOf("/a", "/b"), Junk.outermost(listOf("/b", "/a/x", "/a")))
    }
}
