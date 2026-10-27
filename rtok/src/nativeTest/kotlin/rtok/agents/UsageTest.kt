// Port of src/agents/usage.rs tests: the report over a fixture store (Kyiv is UTC+3 in October) and over the
// on-disk session logs the Rust trycmd cases read (`fixtures/usage-logs`). Numbers are compared as text, as the
// Rust asserts write them.
package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import rtok.config.Config
import rtok.config.ModelPrice
import rtok.config.envVar
import rtok.measure.rowCost
import rtok.plugin.sdk.Measurement
import rtok.store.Store
import rtok.store.insertMeasurement
import rtok.store.insertUsageAt
import rtok.store.setMeasurementTs
import rtok.store.usageByModel
import rtok.support.diskConfig
import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.writeString
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPSILON = 1e-5

@OptIn(kotlin.time.ExperimentalTime::class)
private fun at(utc: String) =
    kotlin.time.Instant
        .parse(utc)
        .epochSeconds

private fun legs(s: String) = s.split(" ").map(String::toLong).toLongArray()

private fun price(s: String): ModelPrice {
    val v = s.split(" ").map(String::toDouble)
    return ModelPrice(v[0], v[1], v[2], v[v.lastIndex])
}

private val prices = mapOf("claude-test" to price("1.0 1.25 0.1 5.0"), "gpt-x" to price("2.0 2.0 0.0 10.0"))

private fun Config.usage(
    source: String? = null,
    tz: String? = null,
    period: String? = null,
    by: String? = null,
    hosts: List<String>? = null,
    since: String? = null,
    until: String? = null,
): Config {
    val u = agents.usage
    return copy(
        agents =
            agents.copy(
                usage =
                    u.copy(
                        source = source ?: u.source,
                        tz = tz ?: u.tz,
                        period = period ?: u.period,
                        by = by ?: u.by,
                        hosts = hosts ?: u.hosts,
                        since = since ?: u.since,
                        until = until ?: u.until,
                    ),
            ),
    )
}

private fun fixture(): Pair<Config, Store> {
    val base = diskConfig("usage").first
    val cfg = base.copy(stats = base.stats.copy(prices = prices)).usage(source = "rtok", tz = "Europe/Kyiv")
    val store = Store.openInMemory()
    for (r in listOf(
        Row("s1", "claude", "claude-test", "2026-10-01T22:30:00Z", "1000000 0 2000000 100000"),
        Row("s1", "claude", "claude-test", "2026-10-02T10:00:00Z", "500000 0 0 0"),
        Row("s2", "codex", "openai/gpt-x-20260901", "2026-09-15T12:00:00Z", "1000000 0 0 200000"),
        Row("s2", "codex", "mystery", "2026-09-15T12:05:00Z", "4000 0 0 0"),
        Row("s3", null, "claude-test", "2026-10-02T09:00:00Z", "2000 0 0 0"),
    )) {
        store.insertUsageAt(r.session, r.host, r.model, at(r.utc), legs(r.legs))
    }
    for (l in listOf(
        Ledger("s1", "cmd", "filter", "5000000 1000000"),
        Ledger("s1", "expand", "expand", "0 500000"),
        Ledger("s2", "expand", "expand", "0 2000"),
    )) {
        val (before, after) = l.ests.split(" ").map(String::toUInt)
        store.insertMeasurement(l.session, Measurement(l.plugin, l.kind, 0u, 0u, before, after))
        store.setMeasurementTs(l.session, at("2026-10-01T22:30:00Z"))
    }
    return cfg to store
}

private data class Row(
    val session: String,
    val host: String?,
    val model: String,
    val utc: String,
    val legs: String,
)

private data class Ledger(
    val session: String,
    val plugin: String,
    val kind: String,
    val ests: String,
)

private fun logsConfig(source: String): Config {
    val root = "${envVar("RTOK_FIXTURES")}/usage-logs"
    val base = diskConfig("usage-logs").first
    return base.copy(stats = base.stats.copy(transcriptsDir = "$root/claude", codexDir = "$root/codex", prices = prices)).usage(source = source, tz = "Europe/Kyiv")
}

