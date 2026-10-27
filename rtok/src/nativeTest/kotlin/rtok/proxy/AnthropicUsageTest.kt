@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.serialization.json.*
import kotlin.test.*

/** Port of the Wire tests in src/proxy/anthropic.rs. */
class AnthropicUsageTest {
    @Test fun anthropicReadsBodyAndSseUsage() {
        assertEquals(Usage(3, 0, 0, 2), AnthropicWire.usageFromBody(js("""{"usage":{"input_tokens":3,"output_tokens":2}}""")))
        assertEquals(Usage(0, 0, 4, 0), AnthropicWire.usageFromSse(js("""{"message":{"usage":{"cache_read_input_tokens":4}}}""")))
    }

    @Test fun contextEditsAreOptInAndNeverOverwrite() {
        assertNull(AnthropicWire.applyContextEdits(js("""{"model":"m"}"""), false))
        val on = AnthropicWire.applyContextEdits(js("""{"model":"m"}"""), true)!!
        assertEquals(js("""{"edits":[{"type":"clear_tool_uses_20250919"}]}"""), on.jsonObject["context_management"])
        assertNull(AnthropicWire.applyContextEdits(js("""{"context_management":{"edits":[]}}"""), true))
        assertNull(AnthropicWire.applyContextEdits(JsonPrimitive("nope"), true))
    }

    @Test fun skillBodyRightAfterLaunchingSkillIsASkillRef() {
        val same =
            req(
                AnthropicWire,
                """{"messages":[
            {"role":"user","content":[
                {"type":"tool_result","tool_use_id":"tu1","content":"Launching skill: pixel"},
                {"type":"text","text":"Base directory for this skill: /s/pixel\n\n# Pixel\n"}]},
            {"role":"assistant","content":"ok"},
            {"role":"user","content":[{"type":"text","text":"next"}]}]}""",
            ).skillRefs()
        assertEquals(listOf(Triple("tu1", "pixel", 1)), same.map { Triple(it.id, it.name, it.turn) })
        val next =
            req(
                AnthropicWire,
                """{"messages":[
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"tu2","content":"Launching skill: ponytail"}]},
            {"role":"user","content":[{"type":"text","text":"Base directory for this skill: /s/ponytail\n\n# P\n"}]},
            {"role":"user","content":[{"type":"text","text":"hello"}]}]}""",
            ).skillRefs()
        assertEquals(listOf(Triple("tu2", "ponytail", 1)), next.map { Triple(it.id, it.name, it.turn) })
        val skip =
            req(
                AnthropicWire,
                """{"messages":[{"role":"user","content":[
                {"type":"tool_result","tool_use_id":"tu3","content":"Launching skill: x"},
                {"type":"text","text":"not a skill body"}]}]}""",
            )
        assertTrue(skip.skillRefs().isEmpty())
    }

    @Test fun aNonBodyBlockBeforeTheSkillTextDoesNotEatThePair() {
        val refs =
            req(
                AnthropicWire,
                """{"messages":[{"role":"user","content":[
                {"type":"tool_result","tool_use_id":"tu1","content":"Launching skill: pixel"},
                {"type":"text","text":"working on it"},
                {"type":"text","text":"Base directory for this skill: /s/pixel\n\n# Pixel\n"}]}]}""",
            ).skillRefs()
        assertEquals(listOf("tu1" to "pixel"), refs.map { it.id to it.name })
    }
}
