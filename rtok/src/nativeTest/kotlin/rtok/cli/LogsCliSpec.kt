// `rtok logs [export] [--lines N] [--json]` and `rtok info` over a seeded log (tests/logs.rs, cli/info.rs).
package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import rtok.sys.createDirAll
import rtok.sys.join
import rtok.sys.writeString

class LogsCliSpec :
    FunSpec({
        tags(Cli)

        fun seed(home: String) {
            createDirAll(join(home, "logs"))
            writeString(join(home, "logs/rtok.log"), "2026-09-09 15:04:05 error t/n: boom\na\nb\nc\n")
            writeString(join(home, "logs/rtok.log.1"), "old\n")
        }

        test("logs numbers the tail newest first across the rotation") {
            withHome("logs-screen") { home ->
                seed(home)
                cli("logs").out shouldBe "1 c\n2 b\n3 a\n4 2026-09-09 15:04:05 error t/n: boom\n5 old\n"
                cli("logs", "--lines", "2").out shouldBe "1 c\n2 b\n"
            }
        }
        test("logs export drops the numbering; --lines is global") {
            withHome("logs-export") { home ->
                seed(home)
                cli("logs", "export", "--lines", "2").out shouldBe "c\nb\n"
                cli("logs", "--lines", "1", "export").out shouldBe "c\n"
            }
        }
        test("logs --json is the plain lines") {
            withHome("logs-json") { home ->
                seed(home)
                cli("logs", "--json", "--lines", "2").out shouldBe "[\n  \"c\",\n  \"b\"\n]\n"
            }
        }
        test("an empty log says so, and a bad --lines is a usage error") {
            withHome("logs-empty") {
                cli("logs").out shouldBe "no logs yet\n"
                cli("logs", "export").out shouldBe "no logs yet\n"
                cli("logs", "--lines", "x").code shouldBe ExitCode.USAGE
            }
        }
        test("info counts the log's lines and errors") {
            withHome("info") { home ->
                seed(home)
                val text = cli("info").out
                text shouldContain "rtok "
                text shouldContain "5 lines, 1 errors)"
                val log = Json.parseToJsonElement(cli("info", "--json").out).jsonObject["log"]!!.jsonObject
                log["errors"]!!.jsonPrimitive.long shouldBe 1
                log["path"]!!.jsonPrimitive.content shouldBe join(home, "logs/rtok.log")
            }
        }
    })
