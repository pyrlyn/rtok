// Port of the src/hooks/mod.rs tests: host output shapers, session/slow helpers, budget cap, and
// dispatch with agent message push (T288).
package rtok.hooks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import rtok.config.Config
import rtok.config.envVar
import rtok.sys.tempDir
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.store.inbox
import rtok.store.kvSet
import rtok.store.logsAfter
import rtok.store.measurementCount
import rtok.store.registerAgent
import rtok.store.sendMessage
import rtok.store.undelivered
import kotlin.test.*

class DispatchTest {
    init { rtok.support.installDb() }
    private fun json(b: ByteArray): JsonElement = Json.parseToJsonElement(b.decodeToString())
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)
    private val fixtures = "${envVar("RTOK_FIXTURES")}/hooks"
    private fun fixture(name: String) = rtok.sys.readToString("$fixtures/$name")

    @Test fun copilotOutputShapesPrePostBlockAndEmpty() {
        assertEquals(j("{}"), json(copilotOutput(HookOutput())))
        assertEquals(j("""{"permissionDecision":"allow","permissionDecisionReason":"rtok","modifiedArgs":{"command":"rtok cmd -- git status"}}"""),
            json(copilotOutput(preOut("allow", "rtok", j("""{"command":"rtok cmd -- git status"}""")))))
        assertEquals(j("""{"additionalContext":"ctx"}"""), json(copilotOutput(postOut("ctx"))))
        assertEquals(j("""{"permissionDecision":"deny","permissionDecisionReason":"guard"}"""), json(copilotOutput(HookOutput(decision = "block", reason = "guard"))))
    }

    @Test fun shapersEmitSortedKeysLikeSerdeJson() {
        assertEquals("""{"additionalContext":"c","modifiedArgs":{"a":1,"b":2},"permissionDecision":"allow"}""",
            copilotOutput(HookOutput(hookSpecificOutput = HookSpecificOutput("PreToolUse", "allow", null, j("""{"b":2,"a":1}"""), "c"))).decodeToString())
    }

    @Test fun geminiOutputShapesDenyRewriteContextAndEmpty() {
        assertEquals(j("{}"), json(geminiOutput(HookOutput(), "PreToolUse")))
        assertEquals(j("""{"decision":"deny","reason":"dup"}"""), json(geminiOutput(preOut("deny", "dup", null), "PreToolUse")))
        assertEquals(j("""{"hookSpecificOutput":{"tool_input":{"command":"rtok cmd -- git status"}}}"""),
            json(geminiOutput(preOut(null, null, j("""{"command":"rtok cmd -- git status"}""")), "PreToolUse")))
        assertEquals(j("""{"hookSpecificOutput":{"additionalContext":"ctx"}}"""), json(geminiOutput(postOut("ctx"), "PostToolUse")))
    }

    @Test fun codewhaleOutputFoldsContextIntoAFullTextReplacementAndEmptyStaysEmpty() {
        assertEquals(j("{}"), json(codewhaleOutput(HookOutput(), "hi")))
        assertEquals(j("""{"text":"hi\n\n[hook context] ctx"}"""), json(codewhaleOutput(postOut("ctx"), "hi")))
        assertEquals(j("""{"text":"ctx"}"""), json(codewhaleOutput(postOut("ctx"), null)))
    }

    @Test fun malformedStdinIsEmptyObject() {
        assertEquals("{}", run("PreToolUse", { "not-json".encodeToByteArray() }, testConfig()).decodeToString())
    }

    @Test fun strictPathThrowsOnMalformedStdin() {
        val cfg = testConfig().let { it.copy(hook = it.hook.copy(failOpen = false)) }
        assertTrue("bad stdin" in assertFailsWith<HookError> { run("PreToolUse", { "not-json".encodeToByteArray() }, cfg) }.message!!)
    }

    @Test fun oversizedStdinFailsOpen() {
        val cfg = testConfig().let { it.copy(core = it.core.copy(hookMaxInputBytes = 4u)) }
        assertEquals("{}", run("PreToolUse", { "{\"a\":1}".encodeToByteArray() }, cfg).decodeToString())
    }

    @Test fun clineOutputShapesOverrideContextBlockAndEmpty() {
        assertEquals(j("{}"), json(clineOutput(HookOutput())))
        assertEquals(j("""{"overrideInput":{"commands":["rtok run -- 'git status'"]}}"""),
            json(clineOutput(HookOutput(hookSpecificOutput = HookSpecificOutput("PreToolUse", updatedInput = j("""{"command":"rtok run -- 'git status'"}"""))))))
        assertEquals(j("""{"context":"ctx"}"""), json(clineOutput(postOut("ctx"))))
        assertEquals(j("""{"cancel":true,"errorMessage":"guard"}"""), json(clineOutput(HookOutput(decision = "block", reason = "guard"))))
    }

    @Test fun codebuddyOutputRenamesUpdatedInputAndPassesTheRest() {
        assertEquals(j("{}"), json(codebuddyOutput(HookOutput())))
        val v = json(codebuddyOutput(preOut(null, null, j("""{"command":"rtok run -- ls"}"""))))
        assertEquals(j("""{"hookSpecificOutput":{"hookEventName":"PreToolUse","modifiedInput":{"command":"rtok run -- ls"}}}"""), v)
        val deny = preOut("deny", "guard", null)
        assertEquals(deny.toJson(), json(codebuddyOutput(deny)))
        assertEquals(postOut("ctx").toJson(), json(codebuddyOutput(postOut("ctx"))))
    }

    @Test fun codexOutputAllowsARewriteAndDropsCursorKeys() {
        assertEquals(j("{}"), json(codexOutput(HookOutput())))
        assertEquals(j("""{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","permissionDecisionReason":"rtok","updatedInput":{"command":"rtok run -- ls"}}}"""),
            json(codexOutput(preOut(null, "rtok", j("""{"command":"rtok run -- ls"}""")))))
        for (out in listOf(preOut("deny", "guard", null), postOut("ctx"))) assertEquals(out.toJson(), json(codexOutput(out)))
        val mcp = postOut("ctx").let { it.copy(hookSpecificOutput = it.hookSpecificOutput!!.copy(updatedMcpToolOutput = j("""{"content":[]}"""))) }
        assertEquals(postOut("ctx").toJson(), json(codexOutput(mcp)))
    }

    @Test fun cursorOutputEmitsSnakeCaseMcpReplacement() {
        val out = postOut("ctx").let { it.copy(hookSpecificOutput = it.hookSpecificOutput!!.copy(updatedMcpToolOutput = j("""{"content":[]}"""))) }
        assertEquals("""{"additional_context":"ctx","updated_mcp_tool_output":{"content":[]}}""", cursorOutput(out).decodeToString())
        assertEquals(preOut("deny", "x", null).encode(), cursorOutput(preOut("deny", "x", null)).decodeToString())
    }

    @Test fun resolveSessionPrefersStdinThenEnv() {
        val env = { _: String -> "env-sess" }
        assertEquals("stdin-sess", resolveSession("stdin-sess", "ANY_KEY", env))
        assertEquals("env-sess", resolveSession("", "ANY_KEY", env))
        assertEquals("padded", resolveSession("", "ANY_KEY") { "  padded  " })
        assertEquals("unknown", resolveSession("", "", env))
        assertEquals("unknown", resolveSession("", "ANY_KEY") { null })
        assertEquals("unknown", resolveSession("", "ANY_KEY") { "   " })
    }

    @Test fun slowNoteFiresOnlyOverBudget() {
        assertNotNull(slowNote(12.0, 10u, "PreToolUse"))
        assertNull(slowNote(9.9, 10u, "PreToolUse")); assertNull(slowNote(500.0, 0u, "PreToolUse"))
    }

    @Test fun slowTallyWarnsOncePerIntervalWithCountAndWorst() {
        var (t, due) = slowTally(null, 1000, 12.0, 300u)
        assertEquals(1L to 12.0, due); assertEquals("1000 0 0", t)
        slowTally(t, 1100, 40.0, 300u).let { t = it.first; assertNull(it.second) }
        slowTally(t, 1200, 25.0, 300u).let { t = it.first; assertNull(it.second) }
        slowTally(t, 1300, 11.0, 300u).let { assertEquals(3L to 40.0, it.second); assertEquals("1300 0 0", it.first) }
    }

    @Test fun slowTallyZeroIntervalAndGarbage() {
        val (t, due) = slowTally("1000 0 0", 1000, 12.0, 0u)
        assertEquals(1L to 12.0, due)
        assertEquals(1L to 13.0, slowTally(t, 1000, 13.0, 0u).second)
        assertEquals(1L to 13.0, slowTally("junk", 5, 13.0, 300u).second)
    }

    private fun mem(session: String, f: (Config) -> Config = { it }) = rtok.support.installDb().let { Runtime.inMemory(session) }.also { it.config = f(it.config) }

    @Test fun capBudgetMarksDropWhenFirstLineExceeds() = mem("cap-first") { it.copy(plugins = it.plugins.copy(inject = it.plugins.inject.copy(budgetTokens = 40u))) }.use { cx ->
        val want = "word ".repeat(400).trimEnd()
        val out = capBudget(cx, want)
        assertTrue(out.isNotEmpty()); assertTrue("dropped:post_tool:" in out, out)
        assertTrue(cx.estimate(out, Class.Prose) <= 40u, out)
    }

    @Test fun capBudgetGivesBackLinesSoTheMarkerFits() = mem("cap-giveback") { it.copy(plugins = it.plugins.copy(inject = it.plugins.inject.copy(budgetTokens = 40u))) }.use { cx ->
        val text = (0 until 200).joinToString("\n") { "l$it" }
        val out = capBudget(cx, text)
        assertTrue(cx.estimate(out, Class.Prose) <= 40u, out)
        val marker = out.substringAfterLast('\n')
        assertTrue(marker.startsWith("dropped:post_tool:"), out)
        assertEquals(rtok.MIN_PREFIX, marker.substringAfterLast("rtok expand ").length, marker)
        assertTrue(text.startsWith(out.substringBeforeLast('\n') + "\n"))
    }

    @Test fun capBudgetKeepsFittingLinesAndNamesTheRest() = mem("cap-rest") { it.copy(plugins = it.plugins.copy(inject = it.plugins.inject.copy(budgetTokens = 30u))) }.use { cx ->
        val out = capBudget(cx, "ok\n" + "word ".repeat(400).trimEnd())
        assertTrue(out.startsWith("ok\n"), out); assertTrue(cx.estimate(out, Class.Prose) <= 30u, out)
    }

    // ── T288 push ──
    private fun pushFixture(name: String, session: String): Triple<ByteArray, HookInput, Pair<Runtime, String>> {
        val v = JsonObject(j(fixture(name)).jsonObject + ("session_id" to JsonPrimitive(session)))
        val stdin = v.toString().encodeToByteArray()
        val input = HookInput.parse(stdin.decodeToString())
        rtok.support.installDb(); val cx = Runtime.inMemory(session)
        val id = cx.store.registerAgent(cx.hostId!!, cx.session, null, null, null)
        return Triple(stdin, input, cx to id)
    }

    private fun ctx(b: ByteArray): String =
        (HookOutput.parse(b.decodeToString()).hookSpecificOutput?.additionalContext) ?: ""

    @Test fun oneMessageIsPushedFramedOnPromptAndToolResult() {
        for (f in listOf("user_prompt_submit.json", "post_tool.json")) {
            val (stdin, input, p) = pushFixture(f, "t288-one"); val (cx, id) = p
            cx.use {
                cx.store.sendMessage(null, id, "hello")
                val frame = rtok.agentMessageFrame(cx.store.undelivered(id)[0])
                val c = ctx(dispatch(stdin, input, cx))
                assertTrue(c.startsWith(frame.trimEnd()), c)
                assertTrue(cx.store.undelivered(id).isEmpty())
            }
        }
    }

    @Test fun anOverBudgetBatchPushesWhatFitsAndCountsTheRestThenDrains() {
        val (stdin, input, p) = pushFixture("user_prompt_submit.json", "t288-batch"); val (cx, id) = p
        cx.use {
            for (b in listOf("one", "two", "six")) cx.store.sendMessage(null, id, b)
            val f = cx.store.undelivered(id).map { rtok.agentMessageFrame(it) }
            val more = { n: Int -> Push.moreLine(n) }
            cx.config = cx.config.let { c -> c.copy(agents = c.agents.copy(pushBytes = (f[0].encodeToByteArray().size + more(3).encodeToByteArray().size + 1).toUInt())) }
            assertEquals(f[0] + more(2), ctx(dispatch(stdin, input, cx)))
            assertEquals(f[1] + more(1), ctx(dispatch(stdin, input, cx)))
            assertEquals(f[2].trimEnd(), ctx(dispatch(stdin, input, cx)))
            assertEquals("", ctx(dispatch(stdin, input, cx)))
        }
    }

    @Test fun aMessageOverPushBytesIsAnnouncedOnceByTheMoreLine() {
        val (stdin, input, p) = pushFixture("user_prompt_submit.json", "t288-big"); val (cx, id) = p
        cx.use {
            cx.store.sendMessage(null, id, "hello")
            cx.config = cx.config.let { c -> c.copy(agents = c.agents.copy(pushBytes = 64u)) }
            assertEquals(Push.moreLine(1), ctx(dispatch(stdin, input, cx)))
            assertEquals("{}", dispatch(stdin, input, cx).decodeToString())
            assertEquals(1, cx.store.inbox(id, true, false).size)
        }
    }

    @Test fun anEmptyInboxPrintsNothingAndRecordsNoMeasurement() {
        for (f in listOf("user_prompt_submit.json", "post_tool.json")) {
            val (stdin, input, p) = pushFixture(f, "t288-empty"); val (cx, _) = p
            cx.use { assertEquals("{}", dispatch(stdin, input, cx).decodeToString()); assertEquals(0, cx.store.measurementCount("inject")) }
        }
    }

    @Test fun noPushWhenAgentsAreDisabled() {
        for (f in listOf("user_prompt_submit.json", "post_tool.json")) {
            val (stdin, input, p) = pushFixture(f, "t288-off"); val (cx, id) = p
            cx.use {
                cx.store.sendMessage(null, id, "hello")
                cx.config = cx.config.let { c -> c.copy(agents = c.agents.copy(enabled = false)) }
                assertEquals("{}", dispatch(stdin, input, cx).decodeToString())
                assertEquals(1, cx.store.undelivered(id).size)
            }
        }
    }

    @Test fun slowHookRunRecordsOneWarnRow() = mem("b1e2c3d4-0000-4000-8000-0000000000b1") { it.copy(hook = it.hook.copy(maxMs = 10u)) }.use { cx ->
        noteSlow(cx, "PreToolUse", 50.0)
        val rows = cx.store.logsAfter(0, 10)
        assertEquals(1, rows.size); assertEquals("warn", rows[0].level); assertTrue("slow" in rows[0].message)
        noteSlow(cx, "PreToolUse", 5.0)
        assertEquals(1, cx.store.logsAfter(0, 10).size)
    }

    @Test fun noteSlowLogsOneRowPerEventPerInterval() = mem("b1e2c3d4-0000-4000-8000-0000000000b2") { it.copy(hook = it.hook.copy(maxMs = 10u, slowWarnS = 3600u)) }.use { cx ->
        repeat(10) { noteSlow(cx, "PostToolUse", 50.0) }
        noteSlow(cx, "PreToolUse", 50.0)
        assertEquals(2, cx.store.logsAfter(0, 50).size)
        cx.store.kvSet(slowKey("PostToolUse"), "1 9 50.0")
        noteSlow(cx, "PostToolUse", 70.0)
        val rows = cx.store.logsAfter(0, 50)
        assertEquals(3, rows.size); assertTrue("10 calls" in rows[2].message && "worst 70.0 ms" in rows[2].message, rows[2].message)
        cx.config = cx.config.let { it.copy(hook = it.hook.copy(slowWarnS = 0u)) }
        noteSlow(cx, "PostToolUse", 50.0); noteSlow(cx, "PostToolUse", 50.0)
        assertEquals(5, cx.store.logsAfter(0, 50).size)
    }

    @Test fun agentActivityIsToolPlusShortMainArgument() {
        val input = HookInput.parse("""{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"${"x".repeat(100)}","description":"d"}}""")
        assertEquals("Bash: " + "x".repeat(60), agentActivity(input))
        assertNull(agentActivity(HookInput.parse("""{"hook_event_name":"Stop"}""")))
    }
}

/** Default config finished under a temp home, so error logs never land in a literal `./~`. */
private fun testConfig(): Config = Config().finish("${tempDir()}/rtok-dispatch-test")
