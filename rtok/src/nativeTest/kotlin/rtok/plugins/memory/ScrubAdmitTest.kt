// Port of src/plugins/memory/scrub.rs and admit.rs tests.
package rtok.plugins.memory

import kotlin.test.*
import rtok.plugins.memory.Admit.Verdict
import rtok.plugins.memory.Admit.judge

class ScrubTest {
    @Test fun stripsPrivateTagsAndKeyShapedTokens() {
        val out = scrubSecrets("keep <private>nope</private> and sk-ant-abcdefghijklmnopqrstuvwxyz")
        assertFalse("nope" in out, out); assertFalse("sk-ant-" in out, out); assertTrue("[REDACTED]" in out, out)
    }
    @Test fun stripsUrlUserinfo() {
        val out = scrubSecrets("see https://user:secret@example.com/a")
        assertTrue("https://[REDACTED]@example.com/a" in out, out); assertFalse("secret" in out, out)
    }
}

class AdmitTest {
    private fun row(id: Int, body: String) = Admit.Row(id, "title $id", body)
    @Test fun aDistinctNoteIsAdmitted() =
        assertEquals(Verdict.Admit, judge(12u, 85u, "hooks must exit zero within ten milliseconds", listOf(row(1, "the walrus cache evicts the oldest entry first"))))
    @Test fun caseAndWhitespaceDoNotHideADuplicate() =
        assertEquals(Verdict.Duplicate(7), judge(12u, 85u, "use   diesel for every query,\nnever raw sql.", listOf(row(7, "Use Diesel for every query, never raw SQL."))))
    @Test fun aNearDuplicateIsRefusedAndARewriteIsNot() {
        val s = listOf(row(3, "Config keys come from one module and the schema from its types."))
        assertEquals(Verdict.Duplicate(3), judge(12u, 85u, "Config keys come from one module and the schema from its types!", s))
        assertEquals(Verdict.Admit, judge(12u, 85u, "Every TOML key lives in rtok-config; the JSON schema is generated.", s))
    }
    @Test fun aBodyUnderTheFloorIsRefusedBeforeAnyComparison() {
        val s = listOf(row(1, "ok"))
        assertEquals(Verdict.TooShort, judge(12u, 85u, "  ok  ", s)); assertEquals(Verdict.Duplicate(1), judge(2u, 85u, "ok", s))
    }
    @Test fun theFirstMatchInStoredOrderIsNamed() {
        val t = "the same sentence stored twice by two sessions"
        assertEquals(Verdict.Duplicate(9), judge(12u, 85u, t, listOf(row(9, t), row(4, t))))
    }
    @Test fun similarityOfUnrelatedTextsIsLow() {
        assertTrue(Admit.similarityPct("abcdefgh", "ijklmnop") < 10u); assertEquals(0u, Admit.similarityPct("", "abc"))
    }
}
