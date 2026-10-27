// Port of the store-backed checks of tests/report.rs (T22.1, T22.2, T22.5, T22.6, T207, T385.12.2, T581): every
// number traces to the seeded rows, HTML holds every Markdown number and fetches nothing, each advice rule fires once
// in recoverable order, a healthy store gets none, the totals agree with OTLP and the plugin pages, per-lane cache
// rows, and the top-sinks filter.
@file:Suppress("MagicNumber")

package rtok.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.jsonArray
import rtok.config.Config
import rtok.model.Plugins
import rtok.model.ReportLedgers
import rtok.model.StatsPage
import rtok.plugin.sdk.Measurement
import rtok.store.Store
import rtok.store.countMeasurements
import rtok.store.insertCall
import rtok.store.insertCallIo
import rtok.store.insertMeasurement
import rtok.store.insertUsage
import rtok.store.markExpanded
import rtok.store.otelSavedTotals
import rtok.store.putArchive
import rtok.store.putArchiveDecision
import rtok.store.setCallMs
import rtok.store.setCallServiceTier
import rtok.store.upsertSession
import rtok.sys.createDirAll
import rtok.sys.writeString

private const val BODY_A = """{"system":"s","tools":[{"name":"A"}],"messages":[]}"""
private const val BODY_MORE_TOOLS = """{"system":"s","tools":[{"name":"A"},{"name":"B"}],"messages":[]}"""
private val SECTIONS = listOf("Window", "Savings", "Calls", "Cache", "Expand", "Config", "Doctor", "Recommendations")

private fun open(home: String): Pair<Config, Store> {
    rtok.support.installDb()
    val cfg = Config.loadFrom(home)
    return cfg to Store.open(cfg.core.dbPath)
}

private fun Store.measure(
    session: String,
    plugin: String,
    kind: String,
    est: Pair<Int, Int>,
    ref: String? = null,
    bytes: Pair<ULong, ULong> = 100uL to 40uL,
) = insertMeasurement(session, Measurement(plugin, kind, bytes.first, bytes.second, est.first.toUInt(), est.second.toUInt(), ref))

/** The `stats --cache` bust pattern: a read drop plus cache_create > 20k with the tools array changed, at turn 3. */
private fun Store.bust(session: String) {
    for ((body, create, read) in listOf(Triple(BODY_A, 0L, 0L), Triple(BODY_A, 500L, 30_000L), Triple(BODY_MORE_TOOLS, 31_000L, 200L))) {
        val id = insertCall(session, "proxy", "api_request", null, null, null, null, null)
        insertCallIo(id, body.encodeToByteArray(), null, 1 shl 20, null)
        insertUsage(session, "m", "anthropic", 100, create, read, 5, id)
    }
}

/** Three live-zone pointers over archived payloads, the first frozen by `rtok expand`; returns its id. */
private fun Store.pointers(
    session: String,
    dir: String,
    tool: (Int) -> String,
): String {
    val ids = listOf("first payload\n", "second", "third").map { putArchive(session, it.encodeToByteArray(), dir) }
    ids.forEachIndexed { i, id -> putArchiveDecision(tool(i), id, session, "head…") }
    markExpanded(ids[0])
    return ids[0]
}

/** report.rs `seed`: the store every asserted number is summed from; returns the frozen archive id. */
private fun seed(home: String): String {
    createDirAll("$home/.claude")
    writeString(
        "$home/.claude/settings.json",
        """{"hooks":{"PreToolUse":[{"hooks":[{"type":"command","command":"rtok hook PreToolUse"}]}],"PostToolUse":[{"hooks":[{"type":"command","command":"rtok hook PostToolUse"}]}]}}""",
    )
    val (cfg, s) = open(home)
    s.upsertSession("s1", null, null, null, "proxy")
    for ((surface, kind, ms) in listOf(Triple("hook", "hook", 4.0), Triple("hook", "hook", 2.0), Triple("mcp", "mcp_call", 10.0))) {
        s.setCallMs(s.insertCall("s1", surface, kind, null, null, null, null, null), ms)
    }
    s.insertCall("s1", "proxy", "api_request", null, null, null, null, null)
    s.bust("s1")
    val first = s.pointers("s1", cfg.core.archiveDir) { "t${it + 1}" }
    s.measure("s1", "cmd", "filter", 25 to 10)
    s.measure("s1", "archive", "pointer", 100 to 20, first)
    s.measure("s1", "archive", "expand", 0 to 5, first)
    return first
}

