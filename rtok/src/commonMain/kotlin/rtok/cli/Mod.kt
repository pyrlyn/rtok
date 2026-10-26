// Port of src/cli/mod.rs (subset). Each subcommand lands in its own file as phases complete.
// The CLI is described with rtok's own `CommandSpec`; the parsing library is an adapter
// (`clikt/CliktAdapter.kt`) behind `CliParser`, so it can be replaced without touching commands.
package rtok.cli

/** One (sub)command: its name, help, children, and what it does once parsed. */
class CommandSpec(
    val name: String,
    val help: String = "",
    val subcommands: List<CommandSpec> = emptyList(),
    val action: () -> Unit = {},
)

/** Parses argv against a [CommandSpec] tree and runs the chosen command (help, errors and exit codes included). */
fun interface CliParser {
    fun run(spec: CommandSpec, args: Array<String>)
}

/** The parser in use; Clikt unless replaced. */
var cliParser: CliParser = rtok.cli.clikt.CliktParser

val RTOK = CommandSpec(name = "rtok")

fun run(args: Array<String>) = cliParser.run(RTOK, args)
