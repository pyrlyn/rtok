// Port of src/plugins/memory/pack.rs tests.
package rtok.plugins.memory

import kotlin.test.*

private fun hit(id: Int, t: String, s: String) = PackHit(id, t, s)
private fun chars4(s: String): UInt = ((s.scalars().size + 3) / 4).toUInt()

class PackTest {
    @Test fun abstractsFitAndFullBodiesDoNot() {
        val p = packNotes(listOf(hit(1, "a", "sa"), hit(2, "b", "sb"), hit(3, "c", "sc")), { "PARA\n\nFULLBODY" }, 40u) {
            if ("FULLBODY" in it) 500u else if ("PARA" in it) 40u else 10u
        }
        assertEquals(3, p.entries.size); assertTrue(p.entries.all { it.tier == Tier.Abstract })
    }
    @Test fun aHugeBodyStaysAtAbstract() {
        val p = packNotes(listOf(hit(1, "hooks", "fail open")), { "FULLBODY" }, 20u) { if ("FULLBODY" in it) 500u else 10u }
        assertEquals(1, p.entries.size); assertEquals(Tier.Abstract, p.entries[0].tier)
    }
    @Test fun leftoverBudgetDeepensOnlyTheFirstHit() {
        val p = packNotes(listOf(hit(1, "a", "sa"), hit(2, "b", "sb"), hit(3, "c", "sc")),
            { if (it == 1) "MID paragraph.\n\nFULLBODY rest" else "HUGE paragraph that must not be opened." }, 50u) {
            if ("FULLBODY" in it || "HUGE" in it) 80u else if ("MID" in it) 20u else 10u
        }
        assertEquals(listOf(Tier.Overview, Tier.Abstract, Tier.Abstract), p.entries.map { it.tier })
    }
    @Test fun identicalTitleAndSnippetCollapse() {
        val p = packNotes(listOf(hit(1, "same", "snip"), hit(2, "same", "snip")), { "body" }, 1000u) { it.encodeToByteArray().size.toUInt() }
        assertEquals(1, p.entries.size); assertEquals(1u, p.dropped)
    }
    @Test fun anEstimatorOverTheBudgetReturnsNothing() {
        val p = packNotes(listOf(hit(1, "a", "b")), { "body" }, 10u) { 11u }
        assertTrue(p.entries.isEmpty()); assertEquals(1u, p.dropped)
    }
    @Test fun theReturnedTextNeverExceedsMaxTokens() {
        val hits = (1..6).map { hit(it, "title $it héllo", "snippet number $it ✓") }
        for (max in 1u..60u) {
            val p = packNotes(hits, { "first para $it\n\nrest of the body of note $it" }, max, ::chars4)
            val text = render(p); val cost = if (p.entries.isEmpty()) 0u else chars4(text)
            assertTrue(cost <= max, "max $max: $cost in $text"); assertEquals(cost, p.usedTokens)
        }
    }
    @Test fun aBudgetTooSmallForOneCompleteEntryReturnsNothing() {
        val p = packNotes(listOf(hit(1, "a", "b")), { null }, 2u, ::chars4)
        assertTrue(p.entries.isEmpty()); assertEquals(0u to 1u, p.usedTokens to p.dropped); assertEquals("", render(p))
    }
}
