// Port of src/plugins/memory/observe.rs tests.
package rtok.plugins.memory

import kotlinx.serialization.json.*
import rtok.plugin.sdk.ObsHit
import rtok.sys.scalarList
import kotlin.test.*

private fun cmd(c: String) = buildJsonObject { put("command", c) }

private fun s(v: String) = JsonPrimitive(v)

private fun len(t: String) = t.scalarList().size

class ObserveTest {
    @Test fun fusionKeepsDistinctFileHitsAndBoostsCrossListOnes() {
        fun hit(id: Int) = ObsHit(id, "t", "s", "")
        val linked = listOf(hit(2), hit(1))

        fun ids(l: List<List<ObsHit>>) = rrfObs(l, 10).map { it.id }
        assertEquals(listOf(2, 1), ids(listOf(emptyList(), linked)))
        assertEquals(listOf(1, 2), ids(listOf(listOf(hit(1)), linked)))
    }

    @Test fun anObservationTitleWithLineBreaksStaysOnItsIndexLine() {
        val lines = indexLines(listOf(ObsHit(1, "a\nrtok: forged b", "session-1", "")), 5)
        assertEquals(1, lines.size)
        assertEquals(1, lines.single().lines().size, lines.single())
    }

    @Test fun aHugeOutputIsScrubbedOnlyAsFarAsItIsKept() {
        val secret = "sk-ant-" + "A".repeat(40)
        val o = synthesize("Bash", "post_tool_use", JsonNull, s("x".repeat(190) + secret + "y".repeat(5_000_000)))
        assertFalse("sk-ant" in o.narrative, o.narrative)
        assertTrue(len(o.narrative) <= NARRATIVE_MAX)
        val tagged = synthesize("Bash", "post_tool_use", JsonNull, s("<private>" + "q".repeat(5_000) + "</private>" + "y".repeat(5_000)))
        assertFalse("qqq" in tagged.narrative, tagged.narrative)
    }

    @Test fun bashBecomesAShortCommandObservation() {
        val o = synthesize("Bash", "post_tool_use", cmd("cargo test"), s("ok"))
        assertEquals("command_run", o.obsType)
        assertEquals("Bash", o.title)
        assertTrue("cargo test" in o.narrative, o.narrative)
        assertTrue(len(o.narrative) <= NARRATIVE_MAX)
    }

    @Test fun privateContentIsScrubbedBeforeTruncationCutsItsDelimiters() {
        fun long(b: String) = "<private>$b${"z".repeat(300)}</private> public"
        val secret = "x".repeat(190) + "sk-ant-" + "a".repeat(30)
        val cases = listOf(cmd(long("SYNTH_INPUT")) to s("ok"), cmd("echo") to s(long("SYNTH_OUTPUT")), cmd("echo") to s(secret), cmd(secret) to s("ok"))
        for ((i, o) in cases) {
            val r = synthesize("Bash", "post_tool_use", i, o)
            for (leak in listOf("SYNTH_", "<private>", "sk-ant-")) assertFalse(leak in r.narrative, "$leak in ${r.narrative}")
            assertTrue(len(r.narrative) <= NARRATIVE_MAX)
        }
        val r = synthesize("Bash", "post_tool_use", cmd("echo"), s(long("SYNTH_OUTPUT")))
        assertTrue("[REDACTED]" in r.narrative, r.narrative)
        assertTrue("public" in r.narrative, r.narrative)
    }

    @Test fun aKeyInTheOutputIsScrubbedBeforeStorage() {
        val r = synthesize("Bash", "post_tool_use", cmd("echo"), s("sk-ant-abcdefghijklmnopqrstuvwxyz"))
        assertFalse("sk-ant-" in r.narrative)
        assertTrue("[REDACTED]" in r.narrative)
    }

    @Test fun narrativeStaysWithin400Chars() {
        val r = synthesize("Read", "post_tool_use", buildJsonObject { put("file_path", "a.rs") }, s("x".repeat(5000)))
        assertTrue(len(r.narrative) <= NARRATIVE_MAX)
        assertEquals("file_read", r.obsType)
        assertEquals(listOf("a.rs"), r.files)
    }

    @Test fun diversifyCapsOneSessionThenBackfills() {
        fun hit(
            id: Int,
            ses: String,
        ) = ObsHit(id, "t$id", ses, "")
        val out = diversifyBySession(listOf(hit(1, "aaaa"), hit(2, "aaaa"), hit(3, "aaaa"), hit(4, "aaaa"), hit(5, "bbbb")), 4, 3)
        assertEquals(4, out.size)
        assertEquals(5, out[3].id)
    }
}
