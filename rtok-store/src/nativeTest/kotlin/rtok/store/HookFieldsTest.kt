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

class HookFieldsStoreTest {
    private fun hookCall(s: Store, event: String) = s.insertCall("s1", "hook", "hook", null, null, null, null, event)
    private fun json(b: ByteArray) = kotlinx.serialization.json.Json.parseToJsonElement(b.decodeToString())
    private fun json(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s)
    private val PRE = """{"session_id":"s1","transcript_path":"/t.jsonl","cwd":"/r","permission_mode":"default","hook_event_name":"PreToolUse","tool_name":"Read","tool_input":{"file_path":"/r/a"}}"""
    private val POST = """{"session_id":"s1","transcript_path":"/t.jsonl","cwd":"/r","permission_mode":"default","hook_event_name":"PostToolUse","tool_name":"Read","tool_response":"ok"}"""

    @Test fun twoHookCallsShareOneSessionRowAndReadBackWhole() {
        val s = Store.openInMemory(); s.upsertSession("s1", null, null, null, null)
        val pre = hookCall(s, "PreToolUse"); val post = hookCall(s, "PostToolUse")
        s.insertHookCallIo(pre, PRE.encodeToByteArray(), "{}".encodeToByteArray(), 65536); s.insertHookCallIo(post, POST.encodeToByteArray(), "{}".encodeToByteArray(), 65536)
        assertEquals(1, s.hookSessionCount())
        val saved = s.conn { it.query("SELECT request_json FROM call_io ORDER BY call_id ASC") { r -> r.string(0) } }
        assertTrue(saved.none { it.contains("transcript_path") }, saved.toString())
        for ((id, want) in listOf(pre to PRE, post to POST)) assertEquals(json(want), json(s.callIoRequest(id)!!))
        val recent = s.recentHookInputs("s1", 10); assertEquals(json(POST), json(recent[0])); assertEquals(json(PRE), json(recent[1]))
        assertEquals(json(PRE), json(s.recentHookInputsForEvent("s1", "PreToolUse", 10)[0]))
        assertEquals(json(PRE), json(s.callDetail(s.callsAfter(0, 10)[0]).io!!.requestJson!!))
    }

    @Test fun eventBodiesAreFilteredByEventAndCutoff() {
        val s = Store.openInMemory(); s.upsertSession("s1", null, null, null, null)
        val pre = hookCall(s, "PreToolUse"); val post = hookCall(s, "PostToolUse")
        s.insertHookCallIo(pre, PRE.encodeToByteArray(), "{}".encodeToByteArray(), 65536); s.insertHookCallIo(post, POST.encodeToByteArray(), "{}".encodeToByteArray(), 65536)
        val got = s.hookInputsSince("PostToolUse", 0, 10); assertEquals(1, got.size); assertEquals(json(POST), json(got[0]))
        for ((event, since) in listOf("PostToolUse" to Long.MAX_VALUE, "Stop" to 0L)) assertTrue(s.hookInputsSince(event, since, 10).isEmpty())
    }

    @Test fun storeRawAndOldRowsKeepTheFullBody() {
        val s = Store.openInMemory(); s.upsertSession("s1", null, null, null, null)
        val old = hookCall(s, "PreToolUse"); s.insertCallIo(old, PRE.encodeToByteArray(), null, 65536, null)
        s.setStoreRaw(true); val raw = hookCall(s, "PostToolUse"); s.insertHookCallIo(raw, POST.encodeToByteArray(), null, 65536)
        assertEquals(0, s.hookSessionCount()); assertEquals(POST, s.callIoRequest(raw)!!.decodeToString()); assertEquals(PRE, s.callIoRequest(old)!!.decodeToString())
    }

    @Test fun retentionDropsUnreferencedSessionRows() {
        val s = Store.openInMemory(); s.upsertSession("s1", null, null, null, null)
        val old = hookCall(s, "PreToolUse"); val new = hookCall(s, "PostToolUse")
        s.insertHookCallIo(old, PRE.encodeToByteArray(), null, 65536)
        val sub = POST.replace("\"cwd\":\"/r\"", "\"cwd\":\"/r\",\"agent_id\":\"a1\""); s.insertHookCallIo(new, sub.encodeToByteArray(), null, 65536)
        assertEquals(2, s.hookSessionCount()); s.setCallTs(old, 0); s.runRetention(0u, 1u)
        assertEquals(1, s.hookSessionCount()); assertNull(s.callIoRequest(old)); assertEquals(json(sub), json(s.callIoRequest(new)!!))
    }
}