/** report.rs `seed_advice`: one store in which every rule fires exactly once. */
private fun seedAdvice(home: String) {
    val (cfg, s) = open(home)
    s.upsertSession("adv", null, null, null, "proxy")
    for ((event, n) in listOf("PreCompact" to 12, "PostToolUse" to 12, "PreToolUse" to 2, "SessionStart" to 1)) {
        repeat(n) { s.insertCall("adv", "hook", "hook", null, null, null, null, event) }
    }
    s.bust("adv")
    val first = s.pointers("adv", cfg.core.archiveDir) { "u$it" }
    s.measure("adv", "cmd", "filter", 25 to 10)
    s.measure("adv", "archive", "pointer", 100 to 20, first)
    s.measure("adv", "archive", "expand", 0 to 5, first)
    s.measure("adv", "inject", "inject", 2000 to 1500)
    s.measure("adv", "toon", "table", 10 to 25)
}

/** Maximal digit-led runs (`30d` → `30`, `33.3%` → `33.3`): the Check's "every number". */
private fun numbers(md: String): List<String> =
    Regex("[0-9][0-9.]*")
        .findAll(md)
        .map { it.value.trimEnd('.') }
        .filter { it.isNotEmpty() }
        .toList()

private fun inOrder(positions: List<Int>) = positions == positions.sorted()

private fun rule(
    out: String,
    name: String,
) = out.indexOf("- **$name**:")

