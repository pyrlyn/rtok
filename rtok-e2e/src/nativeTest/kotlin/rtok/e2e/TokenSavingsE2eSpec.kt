package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val TOTAL_SAVED = Regex("total_saved=(\\d+)tok rows=(\\d+)")

class TokenSavingsE2eSpec :
    FunSpec({
        test("a noisy command prints less and the report credits the saving") {
            Sandbox("token-savings").use { s ->
                val tests = (0 until 200).joinToString("") { "test suite::case_$it ... ok\n" }
                val body = "running 200 tests\n${tests}test result: ok. 200 passed; 0 failed\n"
                val shown = s.rtok("filter", "--cmd", "cargo test", "--archive", stdin = body)
                shown.code shouldBe 0
                (shown.out.length * 2 < body.length) shouldBe true

                val report = s.rtok("report", "--ai")
                report.code shouldBe 0
                report.out shouldContain "total_saved="
                val (saved, rows) = TOTAL_SAVED.find(report.out)!!.destructured
                rows shouldBe "1"
                (saved.toLong() > 0) shouldBe true
            }
        }
    })
