@file:Suppress("MagicNumber")

package rtok.mcp

import rtok.config.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestartsTest {
    @Test fun defaultsAreSixAttemptsFiveSecondsApart() {
        val r = Restarts.of(Config().mcp)
        assertEquals(6, r.attempts)
        assertEquals(5000L, r.intervalMs)
    }

    @Test fun onlyCrashesInARowUseTheAttemptsUp() {
        val r = Restarts(2, 0)
        assertTrue(r.crashed())
        assertTrue(r.crashed())
        r.started()
        assertTrue(r.crashed())
        assertTrue(r.crashed())
        assertFalse(r.crashed())
        assertEquals(3, r.failures)
    }

    @Test fun zeroAttemptsNeverRestarts() {
        assertFalse(Restarts(0, 0).crashed())
    }
}
