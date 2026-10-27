package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Cases of web/src/pages/junk.test.tsx that live in junkState.ts: the confirm flow and the plan grouping.
class JunkTest {
    private val plan: JsonObject =
        Json
            .parseToJsonElement(
                """{"yes":false,"items":[
                {"agent":"rtok","kind":"log","path":"/a","bytes":5,"planned":true,"action":"clear","note":"","failed":false},
                {"agent":"rtok","kind":"log","path":"/b","bytes":7,"planned":false,"action":"skip","note":"running","failed":false},
                {"agent":"claude","kind":"cache","path":"/c","bytes":3,"planned":true,"action":"clear","note":"","failed":false}
                ],"planned_bytes":8,"freed_bytes":0}""",
            ).jsonObject

    private fun walk(vararg e: JunkEvent) = e.fold(JunkState(), ::junkReduce)

    @Test fun deleteIsReachedOnlyThroughConfirm() {
        assertEquals(JunkPhase.PLANNING, walk(JunkEvent.Plan).phase)
        assertEquals(JunkPhase.READY, walk(JunkEvent.Plan, JunkEvent.Planned(plan)).phase)
        assertEquals(JunkPhase.IDLE, walk(JunkEvent.Applying).phase, "no apply without a confirm")
        assertEquals(JunkPhase.READY, walk(JunkEvent.Plan, JunkEvent.Planned(plan), JunkEvent.Applying).phase)
        val asked = walk(JunkEvent.Plan, JunkEvent.Planned(plan), JunkEvent.Ask)
        assertEquals(JunkPhase.CONFIRMING, asked.phase)
        val applying = junkReduce(asked, JunkEvent.Applying)
        assertEquals(JunkPhase.APPLYING, applying.phase)
        val done = junkReduce(applying, JunkEvent.Applied(plan))
        assertEquals(JunkPhase.DONE to null, done.phase to done.plan)
        assertEquals(JunkState(), junkReduce(done, JunkEvent.Reset))
    }

    @Test fun cancelDropsThePlanAndAFailureStartsOver() {
        val asked = walk(JunkEvent.Plan, JunkEvent.Planned(plan), JunkEvent.Ask)
        assertNull(junkReduce(asked, JunkEvent.Cancel).plan)
        val failed = junkReduce(asked, JunkEvent.Failed("boom"))
        assertEquals(JunkState(JunkPhase.ERROR, error = "boom"), failed)
        assertEquals(JunkPhase.PLANNING, junkReduce(failed, JunkEvent.Plan).phase)
    }

    @Test fun anEmptyPlanCannotBeConfirmed() {
        val empty = Json.parseToJsonElement("""{"items":[],"planned_bytes":0}""").jsonObject
        assertEquals(JunkPhase.READY, walk(JunkEvent.Plan, JunkEvent.Planned(empty), JunkEvent.Ask).phase)
    }

    @Test fun removableAndGroupsCountOnlyPlannedClears() {
        assertEquals(listOf("/a", "/c"), removable(plan).map { it.str("path") })
        val g = groupPlan(plan.objects("items"))
        assertEquals(listOf("rtok" to "log", "claude" to "cache"), g.map { it.agent to it.kind })
        assertEquals(listOf(2 to 5.0, 1 to 3.0), g.map { it.items.size to it.bytes })
        assertEquals("1 item" to "2 items", itemCount(1) to itemCount(2))
    }
}
