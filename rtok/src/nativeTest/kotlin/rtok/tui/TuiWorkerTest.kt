// Port of app.rs `background_app_switches_tabs_while_the_model_loads` and the Worker's collapse rule.
@file:Suppress("MagicNumber")

package rtok.tui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import tui.Key
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class TuiWorkerTest {
    private fun waitFor(
        what: String,
        step: () -> Boolean,
    ) {
        val end = TimeSource.Monotonic.markNow() + 30.seconds
        while (!step()) {
            check(end.hasNotPassedNow()) { "timed out: $what" }
            platform.posix.usleep(5_000u)
        }
    }

    @Test fun answersOffTheCallerAndCollapsesToTheNewest() {
        val gate = AtomicInt(0)
        val reads = AtomicInt(0)
        val w =
            TuiWorker<Int, Int>("test-worker") { q ->
                while (gate.value == 0) platform.posix.usleep(1_000u)
                reads.incrementAndGet()
                q * 10
            }
        assertTrue(w.running)
        w.request(1, 1)
        w.request(2, 2)
        w.request(3, 3)
        gate.value = 1
        var last: TuiWorker.Done<Int>? = null
        waitFor("answer 3") {
            w.poll()?.let { last = it }
            last?.seq == 3L
        }
        assertEquals(30, last!!.result.getOrThrow())
        assertTrue(reads.value <= 2, "queued requests collapse: ${reads.value} reads")
        w.close()
    }

    @Test fun aFailedReadIsAnAnswer() {
        val w = TuiWorker<Int, Int>("test-fail") { error("boom") }
        w.request(1, 1)
        var d: TuiWorker.Done<Int>? = null
        waitFor("failure") {
            d = w.poll()
            d != null
        }
        assertEquals("boom", d!!.result.exceptionOrNull()?.message)
        w.close()
    }

    @Test fun backgroundAppSwitchesTabsWhileTheModelLoads() {
        val gate = AtomicInt(0)
        val app = TuiApp("")
        app.background { _ ->
            while (gate.value == 0) platform.posix.usleep(1_000u)
            JsonObject(mapOf("plugins" to JsonPrimitive("x")))
        }
        app.request(Config()) { error("inline read on the key thread") }
        assertTrue(app.loading, "the first read is on the worker")
        app.key(Key.Right)
        assertEquals("plugins", app.page, "keys work mid-read")
        app.request(Config()) { error("inline") }
        gate.value = 1
        waitFor("model") {
            app.poll()
            !app.loading
        }
        assertEquals(JsonPrimitive("x"), app.snapshot["plugins"])
        app.request(Config()) { error("inline") }
        assertTrue(app.loading, "a tick requests another read")
    }

    @Test fun withoutAWorkerTheReadIsInline() {
        val app = TuiApp("")
        app.request(Config()) { JsonObject(mapOf("a" to JsonPrimitive(1))) }
        assertFalse(app.loading)
        assertEquals(JsonPrimitive(1), app.snapshot["a"])
    }
}
