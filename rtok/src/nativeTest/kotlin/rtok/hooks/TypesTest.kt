// Port of src/hooks/types.rs tests.
package rtok.hooks

import kotlinx.serialization.json.*
import rtok.canonicalToolName
import rtok.sys.*
import rtok.config.envVar
import kotlin.test.*

private fun j(s: String) = HookInput.parse(s)
private fun o(vararg p: Pair<String, Any?>): JsonObject = buildJsonObject { for ((k, v) in p) put(k, v.toJson()) }
private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull; is JsonElement -> this; is String -> JsonPrimitive(this); is Number -> JsonPrimitive(this); is Boolean -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toJson() }); else -> error("x")
}
private operator fun JsonElement?.get(k: String): JsonElement? = (this as? JsonObject)?.get(k)
private operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.get(i)
private val JsonElement?.s get() = (this as? JsonPrimitive)?.content

class TypesTest {
    private fun fixtures(): List<Pair<String, JsonElement>> {
        val dir = "${envVar("RTOK_FIXTURES")}/hooks"
        return listDir(dir).filter { it.endsWith(".json") }.sorted().map { it to Json.parseToJsonElement(readToString("$dir/$it")) }
    }
    @Test fun everyFixtureRoundTripsUnchanged() {
        val fx = fixtures(); assertEquals(9, fx.size)
        for ((n, v) in fx) assertEquals(v, HookInput.fromJson(v).toJson(), "$n changed after round trip")
    }
    @Test fun eventViewsMatchEventName() {
        for ((n, v) in fixtures()) { val i = HookInput.fromJson(v)
            val seen = listOf(i.preTool() != null, i.postTool() != null, i.sessionStart() != null, i.promptSubmit() != null, i.preCompact() != null).count { it }
            assertEquals(if (i.hookEventName != "PostCompact") 1 else 0, seen, n) }
    }
    @Test fun agentIdentityParsesAndRoundTrips() {
        val i = HookInput.fromJson(o("session_id" to "s-1", "hook_event_name" to "PreToolUse", "tool_name" to "Read", "tool_input" to o("file_path" to "/p.rs"), "agent_id" to "a00bd472273a674a6", "agent_type" to "general-purpose"))
        assertEquals("a00bd472273a674a6", i.agentId); assertEquals("general-purpose", i.agentType); assertEquals(i, HookInput.parse(i.encode()))
        val p = j("""{"session_id":"s-2","hook_event_name":"PreToolUse"}"""); assertNull(p.agentId); assertNull(p.agentType)
    }
    @Test fun adaptCodewhaleMapsTextToPromptAndSetsTheEvent() {
        val i = HookInput.fromJson(o("text" to "hello", "session_id" to "sess_1", "workspace" to "/w", "mode" to "ACT")); i.adaptCodewhale("UserPromptSubmit")
        assertEquals("UserPromptSubmit", i.hookEventName); assertEquals("hello", i.prompt); assertTrue("workspace" in i.extra); assertFalse("text" in i.extra)
    }
    @Test fun emptyOutputIsEmptyObject() {
        assertEquals("{}", HookOutput().encode()); val out = preOut("deny", "dup", null); val js = out.toJson()
        assertEquals("deny", js["hookSpecificOutput"]["permissionDecision"].s); assertNull(js["continue"]); assertEquals(out, HookOutput.fromJson(js))
    }
    @Test fun cursorAfterShellMapsToPostToolUse() {
        val i = HookInput.fromJson(o("hook_event_name" to "afterShellExecution", "command" to "ls -la", "output" to "total 0\n", "conversation_id" to "sess-after", "cwd" to "/tmp"))
        i.adaptCursor("PostToolUse"); assertEquals("sess-after", i.sessionId); assertEquals("PostToolUse", i.hookEventName); assertEquals("Bash", i.toolName)
        assertEquals("ls -la", i.toolInput["command"].s); assertEquals(JsonPrimitive("total 0\n"), i.toolResponse); assertNotNull(i.postTool()); assertNull(i.preTool())
    }
    @Test fun copilotSubagentStartMapsAgentNameAndDescription() {
        val i = HookInput.fromJson(o("sessionId" to "cp-2", "cwd" to "/tmp", "agentName" to "explore", "agentDescription" to "look at /repo/a.rs")); i.adaptCopilot("subagentStart")
        val s = assertNotNull(i.subagentStart()); assertEquals("explore", s.agentType); assertEquals("look at /repo/a.rs", s.taskDescription); assertNull(i.extra["agentName"])
    }
    @Test fun copilotPreToolUseMapsCamelCaseAndToolNames() {
        val i = HookInput.fromJson(o("sessionId" to "cp-1", "timestamp" to 1, "cwd" to "/tmp", "toolName" to "run_in_terminal", "toolArgs" to o("command" to "git status"))); i.adaptCopilot("PreToolUse")
        assertEquals("cp-1", i.sessionId); assertEquals("PreToolUse", i.hookEventName); assertEquals("Bash", i.toolName); assertEquals("git status", i.toolInput["command"].s)
        assertEquals(JsonPrimitive(1), i.extra["timestamp"]); assertNotNull(i.preTool())
        val a = HookInput.fromJson(o("sessionId" to "cp-1", "toolName" to "view", "toolArgs" to o("path" to "a.rs"), "toolResult" to o("textResultForLlm" to "fn main() {}"))); a.adaptCopilot("postToolUse")
        assertEquals("PostToolUse", a.hookEventName); assertEquals("Read", a.toolName); assertNotNull(a.postTool()); assertEquals("web_search", canonicalToolName("web_search"))
    }
    @Test fun copilotDecodesSerializedToolArgs() {
        fun adapted(e: String, tool: String, args: JsonElement) = HookInput.fromJson(o("sessionId" to "cp-3", "toolName" to tool, "toolArgs" to args)).also { it.adaptCopilot(e) }
        for (e in listOf("preToolUse", "PreToolUse", "postToolUse", "PostToolUse")) {
            val want = o("command" to "git status"); val fs = adapted(e, "bash", JsonPrimitive(want.toString())); val fo = adapted(e, "bash", want)
            assertEquals(want, fs.toolInput, e); assertEquals(fs.toolInput, fo.toolInput, e)
        }
        // guard::cache_key assertion arrives with the guard plugin port (Phase 5).
        for (raw in listOf("{\"command\":", "42", "\"x\"", "")) assertEquals(JsonPrimitive(raw), adapted("preToolUse", "bash", JsonPrimitive(raw)).toolInput, raw)
        assertNotNull(adapted("preToolUse", "bash", JsonNull).toolInput)
        val both = HookInput.fromJson(o("tool_input" to o("command" to "ls"), "toolArgs" to "{\"command\":\"pwd\"}")); both.adaptCopilot("preToolUse"); assertEquals("ls", both.toolInput["command"].s)
    }
    @Test fun devinMapsToolNamesResultProjectDirAndCompaction() {
        val pre = j("""{"hook_event_name":"PreToolUse","tool_name":"exec","tool_input":{"command":"echo rtok-t87"},"tool_use_id":"call_e53ac12625584a319924ddb5","session_id":"regal-name","prompt_id":"9d55c94c-4032-4f0d-a353-9a19d43d39d0"}""")
        pre.adaptDevin("PreToolUse", "/work/app"); assertEquals("PreToolUse", pre.hookEventName); assertEquals("Bash", pre.toolName); assertEquals("echo rtok-t87", pre.toolInput["command"].s)
        assertNull(pre.toolInput["shell_id"]); assertEquals("/work/app", pre.cwd); assertEquals(JsonPrimitive("9d55c94c-4032-4f0d-a353-9a19d43d39d0"), pre.extra["prompt_id"]); assertNotNull(pre.preTool())
        val read = j("""{"hook_event_name":"PreToolUse","tool_name":"read","tool_input":{"file_path":"/private/tmp/rtok-t87-capture-72600/ws/hello.txt"},"tool_use_id":"call_9af95feed7d74e8e8c466b2c","session_id":"regal-name","prompt_id":"9d55c94c-4032-4f0d-a353-9a19d43d39d0"}""")
        read.adaptDevin("PreToolUse", "/work/app"); assertEquals("Read", read.toolName); assertEquals("/private/tmp/rtok-t87-capture-72600/ws/hello.txt", read.toolInput["file_path"].s); assertNull(read.toolInput["path"])
        val post = j("""{"hook_event_name":"PostToolUse","tool_name":"exec","tool_input":{"command":"echo rtok-t87"},"tool_use_id":"call_e53ac12625584a319924ddb5","tool_response":{"success":true,"output":"Output from command in shell 131f83:\nrtok-t87\n\n\nExit code: 0","error":null},"session_id":"regal-name","prompt_id":"9d55c94c-4032-4f0d-a353-9a19d43d39d0"}""")
        post.adaptDevin("PostToolUse", "/work/app"); assertEquals("Output from command in shell 131f83:\nrtok-t87\n\n\nExit code: 0", post.toolResponse["stdout"].s)
        assertEquals(JsonPrimitive(true), post.toolResponse["success"]); assertEquals("/work/app", post.cwd); assertNotNull(post.postTool())
        val cw = HookInput.fromJson(o("session_id" to "dv-1", "cwd" to "/from/stdin", "tool_name" to "exec", "tool_input" to o("command" to "ls"), "tool_response" to o("success" to true, "output" to "a\nb\n", "error" to null)))
        cw.adaptDevin("PostToolUse", "/work/app"); assertEquals("/from/stdin", cw.cwd)
        val other = HookInput.fromJson(o("tool_name" to "mcp__github__execute_query", "tool_input" to o())); other.adaptDevin("PreToolUse", null); assertEquals("mcp__github__execute_query", other.toolName); assertNull(other.cwd)
        val c = HookInput.fromJson(o("session_id" to "dv-1", "summary" to null)); c.adaptDevin("PostCompaction", null); assertEquals("PostCompact", c.hookEventName)
    }
    @Test fun devinFailedEmptyOutputLiftsNoStdout() {
        val p = HookInput.fromJson(o("session_id" to "dv-fail", "tool_name" to "exec", "tool_input" to o("command" to "false"), "tool_response" to o("success" to false, "output" to "", "error" to "exit 1")))
        p.adaptDevin("PostToolUse", null); val r = p.toolResponse
        assertEquals(JsonPrimitive(false), r["success"]); assertEquals("", r["output"].s); assertEquals("exit 1", r["error"].s); assertNull(r["stdout"]); assertNotNull(p.postTool())
    }
    @Test fun cursorBeforeShellStillMapsToPreToolUse() {
        val i = HookInput.fromJson(o("hook_event_name" to "beforeShellExecution", "command" to "pwd", "conversation_id" to "sess-before")); i.adaptCursor("PreToolUse")
        assertEquals("PreToolUse", i.hookEventName); assertNotNull(i.preTool()); assertNull(i.postTool()); assertNull(i.toolResponse)
    }
    @Test fun cursorPostToolUseMapsMcpToolOutput() {
        val i = HookInput.fromJson(o("hook_event_name" to "postToolUse", "tool_name" to "MCP:list_issues", "tool_input" to o("team" to "eng"), "tool_output" to "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}", "conversation_id" to "sess-mcp", "mcp_server_name" to "linear"))
        i.adaptCursor("PostToolUse"); assertEquals("sess-mcp", i.sessionId); assertEquals("PostToolUse", i.hookEventName); assertEquals("MCP:list_issues", i.toolName)
        assertEquals("eng", i.toolInput["team"].s); assertEquals("ok", i.toolResponse["content"][0]["text"].s); assertNotNull(i.postTool())
    }
    @Test fun grokLiftsCamelCaseAndMapsOnlyTheTerminal() {
        val pre = HookInput.fromJson(o("hookEventName" to "pre_tool_use", "hook_event_name" to "PreToolUse", "sessionId" to "abc-123", "cwd" to "/Users/you/project", "workspaceRoot" to "/Users/you/project",
            "permissionMode" to "default", "toolName" to "run_terminal_command", "toolInput" to o("command" to "npm test"), "toolUseId" to "tu-1", "timestamp" to "2026-04-14T12:00:00Z"))
        pre.adaptGrok("PreToolUse"); assertEquals("PreToolUse", pre.hookEventName); assertEquals("abc-123", pre.sessionId); assertEquals("Bash", pre.toolName)
        assertEquals("npm test", pre.toolInput["command"].s); assertEquals("tu-1", pre.toolUseId); assertEquals("default", pre.permissionMode); assertEquals("/Users/you/project", pre.cwd)
        assertTrue("timestamp" in pre.extra); assertNotNull(pre.preTool())
        val post = HookInput.fromJson(o("hook_event_name" to "PostToolUse", "sessionId" to "abc-123", "workspaceRoot" to "/w", "toolName" to "run_terminal_command", "toolInput" to o("command" to "ls"),
            "toolResult" to o("type" to "Bash", "command" to "ls", "exit_code" to 0, "output_for_prompt" to "a\nb\n")))
        post.adaptGrok("SessionStart"); assertEquals("PostToolUse", post.hookEventName); assertEquals("/w", post.cwd); assertEquals("a\nb\n", post.toolResponse["stdout"].s); assertNotNull(post.postTool())
        val read = HookInput.fromJson(o("toolName" to "read_file", "toolInput" to o("path" to "src/main.rs"))); read.adaptGrok("PreToolUse"); assertEquals("PreToolUse", read.hookEventName); assertEquals("read_file", read.toolName)
    }
    @Test fun geminiBeforeAfterToolMapEventToolNameAndAliasLlmContentToStdout() {
        val pre = HookInput.fromJson(o("session_id" to "g-1", "hook_event_name" to "BeforeTool", "cwd" to "/work", "tool_name" to "run_shell_command", "tool_input" to o("command" to "ls")))
        pre.adaptGemini("BeforeTool"); assertEquals("PreToolUse", pre.hookEventName); assertEquals("Bash", pre.toolName); assertNotNull(pre.preTool())
        val post = HookInput.fromJson(o("session_id" to "g-1", "hook_event_name" to "AfterTool", "tool_name" to "run_shell_command", "tool_input" to o("command" to "ls"), "tool_response" to o("llmContent" to "a\nb\n", "returnDisplay" to "a\nb\n", "error" to null)))
        post.adaptGemini("AfterTool"); assertEquals("PostToolUse", post.hookEventName); assertEquals("Bash", post.toolName); assertEquals("a\nb\n", post.toolResponse["stdout"].s); assertEquals("a\nb\n", post.toolResponse["llmContent"].s); assertNotNull(post.postTool())
        val read = HookInput.fromJson(o("hook_event_name" to "BeforeTool", "tool_name" to "read_file", "tool_input" to o("file_path" to "/p.rs"))); read.adaptGemini("BeforeTool"); assertEquals("Read", read.toolName); assertNotNull(read.preTool())
        assertEquals("Write", canonicalToolName("write_file")); assertEquals("Edit", canonicalToolName("replace"))
    }
    @Test fun geminiBeforeAgentAndPreCompressMapToClaudeNames() {
        val p = HookInput.fromJson(o("hook_event_name" to "BeforeAgent", "prompt" to "fix the bug")); p.adaptGemini("BeforeAgent"); assertEquals("UserPromptSubmit", p.hookEventName); assertNotNull(p.promptSubmit())
        val c = HookInput.fromJson(o("hook_event_name" to "PreCompress", "trigger" to "auto")); c.adaptGemini("PreCompress"); assertEquals("PreCompact", c.hookEventName); assertNotNull(c.preCompact())
    }
    @Test fun clineMapsToolCallResultAndLifecycle() {
        val pre = HookInput.fromJson(o("hookName" to "tool_call", "taskId" to "task-1", "workspaceRoots" to listOf("/work/app"), "tool_call" to o("id" to "tc-1", "name" to "run_commands", "input" to o("commands" to listOf("git status")))))
        pre.adaptCline("PreToolUse"); assertEquals("PreToolUse", pre.hookEventName); assertEquals("task-1", pre.sessionId); assertEquals("/work/app", pre.cwd); assertEquals("Bash", pre.toolName)
        assertEquals("git status", pre.toolInput["command"].s); assertEquals("git status", pre.toolInput["commands"][0].s); assertEquals("tc-1", pre.toolUseId); assertNotNull(pre.preTool())
        assertFalse("hookName" in pre.extra); assertFalse("taskId" in pre.extra)
        val multi = HookInput.fromJson(o("hookName" to "tool_call", "taskId" to "task-1", "workspaceRoots" to listOf("/work/app"), "tool_call" to o("id" to "tc-2", "name" to "run_commands", "input" to o("commands" to listOf("a", "b")))))
        multi.adaptCline("PreToolUse"); assertEquals("Bash", multi.toolName); assertNull(multi.toolInput["command"])
        val read = HookInput.fromJson(o("hookName" to "tool_call", "taskId" to "task-1", "tool_call" to o("id" to "tc-3", "name" to "read_files", "input" to o("paths" to listOf("src/main.rs"))))); read.adaptCline("PreToolUse"); assertEquals("Read", read.toolName)
        val f = HookInput.fromJson(o("hookName" to "tool_call", "taskId" to "task-1", "tool_call" to o("id" to "tc-4", "name" to "mcp__linear__list", "input" to o()))); f.adaptCline("PreToolUse"); assertEquals("mcp__linear__list", f.toolName)
        val post = HookInput.fromJson(o("hookName" to "tool_result", "taskId" to "task-1", "workspaceRoots" to listOf("/work/app"), "tool_result" to o("id" to "tc-1", "name" to "run_commands", "input" to o("commands" to listOf("git status")), "output" to "clean\n", "durationMs" to 12)))
        post.adaptCline("PostToolUse"); assertEquals("PostToolUse", post.hookEventName); assertEquals("Bash", post.toolName); assertEquals(JsonPrimitive("clean\n"), post.toolResponse); assertNotNull(post.postTool())
        val err = HookInput.fromJson(o("hookName" to "tool_result", "taskId" to "task-1", "tool_result" to o("id" to "tc-1", "name" to "run_commands", "input" to o("commands" to listOf("git status")), "output" to "out\n", "error" to "boom")))
        err.adaptCline("PostToolUse"); assertEquals("out\n", err.toolResponse["stdout"].s); assertEquals("boom", err.toolResponse["stderr"].s)
        val st = HookInput.fromJson(o("hookName" to "agent_start", "taskId" to "task-1", "workspaceRoots" to listOf("/work/app"))); st.adaptCline("TaskStart"); assertEquals("SessionStart", st.hookEventName)
        val ab = HookInput.fromJson(o("hookName" to "agent_abort", "taskId" to "task-1")); ab.adaptCline("TaskCancel"); assertEquals("Noop", ab.hookEventName)
        val bare = HookInput.fromJson(o("taskId" to "task-9", "tool_call" to o("id" to "tc-9", "name" to "run_commands", "input" to o("commands" to listOf("ls"))))); bare.adaptCline("PreToolUse")
        assertEquals("PreToolUse", bare.hookEventName); assertEquals("task-9", bare.sessionId)
    }
    @Test fun commandcodeMapsToolNamesSessionAndProjectDir() {
        val pre = HookInput.fromJson(o("session_id" to "cc-1", "transcript_path" to "/tmp/t.jsonl", "cwd" to "/work/app", "hook_event_name" to "PreToolUse", "permission_mode" to "default", "tool_name" to "shell_command", "tool_input" to o("command" to "git status")))
        pre.adaptCommandcode("PreToolUse", "/env/proj"); assertEquals("PreToolUse", pre.hookEventName); assertEquals("cc-1", pre.sessionId); assertEquals("Bash", pre.toolName)
        assertEquals("git status", pre.toolInput["command"].s); assertEquals("/work/app", pre.cwd); assertNotNull(pre.preTool())
        for ((t, want) in listOf("read_file" to "Read", "write_file" to "Write", "edit_file" to "Edit")) {
            val x = HookInput.fromJson(o("session_id" to "cc-1", "hook_event_name" to "PreToolUse", "tool_name" to t, "tool_input" to o("file_path" to "a"))); x.adaptCommandcode("PreToolUse", null); assertEquals(want, x.toolName)
        }
        val bare = HookInput.fromJson(o("session_id" to "cc-2", "tool_name" to "shell_command", "tool_input" to o("command" to "ls"))); bare.adaptCommandcode("PreToolUse", "/env/proj")
        assertEquals("/env/proj", bare.cwd); assertEquals("PreToolUse", bare.hookEventName)
        val other = HookInput.fromJson(o("tool_name" to "mcp__github__execute_query", "tool_input" to o())); other.adaptCommandcode("PreToolUse", null); assertEquals("mcp__github__execute_query", other.toolName)
    }
}
