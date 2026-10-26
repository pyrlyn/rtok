// Adapter: Clikt (third-party) behind rtok's `CliParser`. Only this package imports Clikt.
package rtok.cli.clikt

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import rtok.cli.CliParser
import rtok.cli.CommandSpec

private class SpecCommand(private val spec: CommandSpec) : CliktCommand(name = spec.name) {
    override fun help(context: Context) = spec.help
    override fun run() = spec.action()
}

private fun build(spec: CommandSpec): CliktCommand = SpecCommand(spec).subcommands(spec.subcommands.map(::build))

object CliktParser : CliParser {
    override fun run(spec: CommandSpec, args: Array<String>) = build(spec).main(args)
}