private fun JsonObject.agent(host: String) = (this["agents"] as JsonArray).map { it.jsonObject }.first { it["host"].toString() == "\"$host\"" }

class UsageReportTest {
    @Test fun theScreenTotalsByAgentAndMonthInTheZone() {
        val (cfg, store) = fixture()
        val r = Usage.report(cfg, store, 0)
        assertEquals("Europe/Kyiv 2026-10-02 3 3", "${r.tz} ${r.through} ${r.totals.sessions} ${r.totals.dailyRows}")
        assertEquals("4806000 6.202 1", "${r.totals.row.tokens} ${r.totals.row.costUsd} ${r.unpricedModels}")
        val expected =
            """
            |rtok agents usage: through rtok, up to 2026-10-02 (Europe/Kyiv)
            |
            |  4.81M tokens
            |  ${'$'}6.20 estimated cost
            |  3 sessions
            |  3 daily rows
            |  rtok saved 3.5M tokens (≈ ${'$'}3.50)
            |
            |! Cost is incomplete: 1 model has no price in [stats.prices], so its tokens are not in
            |  the estimate. `rtok agents usage --unpriced` lists them.
            |
            |Agent                    Tokens Estimated cost Saved tokens Saved est.
            |Codex                      1.2M          ${'$'}4.00        -2000      ${'$'}0.00
            |Claude Code                3.6M          ${'$'}2.20         3.5M      ${'$'}3.50
            |unattributed (anthropic)     2K          ${'$'}0.00            0      ${'$'}0.00
            |
            |Monthly totals
            |Month   Tokens Estimated cost
            |2026-09   1.2M          ${'$'}4.00
            |2026-10   3.6M          ${'$'}2.20
            |
            """.trimMargin()
        assertEquals(expected, UsageText.text(r))
        assertEquals("Model   Agent Tokens\nmystery codex     4K\n", UsageText.unpriced(r))
    }

    @Test fun aSessionAcrossUtcMidnightSplitsByTheZoneNotByUtc() {
        val (cfg, store) = fixture()
        val daily = cfg.usage(period = "daily")
        assertEquals(listOf("2026-09-15", "2026-10-02"), Usage.report(daily, store, 0).periods.map { it.first })
        assertEquals(listOf("2026-09-15", "2026-10-01", "2026-10-02"), Usage.report(daily.usage(tz = "UTC"), store, 0).periods.map { it.first })
    }

    @Test fun savedTokensAreTheNetLedgerPerAgentPricedAtItsInputRate() {
        val (cfg, store) = fixture()
        val v = UsageText.json(Usage.report(cfg, store, 0))
        assertEquals("3498000", v["totals"]!!.jsonObject["saved_tokens"].toString())
        val claude = v.agent("claude")
        assertEquals("3500000 3.5", "${claude["saved_tokens"]} ${claude["saved_usd"]}")
        val codex = (v["agents"] as JsonArray)[0].jsonObject
        assertEquals("-2000 -0.004", "${codex["saved_tokens"]} ${codex["saved_usd"]}")
        assertEquals(
            0L,
            Usage
                .report(cfg.usage(since = "2026-10-03"), store, 0)
                .totals.saved
                ?.tokens,
        )
    }

    @Test fun byModelGroupsTheMiddleTableByModel() {
        val (cfg, store) = fixture()
        val r = Usage.report(cfg.usage(by = "model"), store, 0)
        assertEquals(listOf("openai/gpt-x-20260901", "claude-test", "mystery"), r.models!!.map { it.first })
        val text = UsageText.text(r)
        assertTrue("Model " in text && "Saved tokens" !in text, text)
        assertEquals("\"openai/gpt-x-20260901\"", (UsageText.json(r)["models"] as JsonArray)[0].jsonObject["model"].toString())
        assertNull(UsageText.json(Usage.report(cfg.usage(by = "agent"), store, 0))["models"])
        assertFailsWith<UsageException> { Usage.report(cfg.usage(by = "day"), store, 0) }
    }

