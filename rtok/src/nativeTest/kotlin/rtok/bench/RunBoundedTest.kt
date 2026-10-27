@file:Suppress("MagicNumber")
// Port of src/bench.rs's `run_bounded` and usage tests: a hung run is killed, output past a pipe buffer is drained,
// a descendant holding the pipe does not stall the bench, a failing run is no result; and the JSON readers.

package rtok.bench

import rtok.cli.benchFlags
import rtok.sys.ProgressRunner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class RunBoundedTest {
    private fun quiet(vararg argv: String) = ProgressRunner("", argv[0], argv.drop(1)).quiet()

    @Test fun aHungRunIsKilledInsteadOfHangingTheBench() {
        val start = TimeSource.Monotonic.markNow()
        assertNull(runBounded(quiet("sleep", "30"), 150))
        assertTrue(start.elapsedNow() < 10.seconds, "killed, not waited out")
    }

    @Test fun aFinishedRunHandsBackItsOutput() {
        assertContentEquals("hello".encodeToByteArray(), runBounded(quiet("sh", "-c", "printf hello"), 30_000))
    }

    @Test fun aRunPastThePipeBufferIsDrainedNotTimedOut() {
        assertEquals(300_000, assertNotNull(runBounded(quiet("sh", "-c", "head -c 300000 /dev/zero"), 10_000)).size)
    }

    @Test fun aDescendantHoldingThePipeDoesNotStallTheBench() {
        val start = TimeSource.Monotonic.markNow()
        val out = assertNotNull(runBounded(quiet("sh", "-c", "sleep 10 & printf parent; exit 0"), 3_000), "child exited cleanly")
        assertEquals("parent", out.decodeToString())
        assertTrue(start.elapsedNow() < 6.seconds, "${start.elapsedNow()}")
    }

    @Test fun aFailingRunIsNotAResult() {
        assertNull(runBounded(quiet("sh", "-c", "printf partial; exit 3"), 30_000))
    }

    @Test fun usageAndToolUsesComeOutOfTheJsonResult() {
        val json =
            """{"usage":{"input_tokens":10,"cache_read_input_tokens":3,"cache_creation_input_tokens":4,"output_tokens":5},""" +
                """"total_cost_usd":0.25,"messages":[{"type":"tool_use"},{"content":[{"type":"tool_use"},{"type":"text"}]}]}"""
        assertEquals(Usage(10, 7, 5, 0.25), parseUsage(json.encodeToByteArray()))
        assertEquals(Usage(), parseUsage("not json".encodeToByteArray()))
        assertEquals(2, countToolUses(parseJson(json.encodeToByteArray())))
    }

    @Test fun onlyTypedFlagsReachTheBenchSection() {
        assertNull(benchFlags(null, null, false, null, null))
        assertEquals(
            """{"bench":{"runs":1,"dry_run":true,"suite":"graph"}}""",
            benchFlags(null, 1, true, null, "graph").toString(),
        )
    }
}
