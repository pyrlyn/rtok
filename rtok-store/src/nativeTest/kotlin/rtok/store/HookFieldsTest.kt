package rtok.store

import kotlinx.serialization.json.Json
import kotlin.test.*

class HookFieldsTest {
    @Test fun splitThenRebuildIsTheSameJson() {
        val stdin = """{"session_id":"s","cwd":"/r","hook_event_name":"PreToolUse","tool_input":{"cwd":"x"}}"""
        val (event, fields) = HookFields.split(stdin.encodeToByteArray())!!
        assertEquals("""{"hook_event_name":"PreToolUse","tool_input":{"cwd":"x"}}""", event.decodeToString())
        assertEquals("""{"cwd":"/r","session_id":"s"}""", fields)
        assertEquals(Json.parseToJsonElement(stdin), Json.parseToJsonElement(HookFields.rebuild(event.decodeToString(), fields)))
    }
    @Test fun splitDeclinesBodiesWithoutSessionFields() {
        for (b in listOf("""{"hook_event_name":"Stop"}""", "[1]", "not json")) assertNull(HookFields.split(b.encodeToByteArray()))
    }
    @Test fun rebuildHandlesAnEmptyEventAndAMissingRow() {
        assertEquals("""{"session_id":"s"}""", HookFields.rebuild("{}", """{"session_id":"s"}""")); assertEquals("""{"a":1}""", HookFields.rebuild("""{"a":1}""", null))
    }
}