    @Test fun monthEdgesFollowTheDaylightSavingOffset() {
        val cfg = fixture().first
        val store = Store.openInMemory()
        for ((utc, session) in listOf("2026-07-31T21:30:00Z" to "a", "2026-12-31T21:30:00Z" to "b", "2026-12-31T22:30:00Z" to "c")) {
            store.insertUsageAt(session, "claude", "claude-test", at(utc), legs("1000 0 0 0"))
        }
        assertEquals(listOf("2026-08 1000", "2026-12 1000", "2027-01 1000"), Usage.report(cfg, store, 0).periods.map { "${it.first} ${it.second.tokens}" })
        assertEquals(2, Usage.report(cfg.usage(tz = "UTC"), store, 0).periods.size)
    }

    @Test fun hostAndWindowFiltersAndBadValues() {
        val (cfg, store) = fixture()
        val claude = cfg.usage(hosts = listOf("claude"), since = "2026-10-02")
        assertEquals(
            "3600000",
            Usage
                .report(claude, store, 0)
                .totals.row.tokens
                .toString(),
        )
        assertEquals(
            0L,
            Usage
                .report(claude.usage(until = "2026-10-01"), store, 0)
                .totals.row.tokens,
        )
        Usage.report(claude.usage(hosts = listOf("droid")), store, 0)
        for (bad in listOf(cfg.usage(hosts = listOf("nope")), cfg.usage(tz = "Mars/Base"), cfg.usage(period = "weekly"), cfg.usage(source = "bogus"))) {
            assertFailsWith<UsageException> { Usage.report(bad, store, 0) }
        }
    }

    @Test fun jsonFieldNamesAreStable() {
        val (cfg, store) = fixture()
        val v = UsageText.json(Usage.report(cfg, store, 0))
        assertEquals(listOf("agents", "periods", "skipped", "source", "through", "totals", "tz", "unpriced", "unpriced_models"), v.keys.sorted())
        val totals = v["totals"]!!.jsonObject
        assertEquals(
            listOf("cache_read", "cache_write", "cost_usd", "daily_rows", "input", "output", "saved_tokens", "saved_usd", "sessions", "tokens"),
            totals.keys.sorted(),
        )
        val agent = (v["agents"] as JsonArray)[0].jsonObject
        assertEquals(listOf("cache_read", "cache_write", "cost_usd", "host", "input", "name", "output", "saved_tokens", "saved_usd", "tokens"), agent.keys.sorted())
        assertEquals("period", (v["periods"] as JsonArray)[0].jsonObject.keys.first())
        assertEquals(listOf("host", "model", "tokens"), (v["unpriced"] as JsonArray)[0].jsonObject.keys.sorted())
        assertEquals("2506000 \"codex\"", "${totals["input"]} ${agent["host"]}")
    }

    @Test fun theTotalMatchesStatsPriceForTheSameRows() {
        val cfg = fixture().first
        val store = Store.openInMemory()
        for ((session, utc) in listOf("a" to "2026-10-01T10:00:00Z", "b" to "2026-10-02T10:00:00Z")) {
            store.insertUsageAt(session, "claude", "claude-test", at(utc), legs("1234567 10 2000000 98765"))
        }
        val statsPrice = store.usageByModel().sumOf { m -> rowCost(m.input, m.cacheCreate, m.cacheRead, m.output, cfg.stats.prices.getValue(m.model)).first }
        val total =
            Usage
                .report(cfg, store, 0)
                .totals.row.costUsd!!
        assertTrue(abs(statsPrice - total) < EPSILON, "$statsPrice vs $total")
    }
}

