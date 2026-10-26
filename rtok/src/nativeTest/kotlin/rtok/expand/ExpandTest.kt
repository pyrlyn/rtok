package rtok.expand

import kotlin.test.*
import rtok.Expand
import rtok.Expand.filterLines
import rtok.Expand.parseRange
import rtok.Expand.renderLines
import rtok.ExpandException
import rtok.MIN_PREFIX
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.shortId
import rtok.store.*
import rtok.support.diskConfig

class ExpandTest {
    private fun cfg(name: String): Config = diskConfig("expand-$name").first
    private fun err(block: () -> Unit): String = assertFailsWith<ExpandException> { block() }.message!!

    @Test fun shortIdIsTheResolvableMinimum() {
        val id = "ab".repeat(32)
        assertEquals("abababab", shortId(id)); assertEquals("abc", shortId("abc")); assertTrue(shortId(id).length >= MIN_PREFIX)
    }

    @Test fun ambiguousPrefixListsTheLongerCandidates() {
        val pad = "0".repeat(52)
        val msg = Expand.ambiguousMessage("abababab", listOf("abababab1111$pad", "abababab2222$pad"))
        assertTrue("abababab1111" in msg && "abababab2222" in msg, msg)
    }

    @Test fun hugeGrepContextClampsToTheWholePayload(): Unit =
        assertEquals(listOf("1:a", "2:b", "3:HIT", "4:c"), filterLines("a\nb\nHIT\nc", null, "HIT", Int.MAX_VALUE))

    @Test fun malformedRangeIsRejectedBeforeArchiveLookup(): Unit =
        assertTrue("invalid line range" in err { Expand.run(cfg("bad-range"), "no-such", "3-2", null, 0) })

    @Test fun unknownIdIsErr(): Unit = assertTrue("unknown archive id" in err { Expand.run(cfg("unknown"), "no-such", null, null, 0) })

    @Test fun nonIdGetsTheTrailerHintAndAHexIdDoesNot() {
        val hint = Expand.unknownIdMessage("-")
        assertTrue(hint.startsWith("unknown archive id: - (") && "\"expand <id>\" trailer" in hint && "does not read stdin" in hint && '\n' !in hint, hint)
        assertTrue("trailer" in Expand.unknownIdMessage("/dev/stdin"))
        val hex = "ab".repeat(32)
        assertEquals("unknown archive id: $hex", Expand.unknownIdMessage(hex))
        val c = cfg("hint")
        assertTrue("does not read stdin" in err { Expand.run(c, "-", null, null, 0) })
        assertEquals("unknown archive id: $hex", err { Expand.run(c, hex, null, null, 0) })
    }

    @Test fun roundTripFromPutArchive() {
        val c = cfg("round")
        val id = Runtime.open(c, "expand").use { rt ->
            rt.store.putArchive("expand", "hello\nworld\n".encodeToByteArray(), c.core.archiveDir).also {
                assertEquals("hello\nworld\n", rt.store.getArchive(it, c.core.archiveDir)!!.decodeToString())
            }
        }
        assertEquals("hello\nworld\n", Expand.run(c, id, null, null, 0).decodeToString())
    }

    @Test fun movedArchiveDirStillReads() {
        val c = cfg("moved")
        val id = Runtime.open(c, "expand").use { it.store.putArchive("expand", "relocated\n".encodeToByteArray(), c.core.archiveDir) }
        val dest = rtok.sys.parent(c.core.archiveDir)!! + "/archive-moved"
        check(platform.posix.rename(c.core.archiveDir, dest) == 0)
        val c2 = c.copy(core = c.core.copy(archiveDir = dest))
        Runtime.open(c2, "expand").use { assertEquals("relocated\n", Expand.fetch(it, id)!!.decodeToString()) }
    }

    @Test fun missingFileIsUnknownId() {
        val c = cfg("gone")
        val id = Runtime.open(c, "expand").use { it.store.putArchive("expand", "bye\n".encodeToByteArray(), c.core.archiveDir) }
        rtok.sys.removeFile("${c.core.archiveDir}/$id")
        assertTrue("unknown archive id" in err { Expand.run(c, id, null, null, 0) })
    }

    /** T55.11: the CLI session differs from the proxy session that owns the decision; the freeze still lands. */
    @Test fun cliExpandFreezesTheOwningSessionsPointer() {
        val (c, id) = seeded("freeze-owner", "payload\n")
        Runtime.open(c, "expand").use { rt ->
            assertEquals("payload\n", Expand.fetch(rt, id)!!.decodeToString())
            assertTrue(rt.store.archiveDecision("proxy-sess", "tu-1")!!.expanded, "the writer's session must see the freeze")
        }
    }

