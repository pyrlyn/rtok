package rtok

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class RenderSpec : FunSpec({
    test("plugins table pads id and state to ten columns") {
        val t = pluginsTable(listOf(Triple("cmd", false, listOf("hook", "cli"))))
        t shouldStartWith "id        enabled   surfaces\n"
        t.lines()[1] shouldBe "cmd       off       hook,cli"
    }
})