class UsageLogsReportTest {
    @Test fun logsTotalEachAgentOnceAndCutDaysInTheZone() {
        val cfg = logsConfig("logs")
        val store = Store.openInMemory()
        val r = Usage.report(cfg, store, 0)
        assertEquals("6850000 2 3 7.7", "${r.totals.row.tokens} ${r.totals.sessions} ${r.totals.dailyRows} ${r.totals.row.costUsd}")
        assertEquals("1 mystery-model", "${r.unpricedModels} ${r.unpriced[0].model}")
        assertEquals(listOf("Codex", "Claude Code"), r.agents.map { it.name })
        assertTrue(r.agents.all { it.coverage == null })
        assertEquals(listOf("2026-10"), r.periods.map { it.first })
        assertTrue(UsageText.text(r).startsWith("rtok agents usage: logs from 2 agents, up to 2026-10-02 (Europe/Kyiv)"))
        assertEquals(listOf("2026-09", "2026-10"), Usage.report(cfg.usage(tz = "UTC"), store, 0).periods.map { it.first })
        assertEquals(
            "3200000",
            Usage
                .report(cfg.usage(hosts = listOf("codex")), store, 0)
                .totals.row.tokens
                .toString(),
        )
    }

    @Test fun bothReportsHowMuchOfEachAgentPassedThroughRtok() {
        val cfg = logsConfig("both")
        val store = Store.openInMemory()
        store.insertUsageAt("s1", "claude", "claude-test", at("2026-10-01T09:00:00Z"), legs("1000000 0 0 0"))
        val r = Usage.report(cfg, store, 0)
        assertEquals(
            "6850000",
            r.totals.row.tokens
                .toString(),
        )
        val claude = r.agents.first { it.host == "claude" }
        assertEquals("1000000 0.274", "${claude.throughRtokTokens} ${claude.coverage}")
        val codex = r.agents.first { it.host == "codex" }
        assertEquals("0 0.0", "${codex.throughRtokTokens} ${codex.coverage}")
        val text = UsageText.text(r)
        assertTrue("logs from 2 agents and through rtok" in text, text)
        assertTrue("Through rtok Coverage" in text, text)
        assertTrue("27%" in text, text)
    }

    @Test fun bothListsAnAgentThatOnlyPassedThroughRtok() {
        val store = Store.openInMemory()
        store.insertUsageAt("s9", "cursor", "claude-test", at("2026-10-01T09:00:00Z"), legs("10 0 0 0"))
        val r = Usage.report(logsConfig("both"), store, 0)
        val cursor = r.agents.first { it.host == "cursor" }
        assertEquals("0 null 10 null", "${cursor.row.tokens} ${cursor.row.costUsd} ${cursor.throughRtokTokens} ${cursor.coverage}")
        assertEquals("cursor", r.agents.last().host)
        assertTrue("Saved tokens" in UsageText.text(r))
    }

    @Test fun aLogDirThatHoldsNoJsonIsNamedNotCounted() {
        val dir = tmpDir("usage-skipped")
        createDirAll("$dir/claude/p")
        createDirAll("$dir/codex")
        writeString("$dir/claude/p/s.jsonl", "not json\nstill not\n")
        writeString("$dir/codex/rollout.jsonl", "{broken\n")
        val base = logsConfig("logs")
        val cfg = base.copy(stats = base.stats.copy(transcriptsDir = "$dir/claude", codexDir = "$dir/codex"))
        val store = Store.openInMemory()
        val r = Usage.report(cfg, store, 0)
        assertEquals(listOf("claude" to "unknown format", "codex" to "unknown format"), r.skipped.map { it.host to it.reason })
        assertEquals(0L, r.totals.row.tokens)
        assertEquals(1, Usage.report(cfg.usage(hosts = listOf("codex")), store, 0).skipped.size)
        val s = (UsageText.json(r)["skipped"] as JsonArray)[0].jsonObject
        assertEquals("\"claude\"", s["host"].toString())
        assertTrue(s["path"].toString().endsWith("s.jsonl\""))
    }

    @Test fun unitsAndPricesNormalise() {
        assertEquals(listOf("999", "22.1K", "155.45M", "26.52B", "1K"), listOf("999", "22100", "155450000", "26520000000", "1000").map { UsageFormat.units(it.toLong()) })
        assertEquals("$52,020.90", UsageFormat.usd("52020.9".toDouble()))
        val one = mapOf("claude-test" to price("1.0 1.0 1.0 1.0"))
        for (id in listOf("claude-test", "anthropic/claude-test", "claude-test-20260901")) assertTrue(UsageFormat.price(one, id) != null, id)
        assertNull(UsageFormat.price(one, "claude-test-2"))
    }
}
