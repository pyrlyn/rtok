// Port of src/cli/mod.rs (subset). Each subcommand lands in its own file as Phase 6 ports it.
package rtok.cli

import rtok.VERSION

/** The parser in use; Clikt unless replaced. */
var cliParser: CliParser = rtok.cli.clikt.CliktParser

/** `--config PATH`, global like clap's `#[arg(long, global = true)]`. */
val CONFIG_OPT = OptSpec.Value("config", "Config file (else `<home>/config.toml`)", "PATH", global = true)

val RTOK =
    CommandSpec(
        name = "rtok",
        help = "Token reduction for coding agents (rtok $VERSION)",
        options = listOf(CONFIG_OPT),
        subcommands = listOf(PLUGINS, CONFIG, EXPAND, ARCHIVE, MEMORY, RUN, FILTER, HOOK, GRAPH, DOCS, MCP, AGENTS, WORKTREE, TASK_CMD, BATCH, PROXY, WEB, TUI, DASHBOARD, DOCTOR, STATS, OTEL, REPORT, COMPLETIONS, MAN, DEMON, BENCH),
    )

object StdIo : CliIo {
    override fun out(text: String) {
        print(text)
    }

    override fun err(text: String) = rtok.sys.eprint(text)

    override fun readStdin() = rtok.sys.readFd(0)

    override fun writeBytes(bytes: ByteArray) = rtok.sys.writeFd(1, bytes)

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    override val tty: Boolean get() = platform.posix.isatty(1) == 1

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    override fun flush() {
        platform.posix.fflush(platform.posix.stdout)
    }
}

/** Runs the CLI; the process exit code. */
fun run(
    args: Array<String>,
    io: CliIo = StdIo,
): Int = cliParser.run(RTOK, args, io)
