// Port of src/plugins/memory/handoff.rs tests; the graph-indexed range test waits for Phase 10 (tree-sitter).
package rtok.plugins.memory

import kotlin.test.*
import kotlinx.serialization.json.*
import rtok.plugin.sdk.*
import rtok.store.measurementCount

/** Mirror of `measure::subagents::SPAWN_BRIEF_MARKER` (T131). */
private const val SPAWN_BRIEF_MARKER = "Get the archived body with rtok expand"

class HandoffTest {
    @Test fun instructionsStillCarryTheSpawnBriefMarker() = memRt("t131").use { rt ->
        touch(rt, "Read", "/repo/a.rs"); assertTrue(SPAWN_BRIEF_MARKER in buildBrief(Ctx(rt), 300u, "")!!)
    }
    @Test fun stubRecordsMeasurementWhenOff() = memRt("t596") { it.copy(handoff = false) }.use { rt ->
        assertTrue("disabled" in handoff(Ctx(rt), 800u)); assertTrue(rt.store.measurementCount("memory") >= 1)
    }
    @Test fun handoffEnabledSharesTheSpawnBriefBuilder() = memRt("t596-on").use { rt ->
        touch(rt, "Read", "/repo/a.rs"); val cx = Ctx(rt)
        val out = handoff(cx, 300u); assertEquals(buildBrief(cx, 300u, ""), out); assertTrue("/repo/a.rs" in out)
    }
    @Test fun handoffEnabledWithAnEmptyLedgerFallsBack() = memRt("t596-on-empty").use { rt ->
        assertEquals("handoff: nothing read or edited yet.", handoff(Ctx(rt), 300u))
    }
    @Test fun applyPatchHeadersEnterTheLedgerRelativePathsUnderTheCwd() = memRt("t262-3-patch").use { rt ->
        val patch = "*** Begin Patch\n*** Add File: new.txt\n+hi\n*** Update File: src/a.rs\n*** Move to: src/b.rs\n@@\n-x\n+y\n*** Delete File: /abs/old.rs\n*** End Patch\n"
        touchAt(rt, "/repo", "apply_patch", buildJsonObject { put("command", patch) })
        val text = buildBrief(Ctx(rt), 300u, "")!!
        for (p in listOf("/repo/new.txt", "/repo/src/a.rs", "/repo/src/b.rs", "/abs/old.rs")) assertTrue(text.lines().any { it == p }, "$p in\n$text")
    }
    @Test fun aPatchPathTakesTheSeparatorOfItsCwd() {
        assertEquals("/repo/src/a.rs", underCwd("/repo", "src/a.rs")); assertEquals("/repo/src/a.rs", underCwd("/repo/", "src/a.rs"))
        assertEquals("C:\\repo\\src\\a.rs", underCwd("C:\\repo", "src/a.rs")); assertEquals("/abs/old.rs", underCwd("/repo", "/abs/old.rs"))
        assertEquals("\\abs\\old.rs", underCwd("C:\\repo", "\\abs\\old.rs"))
    }
    @Test fun patchPathsIgnoresEverythingButTheFileHeaders() =
        assertEquals(listOf("a b.rs"), patchPaths("*** Update File: a b.rs\n+*** Add File: not-a-header\n*** Add File: \n"))
    @Test fun emptyLedgerOffersNothing() = memRt("t130-empty").use { rt -> assertNull(buildBrief(Ctx(rt), 300u, "")) }
    @Test fun briefListsTouchedPathsAndCarriesItsOwnExpandId() = memRt("t130-brief").use { rt ->
        touch(rt, "Read", "/repo/a.rs"); touch(rt, "Edit", "/repo/b.rs")
        val text = buildBrief(Ctx(rt), 300u, "")!!
        for (s in listOf("/repo/a.rs", "/repo/b.rs", "rtok expand")) assertTrue(s in text, text)
        assertTrue(rt.store.measurementCount("memory") >= 1)
    }
    @Test fun pathsNamedInTheHintSortFirst() = memRt("t130-hint").use { rt ->
        touch(rt, "Read", "/repo/a.rs"); touch(rt, "Read", "/repo/b.rs")
        val text = buildBrief(Ctx(rt), 300u, "look at /repo/b.rs please")!!
        assertTrue(text.indexOf("/repo/b.rs") < text.indexOf("/repo/a.rs"), text)
    }
    @Test fun briefStaysUnderBudgetAndIsByteStable() = memRt("t130-budget").use { rt ->
        repeat(20) { touch(rt, "Read", "/repo/file_$it.rs") }
        val cx = Ctx(rt); val first = buildBrief(cx, 40u, "")!!
        assertTrue(cx.estimate(first, Class.Prose) <= 40u, first); assertEquals(first, buildBrief(cx, 40u, ""))
    }
}
