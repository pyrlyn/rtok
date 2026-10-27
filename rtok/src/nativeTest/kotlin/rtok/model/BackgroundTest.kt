package rtok.model

import rtok.config.Config
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val SPINS = 2000
private const val WAIT_S = 10
private const val NAP_US = 1000u

class BackgroundTest {
    private fun <T : Any> await(get: () -> T?): T {
        val start = TimeSource.Monotonic.markNow()
        while (start.elapsedNow() < WAIT_S.seconds) {
            get()?.let { return it }
            platform.posix.usleep(NAP_US)
        }
        error("background read never landed")
    }

    @Test fun coldReadReturnsNullThenTheValueAndReadsOnceWhileFresh() {
        val reads = AtomicInt(0)
        val bg = Background<String>("bg-test")
        assertNull(bg.get(1.hours) { "v${reads.incrementAndGet()}" })
        assertEquals("v1", await { bg.get(1.hours) { "v${reads.incrementAndGet()}" } })
        repeat(SPINS) { bg.get(1.hours) { "v${reads.incrementAndGet()}" } }
        assertEquals(1, reads.value)
    }

    @Test fun staleValueIsServedWhileTheRefreshRuns() {
        val bg = Background<String>("bg-stale")
        bg.get(0.milliseconds) { "old" }
        assertEquals("old", await { bg.get(1.hours) { "old" } })
        assertEquals("old", bg.get(0.milliseconds) { "new" })
        assertEquals("new", await { bg.get(1.hours) { "x" }?.takeIf { it == "new" } })
    }

    @Test fun hostsPageProbesOffTheTick() {
        HostsPage.forget()
        val c = Config()
        assertEquals(HostsPage.PROBING, HostsPage.text(c, probe = { "claude  cli\n" }, junk = { null }))
        val page = await { HostsPage.text(c, probe = { "claude  cli\n" }, junk = { null }).takeIf { it != HostsPage.PROBING } }
        assertEquals("claude  cli\n\njunk\n${HostsPage.JUNK_PENDING}", page)
    }
}
