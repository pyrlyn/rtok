package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import rtok.sys.*

private fun big(i: Int) = (1..400).joinToString("\\n") { "r$i line $it: words words words" }

private fun pi(n: Int) =
    (0 until n).joinToString(",", "[", "]") { i ->
        """{"role":"user","content":"prompt $i"},{"role":"toolResult","toolCallId":"call_$i","content":[{"type":"text","text":"${big(i)}"}]}"""
    }

class ArchiveE2eSpec :
    FunSpec({
        context("rtok archive rewrite --stdin (pi carrier)") {
            test("a quiet array and junk come back verbatim with exit 0") {
                Sandbox("arch-quiet").use { s ->
                    s.rtok("archive", "rewrite", "--stdin", stdin = pi(2)).also {
                        it.code shouldBe 0
                        it.out shouldBe pi(2)
                        it.err shouldBe ""
                    }
                    s.rtok("archive", "rewrite", "--stdin", stdin = "junk").out shouldBe "junk"
                }
            }
            test("aged results become pointers, replays are byte-identical, the db and archive land in RTOK_HOME") {
                Sandbox("arch-rewrite").use { s ->
                    val a = s.rtok("archive", "rewrite", "--stdin", stdin = pi(6))
                    a.code shouldBe 0
                    a.err shouldBe ""
                    a.out shouldContain "[archived "
                    a.out shouldStartWith "[{\"role\":\"user\""
                    s.rtok("archive", "rewrite", "--stdin", stdin = pi(6)).out shouldBe a.out
                    s.rtok("archive", "rewrite", "--stdin", stdin = a.out).out shouldBe a.out
                    exists("${s.home}/rtok.db") shouldBe true
                    listDir("${s.home}/archive").isNotEmpty() shouldBe true
                }
            }
            test("a bad --config path fails with a message") {
                Sandbox("arch-badcfg").use { s ->
                    writeString("${s.root}/bad.toml", "[core\n")
                    s.rtok("archive", "rewrite", "--stdin", "--config", "${s.root}/bad.toml", stdin = "[]").also {
                        it.code shouldBe 1
                        it.err shouldStartWith "Error:"
                    }
                }
            }
        }
    })
