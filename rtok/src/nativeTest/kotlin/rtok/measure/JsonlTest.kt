// Port of the src/measure/jsonl.rs tests that cover what `rtok stats` reads: the 200-line fixture's counts, streamed
// lines of one message, malformed lines, thinking blocks and the `SubagentStart` context.
@file:Suppress("MagicNumber")

package rtok.measure

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonlTest {
    private fun fixture200(): String {
        val lines = ArrayList<String>()
        for (i in 0 until 40) {
            lines +=
                """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"ok $i"},""" +
                """{"type":"tool_use","id":"t$i","name":"Bash","input":{"command":"ls"}}],""" +
                """"usage":{"input_tokens":10,"cache_creation_input_tokens":20,"cache_read_input_tokens":30,"output_tokens":40}}}"""
            lines += """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t$i","content":"out $i"}]}}"""
        }
        for (i in 0 until 40) {
            lines += """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"hi $i"}],"usage":{"input_tokens":1,"output_tokens":2}}}"""
        }
        for (i in 0 until 60) lines += """{"type":"attachment","i":$i}"""
        for (i in 0 until 20) lines += "not-json $i"
        assertEquals(200, lines.size)
        return lines.joinToString("\n")
    }

    @Test fun fixture200ExpectedCounts() {
        val p = parseJsonl(fixture200())
        assertEquals(200L, p.lines)
        assertEquals(20L, p.malformed)
        assertEquals(40, p.toolUses.size)
        assertEquals(40, p.toolResults.size)
        assertEquals(80, p.assistantTexts.size)
        assertEquals(80, p.usages.size)
        assertEquals(120, p.turns)
        assertEquals("t0", p.toolUses[0].id)
        assertEquals("out 0", p.toolResults[0].content)
        assertEquals(30L, p.usages[0].cacheReadInputTokens)
        assertEquals(0, p.toolUses[0].turn)
        assertEquals(1, p.toolResults[0].turn)
    }

    @Test fun linesSharingAMessageIdAreOneTurn() {
        val u = """"usage":{"input_tokens":10,"output_tokens":4}"""
        val p =
            parseJsonl(
                listOf(
                    """{"type":"assistant","message":{"id":"msg_1","content":[{"type":"text","text":"hi"}],$u}}""",
                    """{"type":"assistant","message":{"id":"msg_1","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{}}],$u}}""",
                    """{"type":"assistant","message":{"id":"msg_2","content":[{"type":"text","text":"bye"}],$u}}""",
                ).joinToString("\n"),
            )
        assertEquals(Triple(3L, 0L, 1L), Triple(p.lines, p.malformed, p.duplicates))
        assertEquals(2, p.turns)
        assertEquals(20L, p.usages.sumOf { it.inputTokens })
        assertEquals(0, p.toolUses.single().turn)
        assertEquals(listOf("hi", "bye"), p.assistantTexts)
    }

    @Test fun skipsMalformedAndCountsThem() {
        val p = parseJsonl("{\n{}\n")
        assertEquals(2L to 1L, p.lines to p.malformed)
    }

    @Test fun thinkingBlocksCountedOncePerMessageId() {
        val u = """"usage":{"input_tokens":10,"output_tokens":4}"""
        val p =
            parseJsonl(
                listOf(
                    """{"type":"assistant","message":{"id":"m1","content":[{"type":"thinking","thinking":"reasoning here"}],$u}}""",
                    """{"type":"assistant","message":{"id":"m2","content":[{"type":"redacted_thinking","data":"x"}],$u}}""",
                    """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"hi"}],$u}}""",
                ).joinToString("\n"),
            )
        assertEquals(listOf(14L, 1L), p.thinking.map { it.bytes })
        assertEquals(1L, p.duplicates)
    }

    @Test fun subagentStartContextComesFromItsAttachmentOnly() {
        fun att(
            kind: String,
            event: String,
            text: String,
        ) = """{"type":"attachment","attachment":{"type":"$kind","hookEvent":"$event","content":["$text"]}}"""
        val p =
            parseJsonl(
                listOf(
                    """{"type":"user","message":{"role":"user","content":"task"}}""",
                    att("hook_success", "SubagentStart", "ok"),
                    att("hook_additional_context", "SessionStart", "recall"),
                    att("hook_additional_context", "SubagentStart", "id"),
                    att("hook_additional_context", "SubagentStart", "brief"),
                ).joinToString("\n"),
            )
        assertEquals("id\nbrief", p.subagentStartContext)
        assertEquals("task", p.firstUserText)
        assertEquals(1, p.turns)
    }

    @Test fun imagesInResultsAndPromptsCarrySizeAndTokens() {
        val png = ImageCostTest.png(200, 200)
        val p =
            parseJsonl(
                listOf(
                    """{"type":"user","message":{"content":[{"type":"image","source":{"type":"base64","data":"$png"}}]}}""",
                    """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[""" +
                        """{"type":"image","source":{"data":"R0lGODlh"}},{"type":"text","text":"ok"}]}]}}""",
                ).joinToString("\n"),
            )
        assertEquals(listOf("", "t1"), p.images.map { it.toolUseId })
        assertEquals(64L, p.images[0].tokens)
        assertEquals(false, p.images[1].sized)
        assertEquals("ok", p.toolResults.single().content)
    }

    @Test fun utf8LenCountsBytes() {
        assertEquals(1L, utf8Len("a"))
        assertEquals(2L, utf8Len("é"))
        assertEquals(3L, utf8Len("→"))
        assertEquals(4L, utf8Len("😀"))
    }
}
