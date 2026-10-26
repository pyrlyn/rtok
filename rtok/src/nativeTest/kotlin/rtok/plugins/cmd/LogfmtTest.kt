// Port of the src/plugins/cmd/logfmt.rs tests (8).
package rtok.plugins.cmd

import kotlin.test.*

private fun run(n: Int) = (0 until n).map { "ts=$it level=info msg=\"a b\" id=r$it tail" }
private fun records(n: Int) = (0 until n).map { "ts=$it level=info svc=api msg=\"a b\" id=r$it" }

class LogfmtTest {
    @Test fun fieldsReadsBareAndQuotedValues() =
        assertEquals(listOf("a" to "1", "msg" to "\"x \\\" y=z\"", "b" to ""), fields("2026 INFO a=1 msg=\"x \\\" y=z\" b= =c d"))

    @Test fun constantsPrintOnceAndVaryingKeysShowARange() =
        assertEquals("fields: level=info msg=\"a b\"; varying: ts=0..9 id=r0..r9", logfmtSummary(run(10)))

    @Test fun aKeyMissingFromOneLineIsLeftOut() {
        val r = run(10).toMutableList(); r[4] = "ts=4 level=info"
        val s = logfmtSummary(r)!!; assertTrue(!s.contains("id=") && !s.contains("msg="), s)
    }

    @Test fun shortRunsAndPlainTextHaveNoSummary() {
        assertNull(logfmtSummary(run(7))); assertNull(logfmtSummary((0 until 20).map { "line $it" }))
    }

    @Test fun longVaryingValuesPrintTheKeyOnly() = assertEquals("varying: k", logfmtSummary((0 until 10).map { "k=${"x".repeat(30)}$it" }))

    @Test fun factorStripsSharedPairsAndKeepsAnchorsVerbatim() {
        val lines = records(10).toMutableList(); lines[5] = "ts=5 level=error svc=api msg=\"boom\" id=r5"
        val raw = lines.toList()
        assertEquals("[every record: level=info svc=api msg=\"a b\"]", logfmtFactor(lines) { it.contains("level=error") })
        assertEquals("ts=0 id=r0", lines[0]); assertEquals(raw[5], lines[5], "anchor stays whole")
    }

    @Test fun factorLeavesShortOrMixedOutputAlone() {
        assertNull(logfmtFactor(records(7).toMutableList()) { false })
        val mixed = (0 until 20).map { "plain $it" }.toMutableList(); mixed += records(8)
        assertNull(logfmtFactor(mixed) { false })
        assertNull(logfmtFactor((0 until 10).map { "a=$it b=$it c=$it" }.toMutableList()) { false })
    }

    @Test fun aVaryingKeyWithEqualEndsPrintsNoMisleadingRange() {
        val r = (0 until 10).map { i -> "k=${listOf(4, 5, 6)[i % 3] * (if (i < 9) 1 else 0) + 4 * (if (i == 9) 1 else 0)} j=a" }
        val s = logfmtSummary(r)!!; assertTrue(s.contains("varying: k") && !s.contains("k=4..4"), s)
    }
}
