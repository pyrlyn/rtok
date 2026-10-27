@file:Suppress("MagicNumber")

// Port of the tests of src/measure/transcript_cache.rs.

package rtok.measure

import rtok.support.tmpDir
import rtok.sys.removeDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One assistant tool_use and its tool_result. */
internal fun toolPair(
    uid: String,
    name: String,
    input: String,
    body: String,
) = """{"type":"assistant","message":{"id":"a$uid","content":[{"type":"tool_use","id":"$uid","name":"$name","input":$input}]}}""" + "\n" +
    """{"type":"user","message":{"id":"r$uid","content":[{"type":"tool_result","tool_use_id":"$uid","content":"$body"}]}}""" + "\n"

class TranscriptCacheTest {
    @Test
    fun aSkillLoadedAgainBeforeACompactionIsARepeat() {
        val load = { n: Int ->
            """{"type":"assistant","message":{"id":"a$n","content":[{"type":"tool_use","id":"s$n","name":"Skill","input":{"skill":"rtok"}}]}}""" +
                "\n" + """{"type":"user","isMeta":true,"sourceToolUseID":"s$n","message":{"id":"u$n","content":"${"B".repeat(40)}"}}""" + "\n"
        }
        val boundary = """{"type":"system","subtype":"compact_boundary"}""" + "\n"
        val agg = TranscriptCache.aggregate(parseJsonl(load(1) + load(2) + boundary + load(3)), 0, 0)
        assertEquals(3L, agg.skills["rtok"])
        assertEquals(listOf(1L, 10L), agg.skillRepeats["rtok"], "$agg")
    }

    @Test
    fun unchangedTranscriptsAreParsedOnce() {
        val dir = tmpDir("t135-scan")
        val t = "$dir/s.jsonl"
        writeString(t, toolPair("u1", "Read", "{}", "R".repeat(40)) + toolPair("u2", "Skill", """{"skill":"rtok"}""", "ok"))
        val cache = mutableMapOf<String, FileAgg>()
        val (first, parsed) = TranscriptCache.scanWith(cache, dir, 0)
        assertTrue(parsed > 0)
        assertEquals(10L, first[0].second.toolTokens["Read"])
        assertEquals(1L, first[0].second.skills["rtok"])
        assertEquals(first to 0L, TranscriptCache.scanWith(cache, dir, 0))
        val file = "$dir/cache.json"
        TranscriptCache.save(cache, file)
        val reloaded = TranscriptCache.load(file)
        assertEquals(0L, TranscriptCache.scanWith(reloaded, dir, 0).second)
        writeString(t, toolPair("u3", "Grep", "{}", "GGGG"))
        val (changed, again) = TranscriptCache.scanWith(reloaded, dir, 0)
        assertTrue(again > 0)
        assertEquals(1L, changed[0].second.toolTokens["Grep"])
        removeDirAll(dir)
    }
}
