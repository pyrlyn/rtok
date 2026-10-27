// Port of the transcript-walk tests of src/measure/stats.rs: CTT and tool totals, p95, thinking, dictionary, images,
// skills, edits and compactions.
@file:Suppress("MagicNumber")

package rtok.measure.stats

import rtok.measure.DictKind
import rtok.measure.DictionaryRow
import rtok.measure.ImageCostTest
import rtok.measure.estTokens
import rtok.measure.utf8Len
import rtok.sys.writeBytes
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StatsCollectTest {
    @Test fun cttAndToolTotalsOnMiniSession() {
        val dir = statsDir()
        // 3 turns; the 13-byte result at turn 1 → 4 tokens, ctt = 4 × (3 − 1).
        writeString(
            "$dir/s.jsonl",
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"cd x && sed -n p"}}],"usage":{"input_tokens":10,"output_tokens":1}}}""",
                toolResult("t1", "hello world!!"),
                """{"type":"assistant","message":{"content":[{"type":"text","text":"done"}],"usage":{"input_tokens":20,"cache_read_input_tokens":80,"output_tokens":2}}}""",
            ).joinToString("") { "$it\n" },
        )
        // p95 fixture: twenty 1-byte Reads and one 1000-byte Read; nearest-rank p95 is the 20th of 21.
        val p =
            (0 until 20).flatMap { i -> listOf(toolCall("r$i", "Read", """{"file_path":"a"}"""), toolResult("r$i", "x")) } +
                listOf(toolCall("big", "Read", """{"file_path":"b"}"""), toolResult("big", "y".repeat(1000)))
        writeString("$dir/p.jsonl", p.joinToString("") { "$it\n" })
        val r = collect(dir, cutoff(), "", defaultReplay())
        assertEquals(2, r.sessions)
        val bash = r.tools["Bash"]!!
        assertEquals(Triple(1L, 4L, 8L), Triple(bash.count, bash.estTokens, bash.ctt))
        assertEquals(1, r.bashFamilies["sed"]!!.count)
        assertEquals(80, r.usageCacheRead)
        val read = r.tools["Read"]!!
        assertEquals(Triple(21L, 1000L, 1L), Triple(read.count, read.max, read.p95))
        assertNotEquals(read.p95, read.max)
    }

    @Test fun p95IsNearestRankNotMax() {
        val map = SortedMap<SizeRow>()
        val samples = RowSamples()
        repeat(100) { samples.add(map, "Read", 1, 1, 0) }
        samples.add(map, "Read", 1000, 250, 0)
        samples.finish(map)
        assertEquals(1000L to 1L, map["Read"]!!.max to map["Read"]!!.p95)
    }

    @Test fun p95SurvivesTheCapWithoutRng() {
        val v = ArrayList<Long>()
        for (i in 0 until P95_CAP + 100) pushCapped(v, i.toLong())
        assertTrue(v.size <= P95_CAP)
        val once = nearestP95(ArrayList(v))
        assertEquals(once, nearestP95(ArrayList(v)))
        assertTrue(once <= P95_CAP + 100)
    }

    @Test fun thinkingBlocksCountedPerSession() {
        val dir = statsDir()
        writeString(
            "$dir/t.jsonl",
            listOf(
                """{"type":"assistant","message":{"id":"m1","content":[{"type":"thinking","thinking":"reason 123"}],"usage":{"input_tokens":10,"output_tokens":1}}}""",
                """{"type":"assistant","message":{"id":"m2","content":[{"type":"redacted_thinking","data":"x"}],"usage":{"input_tokens":11,"output_tokens":1}}}""",
                """{"type":"assistant","message":{"id":"m1","content":[{"type":"text","text":"answer"}],"usage":{"input_tokens":10,"output_tokens":1}}}""",
            ).joinToString("") { "$it\n" },
        )
        val r = collect(dir, cutoff(), "", Replay())
        assertEquals(2L to 11L, r.thinking.blocks to r.thinking.bytes)
    }

    /** T403: the dictionary row prints against the billed input, and stays out when nothing was sampled. */
    @Test fun dictionaryRowPrintsSharesOfBilledInput() {
        val r = Report()
        assertFalse("dictionary" in r.toTable())
        r.dictionary.requests = 2
        r.dictionary.inputBytes = 4000
        r.dictionary.requestTokens = 2500
        r.dictionary.paths.repeated = 400
        r.dictionary.paths.saved = 100
        val table = r.toTable()
        assertTrue("dictionary requests 2  content bytes 4000  billed input est. bytes 10000" in table, table)
        assertTrue("paths repeated 400 (4.00% of billed)  cache-safe saving 100 (1.00% of billed, 2.50% of content)" in table, table)
        assertEquals(DictionaryRow(2, 4000, 2500, DictKind(400, 100)), r.dictionary)
    }

    /** T137: a PNG screenshot in a tool_result and a pasted JPEG, both before the second of two requests. */
    @Test fun imageBlocksCountedWithSizeAndTokens() {
        val dir = statsDir()

        fun img(
            data: String,
            mt: String,
        ) = """{"type":"image","source":{"type":"base64","media_type":"$mt","data":"$data"}}"""
        val lines =
            listOf(
                """{"type":"assistant","message":{"id":"a1","content":[{"type":"tool_use","id":"s1","name":"screenshot","input":{}}],"usage":{"input_tokens":1000,"output_tokens":1}}}""",
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"s1","content":[${img(ImageCostTest.png(1920, 1080), "image/png")}]}]}}""",
                """{"type":"user","message":{"content":[${img(ImageCostTest.jpeg(200, 200), "image/jpeg")},{"type":"text","text":"look"}]}}""",
                """{"type":"assistant","message":{"id":"a2","content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":9000,"output_tokens":1}}}""",
            )
        writeString("$dir/t.jsonl", lines.joinToString("") { "$it\n" })
        val r = collect(dir, cutoff(86_400), "", Replay())
        val d = r.images
        assertEquals(Triple(2L, 2691L + 64, 0L), Triple(d.blocks, d.tokens, d.noSize))
        assertEquals(2691L + 64, d.resident)
        assertEquals(2691, d.bySource["screenshot"]!!.tokens)
        assertEquals(1, d.bySource["user"]!!.blocks)
        val text = r.toTable()
        assertTrue("images blocks 2" in text, text)
        assertTrue("  images user  blocks 1" in text, text)
    }

    /** T61.1: `isMeta` skill bodies keyed by the `Skill` call's `input.skill`; `resident` × the later requests. */
    @Test fun skillsFoldInjectedBodiesAndCountResidentRequests() {
        val dir = statsDir()
        val tiny = "line one\nline two\nline three\n"
        val huge = (0 until 3000).joinToString("") { "body line $it\n" }
        val lines =
            listOf(
                """{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"tu-s1","name":"Skill","input":{"skill":"tiny-skill","args":""}}]},"usage":{"input_tokens":10,"output_tokens":1}}""",
                toolResult("tu-s1", "ideas.md"),
                """{"type":"user","isMeta":true,"sourceToolUseID":"tu-s1","message":{"role":"user","content":[{"type":"text","text":${q(tiny)}}]}}""",
                """{"type":"assistant","message":{"id":"m2","content":[]},"usage":{"input_tokens":100,"output_tokens":2}}""",
                """{"type":"assistant","message":{"id":"m3","content":[{"type":"tool_use","id":"tu-s2","name":"Skill","input":{"skill":"huge-skill","args":""}}]},"usage":{"input_tokens":110,"output_tokens":2}}""",
                """{"type":"user","isMeta":true,"sourceToolUseID":"tu-s2","message":{"role":"user","content":[{"type":"text","text":${q(huge)}}]}}""",
            )
        val r = writeAndCollect(dir, "sk.jsonl", lines)
        val skills = r.skills!!
        val t = skills["tiny-skill"]!!
        assertEquals(listOf(1L, tiny.length.toLong(), tiny.length.toLong(), t.max), listOf(t.count, t.bytes, t.mean, t.p95))
        assertEquals(tiny.length * 2L, t.resident, "the m2 and m3 requests carried it")
        val h = skills["huge-skill"]!!
        assertEquals(Triple(1L, huge.length.toLong(), 0L), Triple(h.count, h.bytes, h.resident))
        assertEquals(8, r.tools["Skill"]!!.totalBytes)
        val table = r.toTable()
        assertTrue("skill" in table && "resident" in table, table)
        assertTrue("\"skills\"" in r.toJsonText())
    }

    @Test fun editRowSumsOldAndNewStringsOverEditAndMultiedit() {
        val dir = statsDir()
        val inputs =
            listOf(
                "Edit" to """{"file_path":"/a.rs","old_string":"0123456789","new_string":"abc"}""",
                "MultiEdit" to """{"file_path":"/b.rs","edits":[{"old_string":"abcd","new_string":"x"},{"old_string":"abcdef","new_string":"xy"}]}""",
                "Bash" to """{"command":"ls"}""",
            )
        val lines =
            inputs.mapIndexed { i, (name, input) ->
                """{"type":"assistant","message":{"id":"m$i","content":[{"type":"tool_use","id":"t$i","name":"$name","input":$input}],"usage":{"input_tokens":1,"output_tokens":10}}}"""
            }
        val r = writeAndCollect(dir, "e.jsonl", lines)
        val e = r.edits
        assertEquals(Triple(3L, 20L, 6L), Triple(e.calls, e.oldBytes, e.newBytes))
        // serde_json writes the input with sorted keys; the byte count is the same either way.
        val denom = inputs.sumOf { (_, input) -> utf8Len(input) }
        assertEquals(denom, e.toolInputBytes)
        val table = r.toTable()
        assertTrue("edit calls 3  old_string 20 B  new_string 6 B" in table, table)
        assertTrue("old/tool_input ${fixed(100.0 * 20.0 / denom, 1)}%" in table, table)
        assertTrue("old/output_tokens (est) 16.7%" in table, table)
        assertEquals(5, estTokens(20))
    }

    /** T946: one undecodable line no longer zeroes the file's compactions. */
    @Test fun compactionsSurviveANonUtf8Line() {
        val dir = statsDir()
        val body =
            "{\"type\":\"system\",\"subtype\":\"compact_boundary\"}\n".encodeToByteArray() +
                "{\"type\":\"user\",\"message\":\"".encodeToByteArray() + byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "\"}\n".encodeToByteArray() +
                "  {\"type\":\"system\",\"subtype\":\"compact_boundary\"}  \n".encodeToByteArray() +
                "{\"type\":\"assistant\",\"message\":{\"content\":\"ok\"}}\n".encodeToByteArray()
        writeBytes("$dir/s.jsonl", body)
        val r = collect(dir, cutoff(), "", defaultReplay())
        assertEquals(Triple(1L, 2L, 1L), Triple(r.sessions, r.compact, r.malformed))
    }

    @Test fun compactBoundaryCountsOncePerEvent() {
        val dir = statsDir()
        writeString(
            "$dir/s.jsonl",
            """
            {"type":"system","subtype":"compact_boundary","content":"Conversation compacted"}
            {"type":"user","isCompactSummary":true,"message":{"content":"summary"}}
            {"type":"assistant","message":{"content":"ok"}}

            """.trimIndent(),
        )
        val r = collect(dir, cutoff(), "", defaultReplay())
        assertEquals(1L to 1L, r.sessions to r.compact)
        assertTrue(r.toTable().startsWith("sessions 1  compact 1  checkpoint 0  no_checkpoint 1  lines 3"), r.toTable())
    }
}
