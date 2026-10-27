package rtok.demon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** One row of `rtok demon status` (T15.11): liveness asked of the lock, never copied out of the state file. */
@Serializable
data class Row(
    val service: String,
    val running: Boolean,
    /** The proxy's configured `bind:port`, running or not; null for a surface that listens nowhere. */
    val endpoint: String?,
    val supervisor: Int?,
    val child: Int?,
    @SerialName("uptime_secs") val uptimeSecs: Long?,
    val restarts: Int?,
    val log: String,
)

/** One row per named service, or every service when none is named, so a stopped one reads as stopped. */
fun Store.rows(
    named: List<Service>,
    proxyEndpoint: String,
    now: Long = Clock.System.now().epochSeconds,
): List<Row> =
    named.ifEmpty { Service.entries }.map { service ->
        val st = read(service)
        val live = st?.takeIf { held(service) }
        Row(
            service = service.id,
            running = live != null,
            endpoint = proxyEndpoint.takeIf { service == Service.PROXY },
            supervisor = live?.supervisor,
            child = live?.child,
            uptimeSecs = live?.let { maxOf(0L, now - it.since) },
            restarts = live?.restarts,
            log = file(service, "log"),
        )
    }

private val WIDTHS = listOf(9, 9, 16, 10, 8, 8, 8)
private const val SEP = "  "

/** The table: a header plus one row per service, fixed column floors, the log path last. */
fun table(rows: List<Row>): String {
    fun line(cells: List<String>) = cells.zip(WIDTHS).joinToString(SEP) { (c, w) -> c.padEnd(w) } + SEP

    val dash = "-"
    val out = StringBuilder(line(listOf("service", "state", "endpoint", "supervisor", "child", "uptime", "restarts")) + "log\n")
    for (r in rows) {
        val cells =
            listOf(
                r.service,
                if (r.running) "running" else "stopped",
                r.endpoint ?: dash,
                r.supervisor?.toString() ?: dash,
                r.child?.toString() ?: dash,
                r.uptimeSecs?.let { "${it}s" } ?: dash,
                r.restarts?.toString() ?: dash,
            )
        out.append(line(cells)).append(r.log).append('\n')
    }
    return out.toString()
}
