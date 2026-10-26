package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import rtok.sys.createDirAll
import rtok.sys.readToString
import rtok.sys.writeString

/** A config whose Windsurf app is a folder in [home] and whose MCP file lives there too. */
private fun windsurfHome(home: String): String {
    createDirAll("$home/Windsurf.app")
    writeString(
        "$home/config.toml",
        "[setup]\nbackup = false\n[setup.paths]\nwindsurf = \"$home/Windsurf.app\"\n[setup.windsurf]\nconfig_path = \"$home/mcp_config.json\"\n",
    )
    return "$home/mcp_config.json"
}

class AgentsCliSpec :
    FunSpec({
        tags(Cli)
        test("install, info and uninstall write and read Windsurf's MCP entry") {
            withHome("agents-install") { home ->
                val file = windsurfHome(home)
                val dry = cli("agents", "install", "windsurf", "--dry-run")
                dry.code shouldBe 0
                dry.out shouldContain "Desktop: Windsurf — dry run, nothing written"
                val ran = cli("agents", "install", "windsurf")
                ran.code shouldBe 0
                ran.out shouldContain "Desktop: Windsurf\n"
                readToString(file) shouldContain "\"rtok\""
                cli("agents", "install", "windsurf").out shouldContain "Desktop: Windsurf — already installed"
                val info = cli("agents", "info", "windsurf")
                info.out shouldContain "  app     $home/Windsurf.app"
                info.out shouldContain "✓ mcp"
                val json = cli("agents", "info", "windsurf", "--json")
                json.code shouldBe 0
                json.out shouldContain "\"host\": \"windsurf\""
                json.out shouldContain "\"present\": true"
                json.out shouldContain "\"entry\": \"present\""
                cli("agents", "list", "--json").out shouldContain "\"kind\": \"desktop\""
                cli("agents", "update").out shouldContain "Desktop: Windsurf — already current"
                cli("agents", "remove", "windsurf").code shouldBe 0
                readToString(file) shouldNotContain "\"rtok\""
            }
        }
        test("unknown hosts, --stat without --dry-run and a bad --source are refused") {
            withHome("agents-refuse") { home ->
                windsurfHome(home)
                cli("agents", "install", "nope").also {
                    it.code shouldBe 1
                    it.err shouldContain "unknown host: nope"
                }
                cli("agents", "install", "windsurf", "--stat").err shouldContain "--stat requires --dry-run"
                cli("agents", "update", "--source", "ftp").err shouldContain "--source: expected one of github, local, marketplace"
                cli("agents", "info", "nope").err shouldContain "unknown host: nope"
            }
        }
        test("list prints a block per host app") {
            withHome("agents-list") { home ->
                windsurfHome(home)
                val out = cli("agents", "list").out
                out shouldContain "Desktop: Windsurf\n"
                out shouldContain "CLI: Codex"
                out shouldContain "  plugins\n"
            }
        }
        test("outdated and update --check report no plugins; an unknown host is refused") {
            withHome("agents-outdated") { home ->
                writeString("$home/config.toml", "[setup]\nbackup = false\n")
                cli("agents", "outdated").also {
                    it.code shouldBe 0
                    it.out shouldBe "no rtok plugins installed\n"
                }
                cli("agents", "update", "--check").out shouldBe "no rtok plugins installed\n"
                cli("agents", "outdated", "--json", "--exit-code").also {
                    it.code shouldBe 0
                    it.out shouldContain "\"outdated\":[],\"installed\":0}"
                }
                cli("agents", "outdated", "nope").err shouldContain "unknown host: nope"
            }
        }
    })
