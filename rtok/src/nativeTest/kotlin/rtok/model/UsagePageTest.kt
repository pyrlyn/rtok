package rtok.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.support.installDb
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val WAIT_S = 10
private const val NAP_US = 1000u

class UsagePageTest {
    @Test fun badZoneIsThePageTextNotAFailure() {
        installDb()
        val dir = "${tempDir()}/rtok-usagepage-${platform.posix.getpid()}"
        val base = Config()
        val c =
            base.copy(
                core = base.core.copy(dbPath = "$dir/rtok.db"),
                agents = base.agents.copy(usage = base.agents.usage.copy(tz = "Nowhere/Atlantis")),
            )
        val page = UsagePage.read(c)
        assertNull(page.report)
        assertTrue(page.text.startsWith("usage did not answer this tick: "), page.text)
        removeDirAll(dir)
    }

    @Test fun firstTickSaysReadingThenTheReadLands() {
        UsagePage.forget()
        val c = Config()
        val landed = UsagePage("2 agents\n", JsonObject(emptyMap()))
        assertEquals(UsagePage.READING, UsagePage.get(c) { landed })
        val start = TimeSource.Monotonic.markNow()
        var got = UsagePage.get(c) { landed }
        while (got == UsagePage.READING && start.elapsedNow() < WAIT_S.seconds) {
            platform.posix.usleep(NAP_US)
            got = UsagePage.get(c) { landed }
        }
        assertEquals("2 agents\n", (got.toJson() as JsonObject)["text"]!!.jsonPrimitive.content)
    }
}
