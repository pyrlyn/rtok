// Port of web/src/pages/text.ts services (parseServices, kvPairs, humanSecs) and Services.tsx serviceCommand.
package rtok.web.ui

private const val HOUR = 3600
private const val MINUTE = 60

/** text.ts `humanSecs`: `5h 5m`, `2m 5s`, `-`. */
fun humanSecs(s: Long?): String {
    if (s == null) return "-"
    val h = s / HOUR
    val m = s % HOUR / MINUTE
    return if (h != 0L) "${h}h ${m}m" else "${m}m ${s % MINUTE}s"
}

private val KV = Regex("""([a-z_]+)=(\S+)""")

/** text.ts `kvPairs`: `key=value` pairs of a line. */
fun kvPairs(line: String): Map<String, String> = KV.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }

private fun dash(v: String?) = v?.takeIf { it != "-" && it.isNotEmpty() }

/** One `rtok demon status` row. */
data class ServiceRow(
    val name: String,
    val running: Boolean,
    val pid: String?,
    val uptimeSecs: Long?,
    val log: String?,
)

/** The `otel` exporter line (text.ts `OtelView`). */
data class OtelView(
    val endpoint: String?,
    val callsMark: Long?,
    val callsPending: Long?,
    val logsMark: Long?,
    val logsPending: Long?,
    val sessionsMark: Long?,
)

/** text.ts `ServicesView`. */
data class ServicesView(
    val services: List<ServiceRow>,
    val otel: OtelView?,
    val lastFlush: String?,
    val other: List<String>,
)

private val SERVICE = Regex("""^(\S+) {2}(running|stopped) {2}(.*)$""")
private val LOG_TAIL = Regex("""log=(.*)$""")

private fun otelOf(l: String): OtelView {
    val k = kvPairs(l)

    fun n(key: String) = dash(k[key])?.toLongOrNull()
    return OtelView(dash(k["endpoint"]), n("calls_mark"), n("calls_pending"), n("logs_mark"), n("logs_pending"), n("sessions_mark"))
}

/** text.ts `parseServices`. */
fun parseServices(text: String): ServicesView {
    val services = ArrayList<ServiceRow>()
    val other = ArrayList<String>()
    var otel: OtelView? = null
    var lastFlush: String? = null
    for (l in lines(text).filter { it.isNotBlank() }) {
        val svc = SERVICE.find(l)
        when {
            svc != null -> {
                val k = kvPairs(svc.groupValues[3])
                services +=
                    ServiceRow(
                        svc.groupValues[1],
                        svc.groupValues[2] == "running",
                        dash(k["pid"]),
                        dash(k["uptime"]?.removeSuffix("s"))?.toLongOrNull(),
                        LOG_TAIL.find(l)?.groupValues?.get(1),
                    )
            }

            l.startsWith("otel ") -> {
                otel = otelOf(l)
            }

            l.startsWith("last flush: ") -> {
                lastFlush = l
            }

            else -> {
                other += l
            }
        }
    }
    return ServicesView(services, otel, lastFlush, other)
}

/** Services.tsx `serviceCommand`: start/stop stays a CLI verdict, so the card names the command. */
fun serviceCommand(s: ServiceRow) = "rtok demon ${if (s.running) "restart" else "start"} ${s.name}"
