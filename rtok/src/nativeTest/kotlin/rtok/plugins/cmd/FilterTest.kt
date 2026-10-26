package rtok.plugins.cmd

import rtok.sys.removeDirAll
import kotlin.test.*

class FilterTest {
    @Test fun gitStatusFromStdinDropsBoilerplate() {
        val out = Filter.run("git status", "On branch main\nChanges not staged for commit:\n  (use \"git add <file>...\" to update what will be committed)\n\tmodified:   src/lib.rs\n")
        assertTrue(out.contains("On branch main") && out.contains(" M src/lib.rs") && !out.contains("Changes not staged"), out)
    }

    @Test fun readFamilyRecordsARuleRowAndExpandTrailer() {
        val (c, dir) = cmdConfig("filter-read")
        val raw = numbered(80)
        val out = Filter.runWithStore(c, "read src/lib.rs", raw)
        assertTrue(out.contains("[rtok ") && out.contains(" lines]") && out.length < raw.length, out)
        val rows = cmdRows(c)
        assertEquals(1, rows.size); assertEquals("rule", rows[0].kind)
        assertTrue(rows[0].refId.orEmpty().startsWith("read:"), rows[0].refId)
        removeDirAll(dir)
    }

    @Test fun grepFindLsEachRecordAFamilyRow() {
        val (c, dir) = cmdConfig("filter-families")
        val raw = numbered(80)
        for (cmd in listOf("grep foo", "find *.rs", "ls src")) assertTrue(Filter.runWithStore(c, cmd, raw).contains("expand"), cmd)
        val rows = cmdRows(c)
        assertEquals(listOf("grep", "find", "ls"), rows.map { it.refId.orEmpty().substringBefore(':') })
        assertEquals(List(3) { "rule" }, rows.map { it.kind })
        removeDirAll(dir)
    }
}
