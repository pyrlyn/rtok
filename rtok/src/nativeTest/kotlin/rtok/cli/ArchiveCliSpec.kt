package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.*
import rtok.sys.exists

/** A pi message array of [n] user/toolResult turns with large results. */
fun piMessages(n: Int): String = buildJsonArray {
    repeat(n) { i ->
        addJsonObject { put("role", "user"); put("content", "prompt $i") }
        addJsonObject {
            put("role", "toolResult"); put("toolCallId", "call_$i")
            put("content", buildJsonArray { addJsonObject { put("type", "text"); put("text", (1..400).joinToString("\n") { "r$i line $it: some shell output with words" }) } })
        }
    }
}.toString()

class ArchiveCliSpec : FunSpec({
    tags(Cli)
    context("archive rewrite --stdin") {
        test("non-JSON input is echoed byte for byte") {
            withHome("arch-echo") { cli("archive", "rewrite", "--stdin", stdin = "not json").also { it.err shouldBe ""; it.code shouldBe 0; it.out shouldBe "not json"; it.err shouldBe "" } }
        }
        test("a quiet array (inside keep_turns) is echoed") {
            withHome("arch-quiet") { val input = piMessages(3); cli("archive", "rewrite", "--stdin", stdin = input).out shouldBe input }
        }
        test("old results become pointers, kept as text blocks, and the db lands in the home") {
            withHome("arch-rewrite") { home ->
                val r = cli("archive", "rewrite", "--stdin", stdin = piMessages(6))
                r.code shouldBe 0
                val first = Json.parseToJsonElement(r.out).jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                first shouldStartWith "[archived "
                exists("$home/rtok.db") shouldBe true
            }
        }
        test("an unknown option is a usage error") {
            cli("archive", "rewrite", "--nope").also { it.code shouldBe ExitCode.USAGE; it.err shouldContain "--nope" }
        }
        test("without a subcommand it prints help and exits 0") { cli("archive").also { it.code shouldBe 0; it.out shouldContain "rewrite" } }
    }
})
