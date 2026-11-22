package rtok.mcp

import rtok.config.Mcp as McpConfig

/**
 * `[mcp] restart_attempts` / `restart_interval_ms` for `rtok mcp -- <server>`. The demon's mcp service keeps the
 * unbounded `[demon]` backoff. Only crashes in a row count: a server that answered a client request since earns a
 * fresh set of attempts, so a rare crash never uses the limit up. Coming up is not enough: a server that dies
 * right after every handshake would be restarted forever.
 */
class Restarts(
    val attempts: Int,
    val intervalMs: Long,
) {
    /** Crashes since the server last answered the client. */
    var failures = 0
        private set

    /** Counts one crash; true while another restart is allowed. */
    fun crashed(): Boolean = ++failures <= attempts

    fun answered() {
        failures = 0
    }

    companion object {
        fun of(mcp: McpConfig) = Restarts(mcp.restartAttempts.coerceAtMost(Int.MAX_VALUE.toUInt()).toInt(), mcp.restartIntervalMs.coerceAtMost(Long.MAX_VALUE.toULong()).toLong())
    }
}
