// Port of agents/claude/mod.rs hook-walk tests (stale_bin_and_timeout_are_rewritten_in_place,
// a_pair_rtok_no_longer_installs_is_pruned, remove_keeps_a_user_command_that_chains_rtok,
// wrong_shaped_hooks_key_is_replaced_not_panicked_on, command_resolves_rtok_at_run_time_and_fails_open_silently),
// plus own tests for strip of an edited hook and the `--host` spelling.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.agent.sdk.Apply
import rtok.agent.sdk.NO_CHANGES
import rtok.sys.ProgressRunner
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

private val C = ClaudeHooks.CLAUDE
private const val TIMEOUT = 5L
private const val CAP = 65_536L

class ClaudeHooksTest {
    private val pre = HookSpec(listOf("PreToolUse" to "Bash"), "timeout", 7)

    @Test fun staleBinAndTimeoutAreRewrittenInPlace() {
        val hooks =
            obj(
                """{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo mine"},""" +
                    """{"type":"command","command":"/old/store/rtok/v0.1.0/rtok hook PreToolUse","timeout":3}]}]}""",
            )
        val want = ClaudeHooks.command("rtok", "PreToolUse")
        val (out, report) = HookInsert.insert(C, hooks, pre, "rtok")
        assertEquals("~ PreToolUse Bash $want\n1 updates", report)
        val inner = out["PreToolUse"]!!.jsonArray[0].jsonObject["hooks"]!!.jsonArray
        assertEquals("echo mine", inner[0].jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals(want, inner[1].jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals("7", inner[1].jsonObject["timeout"]!!.jsonPrimitive.content)
        assertEquals(1, out["PreToolUse"]!!.jsonArray.size)
        assertEquals(out to NO_CHANGES, HookInsert.insert(C, out, pre, "rtok"))
    }

    @Test fun aPairRtokNoLongerInstallsIsPruned() {
        val hooks =
            obj(
                """{"PostToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"rtok hook PostToolUse"}]}],""" +
                    """"Stop":[{"hooks":[{"type":"command","command":"rtok hook Stop"},{"type":"command","command":"notify-send done"}]}],""" +
                    """"Notification":[{"hooks":[{"type":"command","command":"rtok hook Notification"}]}]}""",
            )
        val (out, report) = HookInsert.insert(C, hooks, HookSpec(listOf("PostToolUse" to "*"), "timeout", TIMEOUT), "rtok")
        assertTrue(report.endsWith("1 additions, 3 removals"), report)
        assertTrue(report.contains("- PostToolUse Bash rtok hook PostToolUse"), report)
        val post = out["PostToolUse"]!!.jsonArray
        assertEquals(1, post.size)
        assertEquals("*", post[0].jsonObject["matcher"]!!.jsonPrimitive.content)
        assertEquals(obj("""{"a":[{"type":"command","command":"notify-send done"}]}""")["a"], out["Stop"]!!.jsonArray[0].jsonObject["hooks"])
        assertNull(out["Notification"])
    }

    @Test fun removeKeepsAUserCommandThatChainsRtok() {
        val chain = "notify-send hi && rtok hook PreToolUse"
        val root = obj("""{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"$chain"}]}]}}""")
        val ctx = HookEdit(C, Apply(), "/x/settings.json", HookSpec(listOf("PreToolUse" to "Bash"), "timeout", 5), "rtok")
        val (installed, _) = ClaudeHooks.edit(root, false, ctx)
        val (removed, report) = ClaudeHooks.edit(installed, true, ctx)
        assertEquals("1 removed", report)
        val text = removed.toString()
        assertTrue(chain in text, text)
        assertFalse("exec rtok hook PreToolUse;" in text, text)
    }

    @Test fun wrongShapedHooksKeyIsReplacedNotPanickedOn() {
        val spec = HookSpec(HookEvents.entries("qwen"), "timeout", 5)
        for (body in listOf("""{"hooks":[]}""", """{"hooks":"nope"}""", """{"hooks":{"PreToolUse":"nope"}}""")) {
            val (out, report) = ClaudeHooks.edit(obj(body), false, HookEdit(C, Apply(), "/x", spec))
            assertTrue(report.endsWith("${spec.entries.size} additions"), "$body → $report")
            assertTrue(out["hooks"]!!.jsonObject["PreToolUse"] is JsonArray, body)
        }
    }

    @Test fun stripLeavesAnEditedHookWithoutYesAndDropsEmptiedEvents() {
        val root =
            obj(
                """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"rtok hook PreToolUse","timeout":9}]}],""" +
                    """"Stop":[{"hooks":[{"type":"command","command":"rtok hook Stop","timeout":5}]}]}}""",
            )
        val spec = HookSpec(listOf("PreToolUse" to "Bash", "Stop" to ""), "timeout", 5)
        val (out, report) = ClaudeHooks.edit(root, true, HookEdit(C, Apply(dryRun = true), "/x/s.json", spec))
        assertEquals("? hooks.PreToolUse Bash in /x/s.json (changed by you; remove asks)\n1 removed", report)
        assertEquals(setOf("PreToolUse"), out["hooks"]!!.jsonObject.keys)
        assertEquals(NO_CHANGES, ClaudeHooks.edit(JsonObject(root - "hooks"), true, HookEdit(C, Apply(), "/x", spec)).second)
    }

    @Test fun hostSpellingNamesTheHost() {
        assertEquals("/opt/rtok hook Stop --host devin", ClaudeHooks.commandFor("devin", "/opt/rtok", "Stop"))
        val f = ClaudeHooks.formFor("devin")
        assertTrue(f.isOurs("\"/opt/my rtok/rtok\" hook Stop --host devin", "Stop"))
        assertTrue(f.isOurs(ClaudeHooks.commandFor("devin", "rtok", "Stop"), "Stop"))
        assertFalse(f.isOurs("rtok hook Stop", "Stop"))
        assertFalse(C.isOurs("echo rtok hook Stop", "Stop"))
    }

    @Test fun commandResolvesRtokAtRunTimeAndFailsOpenSilently() {
        val home = join(tempDir(), "rtok-t174-${kotlin.random.Random.nextLong().toULong()}")
        val empty = join(home, "empty-path").also(::createDirAll)

        fun run(event: String): String {
            val sh = "HOME='$home' PATH='$empty' exec /bin/sh -c '${ClaudeHooks.command("rtok", event).replace("'", "'\\''")}' </dev/null"
            val out = ProgressRunner("", "/bin/sh", listOf("-c", sh)).quiet().runCapped(CAP).first
            assertEquals(0, out.exitCode, event)
            return out.stdout.decodeToString()
        }
        assertEquals("", run("PreToolUse"))
        assertEquals("", run("PostToolUse"))
        val note = obj(run("SessionStart"))["hookSpecificOutput"]!!.jsonObject
        assertEquals("SessionStart", note["hookEventName"]!!.jsonPrimitive.content)
        assertTrue("ketch install pyrlyn/rtok" in note["additionalContext"]!!.jsonPrimitive.content)
        val ketch = join(home, ".ketch/bin")
        createDirAll(ketch)
        writeString(join(ketch, "rtok"), "#!/bin/sh\nprintf 'ketch %s' \"\$2\"\n")
        ProgressRunner("", "/bin/chmod", listOf("755", join(ketch, "rtok"))).quiet().runCapped(CAP)
        assertEquals("ketch SessionStart", run("SessionStart"))
        removeDirAll(home)
    }
}
