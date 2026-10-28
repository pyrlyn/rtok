// Port of src/cli/logs.rs — `rtok logs [export|watch] [--lines N] [--json]`: rtok's own log, newest first.
package rtok.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import rtok.Tspin
import rtok.config.Config
import rtok.logNumbered
import rtok.logScreen
import rtok.logTail
import rtok.logsWatch
import rtok.measure.stats.SerdeJson

private val LINES = OptSpec.Value("lines", "Override `[log] lines`", "LINES", global = true)

private fun Invocation.lines(): Int? = number("lines")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()

private fun Invocation.logsConfig(): Config = Config.loadWith(configFile, null)

/** Prints [rows], or `no logs yet` when nothing was logged. */
private fun Invocation.rows(rows: List<String>) {
    if (rows.isEmpty()) io.println("no logs yet") else rows.forEach(io::println)
}

val LOGS =
    CommandSpec(
        "logs",
        "rtok's own log (`rtok logs` prints, `rtok logs export` strips numbering and colour)",
        options = listOf(LINES, OptSpec.Flag("json", "JSON instead of the table")),
        runsBare = true,
        subcommands =
            listOf(
                CommandSpec("export", "Same selection, no numbering, no colour — for `rtok logs export > my.log`") { inv ->
                    val plain = logTail(inv.logsConfig(), inv.lines())
                    if (inv.flag("json")) inv.io.println(SerdeJson.pretty(JsonArray(plain.map(::JsonPrimitive)))) else inv.rows(plain)
                },
                CommandSpec("watch", "Print the last lines, then follow: new lines arrive above the old, newest first") { inv ->
                    val cfg = inv.logsConfig()
                    // Through tailspin the stream is a pipe from the loop's point of view; `tspin --print` colours
                    // each row as it arrives.
                    val viewer = Tspin.start(cfg, inv.io.tty)
                    if (viewer != null) {
                        try {
                            logsWatch(cfg, inv.lines(), tty = false, write = viewer::write)
                        } finally {
                            viewer.finish()
                        }
                    } else {
                        logsWatch(cfg, inv.lines(), inv.io.tty) { text -> runCatching { inv.io.out(text) }.isSuccess.also { inv.io.flush() } }
                    }
                },
            ),
    ) { inv ->
        val cfg = inv.logsConfig()
        val plain = logTail(cfg, inv.lines())
        if (inv.flag("json")) return@CommandSpec inv.io.println(SerdeJson.pretty(JsonArray(plain.map(::JsonPrimitive))))
        if (plain.isNotEmpty()) Tspin.start(cfg, inv.io.tty)?.let { viewer -> return@CommandSpec viewer.print(logNumbered(plain)) }
        inv.rows(logScreen(plain))
    }