    private fun seeded(name: String, body: String, pointer: (String) -> String = { "[archived $it]" }): Pair<Config, String> {
        val c = cfg(name)
        val id = Runtime.open(c, "proxy-sess").use { rt ->
            rt.store.putArchive("proxy-sess", body.encodeToByteArray(), c.core.archiveDir).also { rt.store.putArchiveDecision("tu-1", it, "proxy-sess", pointer(it)) }
        }
        return c to id
    }

    private fun frozenAndCharged(c: Config, id: String) = Runtime.open(c, "probe").use { rt ->
        rt.store.archiveDecision("proxy-sess", "tu-1")!!.expanded to rt.store.listMeasurements("archive").count { it.refId == id }
    }

    /** T534: a range past the end neither freezes nor charges; a valid range then freezes and records once. */
    @Test fun aRangePastTheEndNeitherFreezesNorCharges() {
        for ((body, spec) in listOf("only line\n" to "2-3", "" to "1")) {
            val (c, id) = seeded("range-past-end", body)
            assertTrue("exceeds line count" in err { Expand.run(c, id, spec, null, 0) })
            Runtime.open(c, "mcp-1").use { rt -> assertFailsWith<ExpandException> { Expand.fetchRange(rt, id, spec) } }
            assertEquals(false to 0, frozenAndCharged(c, id))
            if (body.isNotEmpty()) {
                assertEquals("only line\n", Expand.run(c, id, "1", null, 0).decodeToString())
                assertEquals(true to 1, frozenAndCharged(c, id))
            }
        }
    }

    @Test fun expandMeasurementAttributesToonPointers() {
        val (c, id) = seeded("toon-attr", "table\n") { "[toon $it]\na,b" }
        Runtime.open(c, "expand").use { rt ->
            assertNotNull(Expand.fetch(rt, id))
            assertTrue(rt.store.listMeasurements("toon").any { it.kind == "expand" })
        }
    }

    @Test fun grepIsRegexNumberedByArchiveLineAndFallsBackToLiteral() {
        val text = "alpha\nerror[E0308]: mismatched\nbeta\nerror[E0599]: no method\n"
        assertEquals(listOf("2:error[E0308]: mismatched", "4:error[E0599]: no method"), filterLines(text, null, """error\[E0\d+\]""", 0))
        assertEquals(listOf("4:error[E0599]: no method"), filterLines(text, "3-4", "error", 0))
        assertEquals(listOf("2:error[E0308]: mismatched"), filterLines(text, null, "[E0308", 0))
        assertEquals(listOf("alpha", "error[E0308]: mismatched"), filterLines(text, "1-2", null, 7))
    }

    @Test fun contextWindowsMergeAndSeparateOnDashDash() {
        assertEquals(listOf("1:a1", "2:a2", "3:HIT", "4:a4", "5:a5", "6:b1", "7:HIT", "8:b3", "9:b4"),
            filterLines("a1\na2\nHIT\na4\na5\nb1\nHIT\nb3\nb4\nb5\n", null, "HIT", 2))
        assertEquals(listOf("1:h1", "2:HIT", "3:x3", "--", "8:x8", "9:HIT"), filterLines("h1\nHIT\nx3\nx4\nx5\nx6\nx7\nx8\nHIT\n", null, "HIT", 1))
    }

    @Test fun contextWindowsClampAtTheFileEdges() {
        assertEquals(listOf("1:HIT", "2:b"), filterLines("HIT\nb\n", null, "HIT", 3))
        assertEquals(listOf("1:a", "2:HIT"), filterLines("a\nHIT", null, "HIT", 3))
    }

    @Test fun contextInsideALinesRangeKeepsAbsoluteNumbers(): Unit =
        assertEquals(listOf("2:l2", "3:hit", "4:l4", "--", "6:l6", "7:hit", "8:l8"), filterLines("l1\nl2\nhit\nl4\nl5\nl6\nhit\nl8\n", "2-8", "hit", 1))

    @Test fun contextZeroIsTheOldOutputAndMaxLinesStillCaps() {
        val text = (1..20).joinToString("\n") { if (it == 10 || it == 16) "hit" else "l$it" }
        assertEquals(listOf("10:hit", "16:hit"), filterLines(text, null, "hit", 0))
        val wide = filterLines(text, null, "hit", 3)
        assertEquals(13, wide.size); assertTrue(wide.none { it == "--" })
        val out = renderLines(text, "arc123", null, "hit", 3, 5u)
        assertEquals(5, out.lines().count { "lines omitted" !in it }); assertTrue("8 lines omitted (expand arc123)" in out, out)
    }