class ReportStoreCliSpec :
    FunSpec({
        tags(Cli)
        test("the fixture's numbers are traceable to rows (T22.1)") {
            withReportHome("report-fixture") { home ->
                val first = seed(home)
                val out = reportOk("report")
                out shouldStartWith "# rtok report\n\nWindow "
                out shouldContain " (30d)"
                out shouldContain " · store "
                for (
                want in listOf(
                    "| calls | 7 of 7 | window (30d) |",
                    "| measurements | 3 | whole ledger (no row times) |",
                    "| usage | 3 | whole ledger (no row times) |",
                    "| cmd | 1 | 25 | 10 | 15 |",
                    "| archive | 2 | 100 | 25 | 75 |",
                    "Total: 90 est tokens over 3 `Measurement` rows",
                    "| hook | 2 | 2 | 2.0 | 4.0 |",
                    "| mcp | 1 | 1 | 10.0 | 10.0 |",
                    "| proxy | 4 | 0 | — | — |",
                    "7 of 7 `calls` rows in window (30d)",
                    "| tools | 1 |",
                    "Busts: 1 over 3 turns in 1 session(s).",
                    "`rtok expand` froze 1 of 3 live-zone pointers (33.3%)",
                    "Expanded: ${rtok.shortId(first)}.",
                    "| report.since | 30d | default |",
                    "| report.format | md | default |",
                    "## Doctor",
                    "hooks 2",
                    "33.3%",
                    "3 archive_decisions rows",
                    "session s1 turn 3",
                )
                ) {
                    out shouldContain want
                }
                out shouldNotContain first
                inOrder(SECTIONS.map { out.indexOf("## $it").also { i -> (i >= 0) shouldBe true } }) shouldBe true
                for (r in listOf("cache-bust", "expand-rate", "archive-window")) Regex(Regex.escape("- **$r**:")).findAll(out).count() shouldBe 1
                for (r in listOf("retire-plugin", "inject-budget", "idle-hook")) out shouldNotContain "- **$r**:"
                inOrder(listOf("cache-bust", "expand-rate", "archive-window").map { rule(out, it) }) shouldBe true
                out shouldNotContain "No recommendations."
            }
        }
        test("html holds every markdown number and fetches nothing (T22.2)") {
            withReportHome("report-html") { home ->
                val first = seed(home)
                val md = reportOk("report")
                val html = reportOk("report", "--format", "html")
                numbers(md).forEach { html shouldContain it }
                html shouldContain rtok.shortId(first)
                html shouldNotContain first
                inOrder(SECTIONS.map { html.indexOf(">$it</h2>").also { i -> (i >= 0) shouldBe true } }) shouldBe true
                Regex("<svg").findAll(html).count() shouldBe 3
                val bare = html.replace(Regex("<code>.*?(</code>|$)", RegexOption.DOT_MATCHES_ALL), "")
                bare shouldNotContain "http://"
                bare shouldNotContain "https://"
            }
        }
        test("each rule fires once in recoverable order (T22.5, T22.6)") {
            withReportHome("report-advice") { home ->
                seedAdvice(home)
                val out = reportOk("report")
                val order = listOf("cache-bust", "inject-budget", "retire-plugin", "expand-rate", "archive-window", "idle-hook")
                for (r in order) Regex(Regex.escape("- **$r**:")).findAll(out).count() shouldBe 1
                for (
                want in listOf(
                    "3 archive_decisions rows",
                    "1 toon Measurement row",
                    "session adv turn 3",
                    "12 hook `PreCompact` rows in window",
                    "1 inject Measurement row",
                    "budget_tokens 800",
                    "keep_turns 4",
                )
                ) {
                    out shouldContain want
                }
                out.substringAfter("- **idle-hook**:").substringBefore("\n- **") shouldNotContain "PostToolUse"
                inOrder(order.map { rule(out, it) }) shouldBe true
            }
        }
        test("a healthy store has no recommendations") {
            withReportHome("report-healthy") { home ->
                val (_, s) = open(home)
                s.upsertSession("ok", null, null, null, "proxy")
                repeat(2) { s.insertCall("ok", "hook", "hook", null, null, null, null, "PreToolUse") }
                repeat(12) { s.insertCall("ok", "hook", "hook", null, null, null, null, "PostToolUse") }
                val id = s.insertCall("ok", "proxy", "api_request", null, null, null, null, null)
                s.insertCallIo(id, BODY_A.encodeToByteArray(), null, 1 shl 20, null)
                s.insertUsage("ok", "m", "anthropic", 100, 0, 0, 5, id)
                s.measure("ok", "measure", "estimate", 25 to 10)
                s.measure("ok", "archive", "pointer", 100 to 20)
                s.measure("ok", "inject", "inject", 500 to 400)
                val out = reportOk("report")
                out shouldContain "## Recommendations"
                out shouldContain "No recommendations."
                out shouldNotContain "- **"
            }
        }
        test("report totals agree with the store, the OTLP export and the plugin pages (T207)") {
            withReportHome("report-t207") { home ->
                val (cfg, s) = open(home)
                s.upsertSession("s1", null, null, null, "proxy")
                s.measure("s1", "cmd", "filter", 25 to 10)
                s.measure("s1", "wasm_widget", "raw", 8 to 8)
                s.measure("s1", "archive", "expand", 0 to 16)
                val ledgers = ReportLedgers.read(cfg)
                ledgers.window.measurements shouldBe s.countMeasurements().toULong()
                ledgers.savings.totalSaved shouldBe s.otelSavedTotals().sumOf { it.saved }
                val dash = Plugins.build(cfg, s)
                for (id in listOf("cmd", "archive")) {
                    val cliRows =
                        StatsPage
                            .plugin(cfg, id)["rows"]!!
                            .jsonArray.size
                            .toLong()
                    cliRows shouldBe dash.first { it.id == id }.stats!!.rows
                }
            }
        }
        test("the cache section lists counters per lane and tier (T385.12.2)") {
            withReportHome("report-lanes") { home ->
                reportOk("report", "--format", "md") shouldNotContain "lane/tier"
                val (_, s) = open(home)
                s.upsertSession("s1", null, null, null, "proxy")
                val calls =
                    listOf(
                        Triple("api_request", "default", listOf(5L, 5, 90, 7)),
                        Triple("api_request:bulk", "flex", listOf(100L, 0, 0, 9)),
                        Triple("api_request:bulk", "default", listOf(10L, 10, 80, 2)),
                        Triple("api_request:batch", null, listOf(50L, 0, 0, 5)),
                    )
                for ((kind, tier, legs) in calls) {
                    val call = s.insertCall("s1", "proxy", kind, null, null, null, null, "/v1/x")
                    tier?.let { s.setCallServiceTier(call, it) }
                    s.insertUsage("s1", "m", "openai_chat", legs[0], legs[1], legs[2], legs[3], call)
                }
                val md = reportOk("report", "--format", "md")
                for (row in listOf("| agent/default | 5 | 5 | 90 | 7 | 90.0% |", "| batch/- | 50 | 0 | 0 | 5 | 0.0% |", "| bulk/default | 10 | 10 | 80 | 2 | 80.0% |", "| bulk/flex | 100 | 0 | 0 | 9 | 0.0% |")) {
                    md shouldContain row
                }
                reportOk("report", "--format", "html") shouldContain "<td>bulk/flex</td><td>100</td>"
                reportOk("report", "--ai") shouldContain "bulk/flex"
            }
        }
        test("top sinks survive larger non-actionable rows (T581)") {
            withReportHome("report-sinks") { home ->
                val (_, s) = open(home)
                s.upsertSession("sinks", null, null, null, "proxy")

                fun sink(
                    plugin: String,
                    kind: String,
                    bytes: Int,
                    id: String,
                ) = s.measure("sinks", plugin, kind, bytes / 4 to 1, id, bytes.toULong() to 1uL)
                repeat(10) { sink("archive", "pointer", 100_000, "arch$it") }
                sink("read", "read", 50, "src/small.rs")
                sink("cmd", "filter", 40, "cargo:1")
                sink("cmd", "wrap", 30, "srv/tool:1")
                val out = reportOk("report")
                for (want in listOf("read src/small.rs", "cmd cargo", "mcp srv/tool")) out shouldContain "$want cost"
                out shouldNotContain "archive arch"
                repeat(12) { sink("read", "read", 1_000 + it, "big$it.rs") }
                val more = reportOk("report")
                Regex("read big").findAll(more).count() shouldBe 10
                more shouldNotContain "read src/small.rs cost"
            }
        }
    })
