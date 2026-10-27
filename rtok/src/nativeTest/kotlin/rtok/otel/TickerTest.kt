@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.otel

import kotlinx.cinterop.toKString
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.store.lastLog
import rtok.support.tmpDir
import rtok.sys.exists
import rtok.sys.removeDirAll
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private const val SETTLE_US = 100_000u

/** export.rs ticker tests. */
class TickerTest {
    private fun config(
        dir: String,
        endpoint: String,
        flushSecs: UInt,
    ): Config {
        val c = Config.loadFrom(dir)
        return c
            .copy(
                core = c.core.copy(dbPath = "$dir/rtok.db", archiveDir = "$dir/archive"),
                otel = c.otel.copy(endpoint = endpoint, flushSecs = flushSecs),
            ).also { it.home = dir }
    }

    private fun envEndpointSet() =
        !platform.posix
            .getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
            ?.toKString()
            .isNullOrEmpty()

    /** The worker has no stop handle; what callers rely on: spawn returns at once and the first flush waits a period. */
    @Test fun tickerReturnsAtOnceAndWaitsAPeriodBeforeFlushing() {
        val dir = tmpDir("otel-tick")
        try {
            val cfg = config(dir, "http://127.0.0.1:1", 3600u)
            Runtime.open(cfg, "otel-tick").use { cx ->
                cx.log("info", "test", "seed", "pending")
                val t = TimeSource.Monotonic.markNow()
                assertTrue(Ticker.spawn(cfg))
                assertTrue(t.elapsedNow() < 500.milliseconds, "${t.elapsedNow()}")
                platform.posix.usleep(SETTLE_US)
                assertNull(cx.store.lastLog("otel"), "no flush before the first period")
            }
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun tickerWithoutAnEndpointSpawnsNothing() {
        if (envEndpointSet()) return
        val dir = tmpDir("otel-tick-off")
        try {
            val cfg = config(dir, "", 1u)
            assertFalse(Ticker.spawn(cfg))
            platform.posix.usleep(SETTLE_US)
            assertFalse(exists(cfg.core.dbPath), "no worker opened the store")
        } finally {
            removeDirAll(dir)
        }
    }
}
