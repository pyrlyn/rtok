package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import rtok.sys.*

private fun pluginRow(out: String, id: String) = out.lines().first { it.startsWith("$id ") }

class CliE2eSpec : FunSpec({
    context("binary basics") {
        test("rtok run cat <file> prints the file (release build once failed: Unexpected receiver type: kotlin.Char)") {
            Sandbox("run-cat").use { s ->
                writeString("${s.root}/c.txt", "a\nb\n")
                s.rtok("run", "cat", "${s.root}/c.txt").also { it.err shouldBe ""; it.code shouldBe 0; it.out shouldBe "a\nb\n" }
            }
        }
        test("--version prints the crate version and exits 0") {
            Sandbox("version").use { s -> s.rtok("--version").also { it.code shouldBe 0; it.out shouldStartWith "rtok 0."; it.err shouldBe "" } }
        }
        test("no subcommand prints usage and exits 0; an unknown one exits 2") {
            Sandbox("usage").use { s ->
                s.rtok("--help").also { it.code shouldBe 0; it.out shouldContain "plugins" }
                s.rtok("frobnicate").also { it.code shouldBe 2; it.out shouldBe ""; it.err shouldContain "frobnicate" }
            }
        }
        test("an unknown option and a missing argument are usage errors") {
            Sandbox("badopt").use { s ->
                s.rtok("plugins", "--nope").also { it.code shouldBe 2; it.err shouldContain "--nope" }
                s.rtok("config", "set", "only-key").also { it.code shouldBe 2; it.err shouldContain "value" }
            }
        }
    }

    context("config files on disk") {
        test("the first command creates the reference config under RTOK_HOME") {
            Sandbox("init").use { s ->
                s.rtok("plugins").code shouldBe 0
                exists("${s.home}/config.toml") shouldBe true
                s.rtok("config", "path").out shouldBe "${s.home}/config.toml\n"
            }
        }
        test("config set persists, plugins reads it back, and the file stays valid") {
            Sandbox("set").use { s ->
                s.rtok("config", "set", "plugins.measure.enabled", "false").code shouldBe 0
                pluginRow(s.rtok("plugins").out, "measure") shouldBe "measure   off       cli,proxy"
                s.rtok("config", "validate").also {
                    it.code shouldBe 0; it.err shouldBe ""
                    it.out shouldContain "plugins.measure.enabled = false (default true)"
                    it.out.lines().filter(String::isNotEmpty).last() shouldBe "ok ${s.home}/config.toml"
                }
            }
        }
        test("--config points every command at another file") {
            Sandbox("flag").use { s ->
                writeString("${s.root}/alt.toml", "[plugins.proxy]\nenabled = false\n")
                pluginRow(s.rtok("--config", "${s.root}/alt.toml", "plugins").out, "proxy") shouldBe "proxy     off       proxy"
                pluginRow(s.rtok("plugins").out, "proxy") shouldBe "proxy     on        proxy"
            }
        }
        test("invalid input: a bad value, an unknown key, a broken file") {
            Sandbox("invalid").use { s ->
                s.rtok("config", "set", "plugins.measure.enabled", "maybe").also { it.code shouldBe 1; it.err shouldStartWith "Error: " }
                s.rtok("config", "set", "core.nope", "1").also { it.code shouldBe 1; it.err shouldContain "unknown key" }
                writeString("${s.home}/config.toml", "[core\n")
                s.rtok("config", "validate").also { it.code shouldBe 1; it.err shouldNotContain "Exception" }
                s.rtok("plugins").also { it.code shouldBe 1; it.err shouldStartWith "Error: " }
            }
        }
        test("config validate writes each problem to the log file") {
            Sandbox("log").use { s ->
                s.rtok("plugins")
                writeString("${s.root}/bad.toml", "[core]\nbogus = 1\n")
                s.rtok("config", "validate", "${s.root}/bad.toml").code shouldBe 1
                readToString("${s.home}/logs/rtok.log") shouldContain "bogus"
            }
        }
    }

    context("project root discovery with real git repositories") {
        test("a project .rtok.toml applies from any subdirectory of the checkout") {
            Sandbox("project").use { s ->
                val repo = s.repo("${s.root}/app")
                writeString("$repo/.rtok.toml", "[plugins.measure]\nenabled = false\n")
                createDirAll("$repo/src/deep")
                pluginRow(s.rtok("plugins", cwd = "$repo/src/deep").out, "measure") shouldBe "measure   off       cli,proxy"
                pluginRow(s.rtok("plugins", cwd = s.root).out, "measure") shouldBe "measure   on        cli,proxy"
            }
        }
        test("a linked worktree reads its own checkout's .rtok.toml") {
            Sandbox("worktree").use { s ->
                val repo = s.repo("${s.root}/main")
                s.git(repo, "worktree", "add", "-q", "${s.root}/wt", "-b", "feature")
                writeString("${s.root}/wt/.rtok.toml", "[plugins.proxy]\nenabled = false\n")
                pluginRow(s.rtok("plugins", cwd = "${s.root}/wt").out, "proxy") shouldBe "proxy     off       proxy"
                pluginRow(s.rtok("plugins", cwd = repo).out, "proxy") shouldBe "proxy     on        proxy"
            }
        }
        test("a submodule is its own project") {
            Sandbox("submodule").use { s ->
                val dep = s.repo("${s.root}/dep-src")
                val repo = s.repo("${s.root}/super")
                s.git(repo, "submodule", "add", "-q", dep, "vendor/dep")
                writeString("$repo/.rtok.toml", "[plugins.measure]\nenabled = false\n")
                pluginRow(s.rtok("plugins", cwd = "$repo/vendor/dep").out, "measure") shouldBe "measure   on        cli,proxy"
                pluginRow(s.rtok("plugins", cwd = repo).out, "measure") shouldBe "measure   off       cli,proxy"
            }
        }
    }
})
