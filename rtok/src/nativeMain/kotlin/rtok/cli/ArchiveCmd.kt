// Port of src/cli/archive.rs — `rtok archive rewrite --stdin`, the pi `context` carrier (T70.2).
package rtok.cli

import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.plugins.archive.rewriteStdin

val ARCHIVE = CommandSpec(
    "archive", "Archive live-zone tool results (pi carrier)",
    subcommands = listOf(
        CommandSpec(
            "rewrite",
            "Rewrite old tool results to archive pointers; message array JSON on stdin, rewritten array on stdout (input bytes echoed when nothing is eligible)",
            options = listOf(OptSpec.Flag("stdin", "Read the message array from stdin (pi `context` event)")),
        ) { inv ->
            Runtime.open(inv.loadConfig(), "archive-rewrite").use { rt ->
                inv.io.writeBytes(rewriteStdin(inv.io.readStdin(), Ctx(rt)))
            }
        },
    ),
)
