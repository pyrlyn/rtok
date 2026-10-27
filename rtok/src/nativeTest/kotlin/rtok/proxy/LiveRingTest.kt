@file:Suppress("MagicNumber")

package rtok.proxy

import rtok.config.Config
import kotlin.test.*

/** Port of src/proxy/live.rs tests; a private ring, so no test lock is needed. */
class LiveRingTest {
    @Test fun ringKeepsNewestFirstAndCaps() {
        val ring = LiveRing()
        val cap = LiveRing.LIVE_CAP
        for (i in 0 until cap + 5) ring.push(LiveCall(i.toLong(), "POST", "/v1/$i", null, null, 200, 1, 2, 1.0))
        val s = ring.snapshot()
        assertEquals(cap, s.size)
        assertEquals((cap + 4).toLong(), s[0].ts)
        assertEquals(5L, s.last().ts)
    }

    @Test fun alertsOnlyWhenAKillSwitchIsOff() {
        val cfg = Config()
        assertTrue(Live.alerts(cfg).isEmpty())
        assertEquals(1, Live.alerts(cfg.copy(proxy = cfg.proxy.copy(enabled = false))).size)
        assertTrue(Live.alerts(cfg.copy(core = cfg.core.copy(enabled = false)))[0].contains("[core] enabled"))
    }
}
