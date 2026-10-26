package rtok.store

import rtok.plugin.sdk.SymbolRow
import rtok.sys.removeDirAll
import kotlin.test.*

/** Port of symbols_tests.rs. */
class SymbolsTest {
    private val s = Store.openInMemory()

    @Test fun topRefsRankByCountThenName() {
        s.index("/r", "a.rs", symRow("foo", 1, true), symRow("bar", 2, true), symRow("aaa", 3, true), symRow("zed", 4, true), symRow("foo", 10, false), symRow("foo", 11, false),
            symRow("bar", 12, false), symRow("bar", 13, false), symRow("aaa", 14, false), symImport("foo", 20))
        val got = s.symbolTopRefs("/r", 10)
        assertEquals(listOf("bar" to 2L, "foo" to 2L, "aaa" to 1L, "zed" to 0L), got.map { it.a to it.b }); assertEquals(got, s.symbolTopRefs("/r", 10))
    }

    @Test fun topRefsPicksFirstDefSite() {
        s.index("/r", "b.rs", symRow("dup", 5, true)); s.index("/r", "a.rs", symRow("dup", 9, true), symRow("dup", 3, true), symRow("dup", 1, false))
        assertEquals(listOf(Quad("dup", 1L, "a.rs", 3)), s.symbolTopRefs("/r", 4))
    }

    @Test fun impactExcludesRefEdgeNameAlreadyOnTheChain() {
        s.index("/r1", "a.rs", symRow("X", 1, true), symRef("N", 2, "X")); s.index("/r1", "b.rs", symRow("X", 1, true), symRef("X", 2, "X"))
        assertEquals(listOf(Triple(1u, "a.rs", "X")), s.symbolImpact("/r1", "N", 4u))
    }

    @Test fun impactExcludesImportFollowNameAlreadyOnTheChain() {
        s.index("/r2", "c.rs", symImport("N2", 1), symRow("M", 2, true)); s.index("/r2", "e.rs", symImport("M", 1), symRow("M", 2, true))
        assertEquals(listOf(Triple(1u, "c.rs", "M")), s.symbolImpact("/r2", "N2", 4u))
    }

    @Test fun impactRecordsAnEmptyOutNameButDoesNotFollowIt() {
        s.index("/r3", "a.rs", symRow("N3", 2, false)); s.index("/r3", "b.rs", symImport("", 1), symRow("LEAK", 2, true))
        assertEquals(listOf(Triple(1u, "a.rs", "")), s.symbolImpact("/r3", "N3", 4u))
    }

    @Test fun importSpecMatchesTheDefiningFile() {
        assertTrue(importMatchesFile("crate::a::parse", "src/a.rs")); assertTrue(importMatchesFile("crate::plugins::toon::encode", "src/plugins/toon/mod.rs"))
        assertTrue(importMatchesFile("rtok_sys::unlock", "crates/rtok-sys/src/lib.rs")); assertTrue(importMatchesFile("rtok_mcp::spec::Format", "crates/rtok-mcp/src/spec.rs"))
        assertFalse(importMatchesFile("crate::a::parse", "src/b.rs")); assertFalse(importMatchesFile("figment::providers::Format", "src/doctor/hooks.rs"))
        assertFalse(importMatchesFile("parse", "src/a.rs"))
    }

    @Test fun importedDefsAreTheCandidateFilesAReferenceImports() {
        s.index("/r", "src/a.rs", symRow("parse", 1, true)); s.index("/r", "src/b.rs", symRow("parse", 1, true))
        s.index("/r", "src/c.rs", symImport("parse", 1, "crate::a::parse"), symRef("parse", 3, "go"))
        assertEquals(listOf("src/c.rs" to "src/a.rs"), s.symbolImportedDefs("/r", "parse"))
        s.rebuildSymbolIdf("/r"); assertEquals(1L to 3L, s.symbolNameFreq("/r", "parse")); assertEquals(0L to 3L, s.symbolNameFreq("/r", "missing"))
    }

    @Test fun symbolFtsRanksANameTokenAboveADocHit() {
        fun doc(name: String, line: Int, sig: String, d: String) = SymbolRow(name, "function", line, true, line, "", signature = sig, doc = d)
        s.index("/r", "a.rs", doc("body_lines", 1, "fn body_lines() {}", "Source of one definition"), doc("mid", 2, "fn mid(source: Lines)", ""),
            doc("other", 3, "fn other()", "truncated source lines in the comment"))
        assertTrue(s.symbolFts("/r", "", 10).isEmpty())
        assertEquals(listOf("body_lines", "mid", "other"), s.symbolFts("/r", "truncated source lines", 10).map { it.b })
    }

    @Test fun rebuildSymbolIdfLeavesPlannerStatisticsForSymbols() {
        for ((path, name) in listOf("a.rs" to "parse", "b.rs" to "emit")) s.index("/r", path, symRow(name, 1, true))
        s.rebuildSymbolIdf("/r")
        val idx = s.conn { it.query("SELECT idx FROM sqlite_stat1 WHERE tbl = 'symbols'") { r -> r.string(0) } }
        assertTrue("symbols_root_name" in idx && "symbols_root_path" in idx, "$idx")
    }

    @Test fun nameFreqFollowsARebuiltIdfCache() {
        val dir = tmpDir("t646-idf"); val reader = Store.open("$dir/rtok.db"); val writer = Store.open("$dir/rtok.db")
        writer.index("/r", "a.rs", symRow("parse", 1, false)); writer.rebuildSymbolIdf("/r")
        assertEquals(1L to 1L, reader.symbolNameFreq("/r", "parse")); assertEquals(0L to 1L, reader.symbolNameFreq("/r", "emit"))
        writer.index("/r", "b.rs", symRow("parse", 1, false)); writer.rebuildSymbolIdf("/r")
        assertEquals(2L to 2L, reader.symbolNameFreq("/r", "parse")); assertEquals(0L to 0L, reader.symbolNameFreq("/other", "parse"))
        reader.close(); writer.close(); removeDirAll(dir)
    }
}
