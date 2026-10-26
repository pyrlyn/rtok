// rtok's own command model. Commands are declared here and in one file per subcommand; the parsing
// library is an adapter behind `CliParser` (see clikt/CliktAdapter.kt), so commands and their tests
// never see it.
package rtok.cli

/** A `--name` option: a boolean flag, or one that takes a value. [global] options are inherited by subcommands. */
sealed class OptSpec(val name: String, val help: String, val global: Boolean) {
    class Flag(name: String, help: String = "", global: Boolean = false) : OptSpec(name, help, global)
    class Value(name: String, help: String = "", val metavar: String = "VALUE", global: Boolean = false) : OptSpec(name, help, global)
}

/** A positional argument. */
class ArgSpec(val name: String, val help: String = "", val required: Boolean = true)

/** What the parser hands an action: options by name (without `--`), positionals by name. */
class Invocation(private val flags: Set<String>, private val values: Map<String, String>, private val args: Map<String, String>, val io: CliIo) {
    fun flag(name: String) = name in flags
    fun value(name: String): String? = values[name]
    fun arg(name: String): String? = args[name]
    fun requireArg(name: String): String = args[name] ?: throw CliFailure("missing argument <$name>")
    /** `--since <n>|<n>d|<n>h` in seconds, or null when absent; a bad value fails like Rust (`Error: bad --since x`, exit 1). */
    fun since(name: String = "since"): Long? = value(name)?.let { raw ->
        try { rtok.config.parseSinceFrom(raw, "--$name") } catch (e: rtok.config.ConfigException) { throw CliFailure(e.message ?: "bad --$name $raw") }
    }
    /** The global `--config PATH`. */
    val configFile: String? get() = value("config")
    /** The merged config for `--config` (or the default home). */
    fun loadConfig(): rtok.config.Config = rtok.config.Config.loadWith(configFile, null)
}

/** One (sub)command: name, help, options, positionals, children, and what it does once parsed. */
class CommandSpec(
    val name: String,
    val help: String = "",
    val options: List<OptSpec> = emptyList(),
    val args: List<ArgSpec> = emptyList(),
    val subcommands: List<CommandSpec> = emptyList(),
    val action: (Invocation) -> Unit = {},
)

/** Where a command writes. The process uses stdout/stderr; tests capture. */
interface CliIo {
    fun out(text: String)
    fun err(text: String)
    fun println(text: String = "") = out(text + "\n")
    fun eprintln(text: String = "") = err(text + "\n")
    /** All of stdin (carriers such as `archive rewrite --stdin`). */
    fun readStdin(): ByteArray = ByteArray(0)
    /** Raw bytes to stdout, unchanged (an echoed input must stay byte-identical). */
    fun writeBytes(bytes: ByteArray) = out(bytes.decodeToString())
}

/** A command failed: printed as `Error: <message>` with exit [code] (Rust: anyhow from main → 1). */
open class CliFailure(message: String, val code: Int = 1) : Exception(message)

/** Exit with [code] after the command already printed what it had to say. */
class CliExit(val code: Int) : Exception("exit $code")

/** Exit codes, as the Rust binary (clap + anyhow) uses them. */
object ExitCode { const val OK = 0; const val FAILURE = 1; const val USAGE = 2 }

/** Parses argv against a [CommandSpec] tree, runs the chosen action, and returns the exit code. */
fun interface CliParser {
    fun run(spec: CommandSpec, args: Array<String>, io: CliIo): Int
}

/** Runs [action] and maps its failure to an exit code; shared by every parser adapter. */
fun runAction(io: CliIo, action: () -> Unit): Int = try { action(); ExitCode.OK } catch (e: CliExit) { e.code } catch (e: CliFailure) {
    io.eprintln("Error: ${e.message}"); e.code
} catch (e: Exception) { io.eprintln("Error: ${e.message ?: e::class.simpleName}"); ExitCode.FAILURE }

/** The shared `--since` option of stats, report and discover. */
fun sinceOpt(help: String = "Window: <n>, <n>d (days) or <n>h (hours)") = OptSpec.Value("since", help, "SINCE")
