package rtok.plugins.read

import kotlin.test.*

class DensityTest {
    private fun fixture() = buildString {
        for (i in 0 until 60) {
            append("    let value = self.cache.get(&key).cloned();\n")
            append("    if value.is_none() { return None; }\n")
            if (i == 30) append("    let quota = billing::remaining_quota(account_id)?;\n")
        }
    }

    @Test fun keepsTheRareLineAndDropsRepeatsFirst() {
        val out = renderDensity(fixture(), 0.2)
        assertTrue("billing::remaining_quota" in out, out); assertTrue("lines omitted" in out, out)
        assertTrue(out.length < fixture().length / 3, "${out.length} B")
    }

    @Test fun lineNumbersSurviveSoLinesCanFillAGap() {
        val line = fixture().lines().indexOfFirst { "billing::" in it } + 1
        assertTrue("$line:    let quota" in renderDensity(fixture(), 0.2))
    }

    @Test fun fullShareReturnsEveryNonblankLineAndIsDeterministic() {
        assertEquals("1:a b\n… 1 lines omitted\n3:c d", renderDensity("a b\n\nc d\n", 1.0))
        assertEquals(renderDensity(fixture(), 0.3), renderDensity(fixture(), 0.3))
    }

    @Test fun parseAcceptsAShareAndRejectsTheRest() {
        assertEquals(0.4, parseDensity("density:0.4")!!.getOrThrow())
        assertNull(parseDensity("full"))
        for (bad in listOf("density:0", "density:1.5", "density:x", "density:")) assertTrue(parseDensity(bad)!!.isFailure, bad)
    }
}
