// Port of src/cli/archive.rs — `rtok archive rewrite --stdin`, the pi `context` carrier (T70.2).
package rtok.cli

import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.plugins.archive.rewriteStdin

val ARCHIVE =
    CommandSpec(
        "archive",
        "Archive live-zone tool results (pi carrier)",
        subcommands =
            listOf(
                CommandSpec(
                    "rewrite",
                    "Rewrite old tool results to archive pointers; message array JSON on stdin, rewritten array on stdout (input bytes echoed when nothing is eligible)",
                    options = listOf(OptSpec.Flag("stdin", "Read the message array from stdin (pi `context` event)")),
                ) { inv ->
                    // The pi carrier must echo its array verbatim when config or store fails (D1).
                    val input = inv.io.readStdin()
                    val out =
                        runCatching {
                            Runtime.open(inv.loadConfig(), "archive-rewrite").use { rewriteStdin(input, Ctx(it)) }
                        }.getOrElse {
                            inv.io.eprintln("rtok: archive rewrite skipped: ${it.message}")
                            input
                        }
                    inv.io.writeBytes(out)
                },
            ),
    )
