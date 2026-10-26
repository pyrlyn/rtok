// Port of src/cli/expand.rs — `rtok expand <id> [--lines a-b] [--grep RE] [--context N]`.
package rtok.cli

import rtok.Expand
import rtok.ExpandException

val EXPAND =
    CommandSpec(
        "expand",
        "Print an archived payload by id (the id from an \"expand <id>\" trailer)",
        options =
            listOf(
                OptSpec.Value("lines", "Inclusive 1-based range `a-b`", "LINES"),
                OptSpec.Value("grep", "Regex filter (literal when it does not compile); hits print as `N:line`", "GREP"),
                OptSpec.Value("context", "With `--grep`: N lines around each hit, overlapping windows merged with `--`", "CONTEXT"),
            ),
        args = listOf(ArgSpec("id")),
    ) { inv ->
        val context = inv.value("context")?.let { it.toUIntOrNull() ?: throw CliFailure("invalid value '$it' for '--context <CONTEXT>': invalid digit found in string", ExitCode.USAGE) }
        try {
            inv.io.writeBytes(Expand.run(inv.loadConfig(), inv.requireArg("id"), inv.value("lines"), inv.value("grep"), context?.toInt() ?: 0))
        } catch (e: ExpandException) {
            throw CliFailure(e.message ?: "expand failed")
        }
    }
