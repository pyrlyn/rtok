// Port of the `/ws` doctor messages of src/web/mod.rs and protocol.rs (T331.12): `plan` answers the checklist, `apply`
// passes the selection and the confirmed token, a malformed request gets the notice. The engine is injected.
package rtok.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.doctor.FixItem
import rtok.doctor.FixPlan
import rtok.doctor.Fixed
import rtok.doctor.Ref
import rtok.doctor.Selection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DoctorAnswerTest {
    private val item = FixItem("/h/settings.json", "hooks.Stop[0]", "hook", "claude", "x", "Stop", null, false, true, true)
    private val plans = mutableListOf<Selection>()
    private val applies = mutableListOf<Pair<Selection, String>>()
    private val answer =
        Answer(
            LiveConfig(Config()),
            doctorPlan = { _, s -> FixPlan(listOf(item), "+a", emptyList(), "tok").also { plans += s } },
            doctorApply = { _, s, t -> Fixed("done\n", 0, null).also { applies += s to t } },
        )

    private fun obj(s: String?) = Json.parseToJsonElement(s!!).jsonObject

    @Test fun planAnswersTheChecklistWithTheReq() {
        val r = obj(answer.answer("""{"req":7,"doctor":{"action":"plan","selection":{"toggled":[{"source":"a","path":"b"}]}}}"""))
        assertEquals("doctorplan", r["type"]!!.jsonPrimitive.content)
        assertEquals("7", r["req"]!!.jsonPrimitive.content)
        val plan = r["plan"]!!.jsonObject
        assertEquals("tok", plan["token"]!!.jsonPrimitive.content)
        assertEquals(
            "true",
            plan["items"]!!
                .jsonArray[0]
                .jsonObject["can_keep"]!!
                .jsonPrimitive.content,
        )
        assertEquals(listOf(Ref("a", "b")), plans.single().toggled)
        assertTrue(applies.isEmpty(), "a plan writes nothing")
    }

    @Test fun applyPassesTheConfirmedTokenAndAMissingOneIsEmpty() {
        val r = obj(answer.answer("""{"doctor":{"action":"apply","confirmed":"tok"}}"""))
        assertEquals("doctorfixed", r["type"]!!.jsonPrimitive.content)
        assertEquals("done\n", r["fixed"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        answer.answer("""{"doctor":{"action":"apply"}}""")
        assertEquals(listOf("tok", ""), applies.map { it.second })
    }

    @Test fun aMalformedRequestGetsTheNotice() {
        listOf("""{"doctor":{}}""", """{"doctor":{"action":"wipe"}}""", """{"doctor":1}""").forEach {
            val r = obj(answer.answer(it))
            assertEquals("message", r["type"]!!.jsonPrimitive.content)
            assertEquals("doctor needs an action and a selection", r["text"]!!.jsonPrimitive.content)
        }
        assertTrue(plans.isEmpty() && applies.isEmpty())
    }
}
