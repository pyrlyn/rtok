// Port of the src/measure/subagents.rs tests (T128, T131): the three §17.1 shares with the parent-first split, and the
// briefed/plain split of the re-read share.
@file:Suppress("MagicNumber")

package rtok.measure

import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubagentsTest {
    private fun write(
        path: String,
        vararg lines: String,
    ) = writeString(path, lines.joinToString("\n"))

    private fun collect(dir: String): Subagents {
        val parsed = parsePath("$dir/sess.jsonl")!!
        return SubagentScan.collect(listOf(SubagentParent("$dir/sess.jsonl", parsed)), 0)!!
    }

    @Test fun oneParentAndTwoSiblingsAssertTheThreeShares() {
        val dir = tmpDir("t128-shares")
        val subs = "$dir/sess/subagents"
        createDirAll(subs)
        write(
            "$dir/sess.jsonl",
            """{"type":"user","message":{"role":"user","content":"go"}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[""" +
                """{"type":"tool_use","id":"t0a","name":"Read","input":{"file_path":"a.rs"}},""" +
                """{"type":"tool_use","id":"t0b","name":"Read","input":{"file_path":"b.rs"}},""" +
                """{"type":"tool_use","id":"t1","name":"Agent","input":{"prompt":"one"}},""" +
                """{"type":"tool_use","id":"t2","name":"Agent","input":{"prompt":"two"}}],"usage":{"input_tokens":1,"output_tokens":1}}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t0a","content":"AAAA"},""" +
                """{"type":"tool_result","tool_use_id":"t0b","content":"BBBBBB"},{"type":"tool_result","tool_use_id":"t1","content":""},""" +
                """{"type":"tool_result","tool_use_id":"t2","content":""}]}}""",
        )
        write(
            "$subs/agent-1.jsonl",
            """{"type":"user","message":{"role":"user","content":"one"}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[""" +
                """{"type":"tool_use","id":"u1","name":"Read","input":{"file_path":"a.rs"}},""" +
                """{"type":"tool_use","id":"u2","name":"Read","input":{"file_path":"c.rs"}},""" +
                """{"type":"tool_use","id":"u3","name":"Bash","input":{"command":"ls"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"u1","content":"AAAA"},""" +
                """{"type":"tool_result","tool_use_id":"u2","content":"CCCCCC"},{"type":"tool_result","tool_use_id":"u3","content":"XXXXX"}]}}""",
        )
        write(
            "$subs/agent-2.jsonl",
            """{"type":"user","message":{"role":"user","content":"two"}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[""" +
                """{"type":"tool_use","id":"u1","name":"Read","input":{"file_path":"c.rs"}},""" +
                """{"type":"tool_use","id":"u2","name":"Read","input":{"file_path":"d.rs"}}],""" +
                """"usage":{"input_tokens":2,"cache_read_input_tokens":7,"cache_creation_input_tokens":3,"output_tokens":4}}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"u1","content":"CC"},""" +
                """{"type":"tool_result","tool_use_id":"u2","content":"DDD"}]}}""",
        )
        writeString("$subs/agent-1.meta.json", """{"agentType":"explore","model":"haiku","toolUseId":"t1"}""")
        writeString("$subs/agent-2.meta.json", """{"agentType":"general-purpose","toolUseId":"t2"}""")

        val out = collect(dir)
        assertEquals(1L to 2L, out.sessions to out.count)
        assertEquals(20L, out.resultBytes)
        assertEquals(10L, out.parentResultBytes)
        assertEquals(15L, out.readBytes)
        assertEquals(4L, out.readParent)
        assertEquals(2L, out.readSibling)
        assertEquals(6L, out.rereadBytes)
        assertEquals(100.0 * 6.0 / 15.0, out.shareRead)
        assertEquals(100.0 * 6.0 / 20.0, out.shareResults)
        assertEquals(100.0 * 6.0 / 30.0, out.shareTree)
        assertEquals(listOf(2L, 7L, 3L, 4L), listOf(out.usageInput, out.usageCacheRead, out.usageCacheWrite, out.usageOutput))
        assertEquals(2, out.byType.size)
        val explore = out.byType[0]
        assertEquals(Triple("explore", "haiku", 1L), Triple(explore.agentType, explore.model, explore.count))
        assertEquals(10L to 4L, explore.readBytes to explore.rereadBytes)
        val general = out.byType[1]
        assertEquals(Triple("general-purpose", "-", 1L), Triple(general.agentType, general.model, general.count))
        assertEquals(5L to 2L, general.readBytes to general.rereadBytes)
        assertTrue(isSubagent("$subs/agent-1.jsonl"))
        assertFalse(isSubagent("$dir/sess.jsonl"))
    }

    @Test fun briefedAndPlainSubagentsSplitTheRereadShare() {
        val dir = tmpDir("t131-brief-split")
        val subs = "$dir/sess/subagents"
        createDirAll(subs)
        write(
            "$dir/sess.jsonl",
            """{"type":"user","message":{"role":"user","content":"go"}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[""" +
                """{"type":"tool_use","id":"t0","name":"Read","input":{"file_path":"p.rs"}},""" +
                """{"type":"tool_use","id":"t1","name":"Agent","input":{"prompt":"one"}},""" +
                """{"type":"tool_use","id":"t2","name":"Agent","input":{"prompt":"two"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t0","content":"AAAA"},""" +
                """{"type":"tool_result","tool_use_id":"t1","content":""},{"type":"tool_result","tool_use_id":"t2","content":""}]}}""",
        )
        write(
            "$subs/agent-1.jsonl",
            """{"type":"user","message":{"role":"user","content":"one"}}""",
            """{"type":"attachment","attachment":{"type":"hook_additional_context","hookName":"SubagentStart","hookEvent":"SubagentStart",""" +
                """"toolUseID":"t1","content":["p.rs — rtok expand abc123\n$SPAWN_BRIEF_MARKER <id>; answer with path:line citations."]}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[""" +
                """{"type":"tool_use","id":"u1","name":"Read","input":{"file_path":"p.rs"}},""" +
                """{"type":"tool_use","id":"u2","name":"Read","input":{"file_path":"q.rs"}}],"usage":{"input_tokens":5}}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"u1","content":"AAAA"},""" +
                """{"type":"tool_result","tool_use_id":"u2","content":"QQQQQQ"}]}}""",
        )
        write(
            "$subs/agent-2.jsonl",
            """{"type":"user","message":{"role":"user","content":"go"}}""",
            """{"type":"attachment","attachment":{"type":"hook_additional_context","hookName":"SubagentStart","hookEvent":"SubagentStart",""" +
                """"toolUseID":"t2","content":["rtok agent id: ed8320f8"]}}""",
            """{"type":"assistant","message":{"role":"assistant","content":[""" +
                """{"type":"tool_use","id":"u1","name":"Read","input":{"file_path":"r.rs"}}],"usage":{"input_tokens":2}}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"u1","content":"RRR"}]}}""",
        )
        writeString("$subs/agent-1.meta.json", """{"agentType":"general-purpose","toolUseId":"t1"}""")
        writeString("$subs/agent-2.meta.json", """{"agentType":"general-purpose","toolUseId":"t2"}""")

        val out = collect(dir)
        assertEquals(Triple(1L, 10L, 4L), Triple(out.withBrief.count, out.withBrief.readBytes, out.withBrief.rereadBytes))
        assertEquals(100.0 * 4.0 / 10.0, out.withBrief.shareRead)
        assertEquals(5L, out.withBrief.usageInput)
        assertEquals(Triple(1L, 3L, 0L), Triple(out.withoutBrief.count, out.withoutBrief.readBytes, out.withoutBrief.rereadBytes))
        assertEquals(0.0, out.withoutBrief.shareRead)
        assertEquals(2L, out.withoutBrief.usageInput)
    }
}