    @Test fun aFullArchiveIdInTheOmittedMarkerPrintsEightHexChars() {
        val id = "ab".repeat(32)
        val out = renderLines((1..20).joinToString("\n") { "l$it" }, id, null, null, 0, 5u)
        assertTrue("expand ${shortId(id)}" in out && id !in out, out)
    }

    @Test fun parseRangeBareStartRunsToEnd() {
        assertEquals(10 to 20, parseRange("10", 20)); assertEquals(5 to 5, parseRange("5-5", 20))
        assertEquals(1 to 5, parseRange("-5", 20)); assertEquals(5 to 20, parseRange("5-", 20))
    }

    @Test fun parseRangeStripsSurroundingQuotes() {
        for (s in listOf("\"5-10\"", "'5-10'", " \"5-10\" ")) assertEquals(5 to 10, parseRange(s, 20))
        for (s in listOf("\"5-10", "\"5-10'")) assertFailsWith<ExpandException> { parseRange(s, 20) }
    }

    @Test fun parseRangeAcceptsCommaPairs() {
        for (s in listOf("5,10", "5, 10", "\"5, 10\"")) assertEquals(5 to 10, parseRange(s, 20))
        assertEquals(5 to 20, parseRange("5,", 20)); assertEquals(7 to 10, parseRange("7,100", 10))
        for (s in listOf("1,2,3", "3,2", "0,2", "a,b")) assertTrue("`$s`" in err { parseRange(s, 20) }, s)
    }

    @Test fun parseRangeRejectsMalformedZeroAndReverseRanges() {
        for (s in listOf("", "abc", "1-two", "1-2-3", "0", "0-2", "3-2")) assertTrue("invalid line range" in err { parseRange(s, 20) }, s)
    }

    @Test fun parseRangeStartPastEndIsErrNotEmpty() {
        for (s in listOf("100-200", "11", "11-")) assertTrue("start exceeds line count 10" in err { parseRange(s, 10) }, s)
        assertEquals(8 to 10, parseRange("8-100", 10))
    }

    @Test fun parseRangeAcceptsTheLoggedSpellings() {
        val cases = listOf("36<br/>-60" to (36 to 60), "320 - 360" to (320 to 360), "10:45" to (10 to 45), "[1, 50]" to (1 to 50),
            "[200, 260]" to (200 to 260), "[242, 300]" to (242 to 300), "[317, 390]" to (317 to 390), "560 650" to (560 to 650),
            "12 - 100" to (12 to 100), "580:650" to (580 to 650), "200 - 380" to (200 to 380), "1860_1960" to (1860 to 1960),
            "283 350" to (283 to 350), "1 40" to (1 to 40), "136\"-\"215" to (136 to 215), "610 614" to (610 to 614),
            "490<br>-800" to (490 to 800), "12 - 70" to (12 to 70), "215\"-\"460" to (215 to 460), "235\"-\"270" to (235 to 270),
            "52<br>" to (52 to 52), "60<br>" to (60 to 60), "410<br>" to (410 to 410), "95<br>" to (95 to 95), "420 - 640" to (420 to 640))
        assertEquals(25, cases.size)
        for ((s, want) in cases) assertEquals(want, parseRange(s, 5000), s)
    }

    @Test fun parseRangeTrailingBrIsOneLineNotToEnd() {
        assertEquals(52 to 100, parseRange("52", 100))
        for (s in listOf("52<br>", "52<br/>", "52<br><br>")) assertEquals(52 to 52, parseRange(s, 100), s)
        assertEquals(52 to 70, parseRange("52-70<br>", 100)); assertEquals(490 to 800, parseRange("490<br>-800", 1000))
    }

    @Test fun parseRangeStillRejectsNonLines() {
        for (s in listOf("abc", "1-two", "1-2-3", "", "[]", "<br>", ",,,", "a b", "1\"2")) assertTrue("invalid line range" in err { parseRange(s, 20) }, s)
    }

    @Test fun maxLinesTruncatesAndReports() {
        val out = renderLines((1..150).joinToString("\n") { "line$it" }, "arc123", null, null, 0, 100u)
        assertEquals(100, out.lines().count { "lines omitted" !in it }); assertTrue("50 lines omitted (expand arc123)" in out, out)
    }
}
