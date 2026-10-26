// Adapter: Clikt (third-party) behind rtok's `CliParser`. Only this package imports Clikt.
package rtok.cli.clikt

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import com.github.ajalt.clikt.parameters.options.versionOption
import rtok.VERSION
import rtok.cli.*

/** Collected while Clikt walks the command chain; the last command run is the one chosen. */
private class Parsed {
    val flags = HashSet<String>()
    val values = HashMap<String, String>()
    val args = HashMap<String, String>()
    val lists = HashMap<String, List<String>>()
    var action: ((Invocation) -> Unit)? = null
}

private class SpecCommand(
    private val spec: CommandSpec,
    private val parsed: Parsed,
    inherited: List<OptSpec>,
) : CliktCommand(name = spec.name) {
    override fun help(context: Context) = spec.help

    override val invokeWithoutSubcommand = spec.subcommands.isEmpty() || spec.runsBare
    private val opts =
        (inherited + spec.options).map { o ->
            o to
                when (o) {
                    is OptSpec.Flag -> {
                        option("--${o.name}", help = o.help).flag().also { registerOption(it) }
                    }

                    is OptSpec.Value -> {
                        val names = listOfNotNull("--${o.name}", o.short?.let { "-$it" }).toTypedArray()
                        val base = option(*names, help = o.help, metavar = o.metavar)
                        when {
                            o.repeat -> base.multiple()
                            o.optional -> base.optionalValue("")
                            else -> base
                        }.also { registerOption(it) }
                    }
                }
        }
    private val positionals =
        spec.args.filter { !it.variadic }.map { a ->
            a to (if (a.required) argument(a.name, help = a.help) else argument(a.name, help = a.help).optional()).also { registerArgument(it) }
        }
    private val rest = spec.args.filter { it.variadic }.map { a -> a to argument(a.name, help = a.help).multiple(required = a.required).also { registerArgument(it) } }

    override fun run() {
        for ((o, opt) in opts) {
            when (val v = opt.value) {
                true -> {
                    parsed.flags += o.name
                }

                is String -> {
                    parsed.values[o.name] = v
                }

                is List<*> -> {
                    parsed.lists["--${o.name}"] = v.map { it as String }
                }

                else -> {}
            }
        }
        for ((a, arg) in positionals) (arg.value as String?)?.let { parsed.args[a.name] = it }
        for ((a, arg) in rest) parsed.lists[a.name] = arg.value
        // A subcommand runs after its parent and replaces a bare action.
        if (spec.subcommands.isEmpty() || spec.runsBare) parsed.action = spec.action
    }
}

private fun build(
    spec: CommandSpec,
    parsed: Parsed,
    inherited: List<OptSpec>,
): CliktCommand {
    val passDown = inherited + spec.options.filter { it.global }
    return SpecCommand(spec, parsed, inherited).subcommands(spec.subcommands.map { build(it, parsed, passDown) })
}

object CliktParser : CliParser {
    override fun run(
        spec: CommandSpec,
        args: Array<String>,
        io: CliIo,
    ): Int {
        val parsed = Parsed()
        val cmd = build(spec, parsed, emptyList()).versionOption(VERSION, names = setOf("--version", "-V"), message = { v -> "${spec.name} $v" })
        try {
            cmd.parse(args)
        } catch (e: CliktError) {
            val text = cmd.getFormattedHelp(e) ?: ""
            return when {
                e is PrintHelpMessage || e is PrintMessage || e is PrintCompletionMessage -> {
                    if (text.isNotEmpty()) io.println(text)
                    ExitCode.OK
                }

                e is ProgramResult -> {
                    e.statusCode
                }

                else -> {
                    io.eprintln(text)
                    ExitCode.USAGE
                }
            }
        }
        val action = parsed.action ?: return ExitCode.OK
        return runAction(io) { action(Invocation(parsed.flags, parsed.values, parsed.args, io, parsed.lists)) }
    }
}
