@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.collections.shouldContainExactly
import rtok.VERSION
import rtok.sys.*

/** Captures what a command prints. */
class CaptureIo(private val stdin: ByteArray = ByteArray(0)) : CliIo {
    val out = StringBuilder(); val err = StringBuilder()
    override fun out(text: String) { out.append(text) }
    override fun err(text: String) { err.append(text) }
    override fun readStdin() = stdin
}

data class Ran(val code: Int, val out: String, val err: String)

fun cli(vararg args: String, spec: CommandSpec = RTOK, stdin: String = ""): Ran { rtok.support.installDb(); val io = CaptureIo(stdin.encodeToByteArray()); val code = cliParser.run(spec, arrayOf(*args), io); return Ran(code, io.out.toString(), io.err.toString()) }

/** Runs [body] with `RTOK_HOME` pointing at a fresh directory (unset again after). */
fun <T> withHome(tag: String, body: (String) -> T): T {
    val home = "${tempDir()}/rtok-cli-$tag-${pid()}"; removeDirAll(home); createDirAll(home)
    platform.posix.setenv("RTOK_HOME", home, 1)
    try { return body(home) } finally { platform.posix.unsetenv("RTOK_HOME"); removeDirAll(home) }
}

class CliSpec : FunSpec({
    // A toy tree that exercises the parser layer on its own.
    val seen = ArrayList<String>()
    val toy = CommandSpec("rtok", options = listOf(CONFIG_OPT), subcommands = listOf(
        CommandSpec("show", options = listOf(OptSpec.Flag("json"), sinceOpt()), args = listOf(ArgSpec("key"), ArgSpec("extra", required = false))) { inv ->
            seen += listOf("key=${inv.arg("key")}", "extra=${inv.arg("extra")}", "json=${inv.flag("json")}", "since=${inv.since()}", "config=${inv.configFile}")
        },
        CommandSpec("boom") { throw CliFailure("it broke") },
        CommandSpec("crash") { error("unexpected") },
        CommandSpec("quiet-fail") { inv -> inv.io.eprintln("one problem"); throw CliExit(ExitCode.FAILURE) },
    ))
    beforeTest { seen.clear() }

    context("argument parsing") {
        test("flags, options and positionals reach the action") {
            cli("show", "k", "x", "--json", "--since", "7d", spec = toy).code shouldBe 0
            seen shouldContainExactly listOf("key=k", "extra=x", "json=true", "since=604800", "config=null")
        }
        test("an optional positional and an absent flag are null and false") {
            cli("show", "k", spec = toy).code shouldBe 0
            seen shouldContainExactly listOf("key=k", "extra=null", "json=false", "since=null", "config=null")
        }
        test("the global --config is accepted before and after the subcommand") {
            cli("--config", "/a.toml", "show", "k", spec = toy); seen.last() shouldBe "config=/a.toml"
            cli("show", "k", "--config", "/b.toml", spec = toy); seen.last() shouldBe "config=/b.toml"
        }
        test("--since accepts days, bare days and hours") {
            for ((raw, secs) in listOf("2" to 172800L, "2d" to 172800L, "3h" to 10800L, "0" to 0L)) {
                cli("show", "k", "--since", raw, spec = toy).code shouldBe 0; seen[3] shouldBe "since=$secs"; seen.clear()
            }
        }
        test("--since rejects a bad unit, a non-number and an overflow with exit 1") {
            for ((raw, msg) in listOf("2w" to "Error: bad --since unit in 2w", "x" to "Error: bad --since x", "99999999999999999d" to "Error: --since 99999999999999999d is out of range")) {
                val r = cli("show", "k", "--since", raw, spec = toy)
                r.code shouldBe ExitCode.FAILURE; r.err.trim() shouldBe msg; r.out shouldBe ""
            }
        }
    }

    context("errors and exit codes") {
        test("a missing positional is a usage error (2) on stderr") {
            val r = cli("show", spec = toy); r.code shouldBe ExitCode.USAGE; r.err shouldContain "key"; r.out shouldBe ""
        }
        test("an unknown subcommand and an unknown option are usage errors") {
            cli("nope", spec = toy).code shouldBe ExitCode.USAGE
            cli("show", "k", "--nope", spec = toy).also { it.code shouldBe ExitCode.USAGE; it.err shouldContain "--nope" }
        }
        test("a command failure prints `Error: <message>` and exits 1") {
            cli("boom", spec = toy) shouldBe Ran(1, "", "Error: it broke\n")
            cli("crash", spec = toy) shouldBe Ran(1, "", "Error: unexpected\n")
        }
        test("a command that already reported its problems exits with its own code") {
            cli("quiet-fail", spec = toy) shouldBe Ran(1, "", "one problem\n")
        }
        test("--version and --help print to stdout and exit 0") {
            cli("--version") shouldBe Ran(0, "rtok $VERSION\n", "")
            cli("--help").also { it.code shouldBe 0; it.out shouldContain "plugins"; it.out shouldContain "config"; it.err shouldBe "" }
        }
    }

    context("integration with config, log and project root") {
        test("config path follows RTOK_HOME and --config") {
            withHome("path") { home ->
                cli("config", "path") shouldBe Ran(0, "$home/config.toml\n", "")
                cli("--config", "/x/y.toml", "config", "path") shouldBe Ran(0, "/x/y.toml\n", "")
            }
        }
        test("config set writes the user file and plugins reflects it") {
            withHome("set") { home ->
                cli("config", "set", "plugins.measure.enabled", "false").code shouldBe 0
                readToString("$home/config.toml") shouldContain "enabled = false"
                cli("plugins").out.lines().first { it.startsWith("measure") } shouldBe "measure   off       cli,proxy"
            }
        }
        test("config set --dry-run prints the diff and leaves the file alone") {
            withHome("dry") { home ->
                cli("config", "init").code shouldBe 0
                val before = readToString("$home/config.toml")
                cli("config", "set", "--dry-run", "plugins.measure.enabled", "false").also { it.code shouldBe 0; it.out shouldContain "enabled" }
                readToString("$home/config.toml") shouldBe before
            }
        }
        test("config set rejects an unknown key with exit 1 and no write") {
            withHome("badset") { home ->
                cli("config", "init"); val before = readToString("$home/config.toml")
                cli("config", "set", "nope.key", "1").also { it.code shouldBe 1; it.err shouldStartWith "Error: "; it.err shouldContain "unknown key: nope" }
                readToString("$home/config.toml") shouldBe before
            }
        }
        test("config validate passes a fresh file and logs each problem of a bad one") {
            withHome("validate") { home ->
                cli("config", "validate") shouldBe Ran(0, "ok $home/config.toml\n", "")
                writeString("$home/bad.toml", "[core]\nbogus = 1\n")
                val r = cli("config", "validate", "$home/bad.toml")
                r.code shouldBe 1; r.err shouldContain "bogus"
                readToString("$home/logs/rtok.log") shouldContain "config"
            }
        }
        test("config validate of a typed path that does not exist fails") {
            withHome("missing") { home -> cli("config", "validate", "$home/none.toml").code shouldBe 1 }
        }
        test("config init refuses to overwrite without --force") {
            withHome("init") { _ ->
                cli("config", "init").code shouldBe 0
                cli("config", "init").also { it.code shouldBe 1; it.err shouldContain "--force" }
                cli("config", "init", "--force").code shouldBe 0
            }
        }
    }
})
