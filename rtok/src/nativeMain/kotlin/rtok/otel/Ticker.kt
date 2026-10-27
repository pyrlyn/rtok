// Port of src/otel/export.rs `spawn_ticker`: long-lived surfaces (`rtok mcp`, `rtok mcp --http`, later the proxy)
// flush every `[otel] flush_secs` on their own worker, with their own store connection.
package rtok.otel

import rtok.config.Config
import rtok.plugin.Runtime
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

object Ticker {
    /**
     * Returns at once. Without an endpoint nothing starts (no store is opened). Otherwise a worker opens its own
     * runtime and flushes after each full period; it has no stop handle and dies with the process. Blocking
     * SQLite and `flock` stay off the caller's threads.
     */
    @OptIn(ObsoleteWorkersApi::class)
    fun spawn(cfg: Config): Boolean {
        if (endpoint(cfg) == null) return false
        return runCatching {
            Worker.start(name = "rtok-otel").execute(TransferMode.SAFE, { cfg }) { c -> Ticker.loop(c) }
        }.isSuccess
    }

    internal fun loop(cfg: Config) {
        val cx = runCatching { Runtime.open(cfg, "otel") }.getOrNull() ?: return
        val period = maxOf(cfg.otel.flushSecs, 1u)
        while (true) {
            platform.posix.sleep(period)
            runCatching { Export.flush(cx) }
        }
    }
}
