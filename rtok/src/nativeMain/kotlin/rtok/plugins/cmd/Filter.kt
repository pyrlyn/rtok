// Port of src/plugins/cmd/filter.rs: `rtok filter <cmd>` filters text piped on stdin as if <cmd> printed it.
package rtok.plugins.cmd

import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Measurement
import rtok.shortId
import rtok.store.hexSha256
import rtok.sys.rustLines

object Filter {
    /** Filter without a store: the user config's rules when it loads, the builtins otherwise. */
    fun run(
        cmd: String,
        input: String,
    ): String {
        val settings = runCatching { Settings.fromConfig(Config.loadWith(null, null)) }.getOrElse { Settings.builtin() }
        return compressOnly(settings, cmd, input)
    }

    /** Filter, archive the input when the output points at it, and record one `cmd` row. */
    fun runWithStore(
        cfg: Config,
        cmd: String,
        input: String,
    ): String {
        val settings = Settings.fromConfig(cfg)
        val argv = words(cmd)
        val cx = runCatching { Runtime.open(cfg, "filter") }.getOrElse { return compressOnly(settings, cmd, input) }
        cx.use {
            val bytes = input.encodeToByteArray()
            val id = hexSha256(bytes)
            val (filtered, kind) = Formatters.compress(settings, argv, input, 0, shortId(id))
            val lines = if (input.isEmpty()) 0u else input.rustLines().size.toUInt()
            val named = namesTheId(filtered, shortId(id))
            val c = cfg.plugins.cmd
            val pointer = c.trailer && needsPointer(lines, c.trailerMinLines, bytes, filtered, named)
            if (lines <= c.trailerMinLines && filtered.trimEnd().byteLen >= input.trimEnd().byteLen) return input
            val out =
                buildString {
                    append(filtered)
                    if (pointer) {
                        if (isNotEmpty() && !endsWith('\n')) append('\n')
                        append(trailer(id, lines)).append('\n')
                    }
                }
            if (out.byteLen >= bytes.size) return input
            if ((pointer || named) && runCatching { cx.putArchive(bytes) }.isFailure) return compressOnly(settings, cmd, input)
            val family = Formatters.familyOf(settings, argv, input)
            runCatching {
                cx.record(
                    Measurement(
                        "cmd",
                        kind,
                        bytes.size.toULong(),
                        out.byteLen.toULong(),
                        cx.estimate(input, Class.Code),
                        cx.estimate(out, Class.Code),
                        if (pointer || named) "$family:$id" else null,
                    ),
                )
            }
            return out
        }
    }

    private fun words(cmd: String) = cmd.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }

    private fun compressOnly(
        settings: Settings,
        cmd: String,
        input: String,
    ) = Formatters.compress(settings, words(cmd), input, 0, "").text
}
