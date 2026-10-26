package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class CmdCliSpec :
    FunSpec({
        tags(Cli)
        test("filter --cmd applies the command's formatter to stdin") {
            withHome("filter") {
                val r = cli("filter", "--cmd", "git status", stdin = "On branch main\nChanges not staged for commit:\n\tmodified:   a.rs\n")
                r.code shouldBe 0
                r.out shouldContain " M a.rs"
                r.out shouldNotContain "Changes not staged"
            }
        }
        test("filter --no-ref never appends the pointer") {
            withHome("filter-noref") {
                val body = (0 until 200).joinToString("\n") { "line $it" }
                cli("filter", "--cmd", "cat", "--no-ref", stdin = body).out shouldNotContain "[rtok "
            }
        }
        test("run takes the words after -- and keeps the exit code") {
            withHome("run") { cli("run", "--", "sh", "-c", "exit 4").code shouldBe 4 }
        }
    })
