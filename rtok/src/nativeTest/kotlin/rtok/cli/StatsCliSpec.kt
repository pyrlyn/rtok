// Port of tests/stats_price.rs (T49.1) and the `rtok stats` checks of commands_e2e.rs / plugins_e2e.rs: the table and
// JSON price the proxy `usage` rows; a plain `stats` mentions no cost; `--cache`, `--plugin` and baselines render.
@file:Suppress("MagicNumber")

package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.store.Store
import rtok.store.insertCall
import rtok.store.insertUsage
import rtok.store.upsertSession
import rtok.sys.createDirAll
import rtok.sys.writeString

private const val S1 = """{"type":"assistant","message":{"content":[{"type":"text","text":"hi"}],"usage":{"input_tokens":4,"output_tokens":1}}}
"""

/** A home whose transcripts and Codex dirs live inside it, so `stats` never reads the real `~/.claude`. */
private fun statsHome(home: String) {
    createDirAll("$home/.claude/projects/acme")
    writeString("$home/.claude/projects/acme/s1.jsonl", S1)
    writeString("$home/config.toml", "[stats]\ntranscripts_dir = \"$home/.claude/projects\"\ncodex_dir = \"$home/.codex/sessions\"\n")
}

private data class UsageSeed(
    val model: String?,
    val api: String,
    val legs: List<Long>,
)

/** Shipped prices: sonnet $11.60 (saved $14.40), mini $3.10 (saved $0.90); the other two rows print `-`. */
private val SEED =
    listOf(
        UsageSeed("claude-sonnet-5", "anthropic", listOf(2_000_000, 400_000, 8_000_000, 500_000)),
        UsageSeed("gpt-5-mini", "openai_chat", listOf(4_000_000, 0, 4_000_000, 1_000_000)),
        UsageSeed("mystery-1", "anthropic", listOf(30, 0, 0, 1)),
        UsageSeed(null, "anthropic", listOf(7, 0, 0, 0)),
    )

private fun seed(
    home: String,
    rows: List<UsageSeed> = SEED,
) {
    statsHome(home)
    rtok.support.installDb()
    val store = Store.open(Config.loadFrom(home).core.dbPath)
    store.upsertSession("s1", null, null, null, "proxy")
    val id = store.insertCall("s1", "proxy", "api_request", null, null, null, null, "/v1/messages")
    for (r in rows) store.insertUsage("s1", r.model, r.api, r.legs[0], r.legs[1], r.legs[2], r.legs[3], id)
}

private fun json(out: String) = Json.parseToJsonElement(out).jsonObject

private fun ok(vararg args: String): String {
    val r = cli(*args)
    r.err shouldBe ""
    r.code shouldBe 0
    return r.out
}

class StatsCliSpec :
    FunSpec({
        tags(Cli)
        test("stats --price tables priced models and dashes unknown ones") {
            withHome("stats-price") { home ->
                seed(home)
                val out = ok("stats", "--price")
                out shouldContain "cost total \$14.70 (cache reads saved \$15.30"
                for (want in listOf("claude-sonnet-5", "11.60", "gpt-5-mini", "3.10")) out shouldContain want
                for (model in listOf("mystery-1", "unknown")) {
                    val line = out.lines().first { it.startsWith(model) }
                    line.endsWith("-") shouldBe true
                }
            }
        }
        test("stats --price --json carries the costs") {
            withHome("stats-price-json") { home ->
                seed(home)
                val cost = json(ok("stats", "--price", "--json"))["cost"]!!.jsonObject
                cost["total_cost"]!!.jsonPrimitive.content shouldBe "14.7"
                cost["total_saved"]!!.jsonPrimitive.content shouldBe "15.3"
                val models = cost["models"]!!.jsonObject
                models["claude-sonnet-5"]!!.jsonObject["cost"]!!.jsonPrimitive.content shouldBe "11.6"
                models["claude-sonnet-5"]!!.jsonObject["saved"]!!.jsonPrimitive.content shouldBe "14.4"
                models["gpt-5-mini"]!!.jsonObject["cost"]!!.jsonPrimitive.content shouldBe "3.1"
                models["mystery-1"]!!.jsonObject["cost"] shouldBe JsonNull
                models["mystery-1"]!!.jsonObject["input"]!!.jsonPrimitive.content shouldBe "30"
                cost["unknown"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("mystery-1", "unknown")
            }
        }
        test("stats without --price mentions no costs") {
            withHome("stats-plain") { home ->
                seed(home)
                ok("stats") shouldNotContain "cost"
                json(ok("stats", "--json")).containsKey("cost") shouldBe false
            }
        }
        test("T389: the shipped defaults price the main workload model") {
            withHome("stats-fable") { home ->
                seed(home, listOf(UsageSeed("claude-fable-5-1", "anthropic", listOf(1_000_000, 200_000, 4_000_000, 100_000))))
                val v = json(ok("stats", "--price", "--json"))
                val fable = v["cost"]!!.jsonObject["models"]!!.jsonObject["claude-fable-5-1"]!!.jsonObject
                fable["cost"]!!.jsonPrimitive.content shouldBe "18.5"
                fable["saved"]!!.jsonPrimitive.content shouldBe "39.0"
                v["cost"]!!.jsonObject["unknown"]!!.jsonArray.size shouldBe 0
            }
        }
        test("stats --json parses and counts the transcript; usage matches the store") {
            withHome("stats-json") { home ->
                seed(home)
                val v = json(ok("stats", "--json"))
                v["sessions"]!!.jsonPrimitive.content shouldBe "1"
                v["usage_input"]!!.jsonPrimitive.content shouldBe "4"
                (v["api"] as JsonObject).keys shouldBe setOf("anthropic", "openai_chat")
            }
        }
        test("a bad --since is blamed on the flag") {
            withHome("stats-since") { home ->
                statsHome(home)
                val r = cli("stats", "--since", "7x")
                r.code shouldBe 1
                r.err shouldContain "--since"
            }
        }
        test("--save-baseline then --compare prints zero deltas") {
            withHome("stats-base") { home ->
                statsHome(home)
                ok("stats", "--save-baseline", "before") shouldBe "$home/measurements/before.json\n"
                val text = ok("stats", "--compare", "before")
                text shouldContain "sessions 1 Δ0"
                text.lines().filter { 'Δ' in it }.all { it.endsWith("Δ0") } shouldBe true
            }
        }
        test("--cache on an empty store says so; --plugin lists a catalogue plugin's rows") {
            withHome("stats-cache") { home ->
                statsHome(home)
                ok("stats", "--cache") shouldContain "no usage rows (run `rtok proxy` first)"
                ok("stats", "--cache", "--json") shouldBe "[]"
                val p = json(ok("stats", "--plugin", "archive", "--json"))
                p.keys.toList() shouldBe listOf("archive_hits", "decisions", "expand_rate", "expanded", "plugin", "rows")
                p["expand_rate"]!!.jsonPrimitive.content shouldBe "0.0"
            }
        }
    })
