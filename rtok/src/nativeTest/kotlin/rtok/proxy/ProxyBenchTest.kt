// Port of tests/proxy_bench.rs (T298) and `health_stays_fast_while_a_large_request_is_recorded` (T205) from
// tests/proxy.rs: the replay bench's end-to-end saving floor for archive/toon/compress and its mutation check, and
// `/health` staying responsive while one huge compress-mode request is shaped.
@file:Suppress("MagicNumber")

package rtok.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.Expand
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.shortId
import rtok.store.MeasRow
import rtok.store.listMeasurements
import rtok.store.measurementCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

private const val SESSION = "sess-t298-bench"
private const val PASSTHROUGH_ID = "tu-4"
private const val PASSTHROUGH_TEXT = "No matches found."

/** Measured 2026-09-27 on the Rust side: end-to-end saving 91.4%; floor = measured − 5. */
private const val FLOOR_PCT = 86.4
private const val FULL_ID_LEN = 64
private const val BIG_MB = 48

private val plugins = listOf("archive", "toon", "compress")

/** `tool_use_id -> content` of every user tool_result, pristine or rewritten. */
private fun toolResults(body: ByteArray): Map<String, String> =
    json(body)["messages"]!!
        .jsonArray
        .map { it.jsonObject }
        .filter { it["role"]?.jsonPrimitive?.content == "user" }
        .flatMap { m ->
            m["content"]!!.jsonArray.map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "tool_result" }.map {
                it["tool_use_id"]!!.jsonPrimitive.content to it["content"]!!.jsonPrimitive.content
            }
        }.toMap()

private fun pctSaved(
    before: Long,
    after: Long,
) = (before - after) * 100.0 / maxOf(before, 1)

/** One estimate pair per tool_use_id (original fixture vs what upstream got), so a chained block counts once. */
private fun endToEnd(
    cx: Runtime,
    sent: Map<String, String>,
): Pair<Long, Long> {
    var before = 0L
    var after = 0L
    for ((id, orig) in toolResults(proxyFixture("messages.json"))) {
        before += cx.estimate(orig, Class.Code).toLong()
        after += cx.estimate(sent[id] ?: error("$id missing from the sent body"), Class.Code).toLong()
    }
    return before to after
}

/** The id a pointer prints: a full sha256 id becomes its short prefix (T909). */
private fun printedArchiveId(refId: String) = if (refId.length >= FULL_ID_LEN && refId.all { it.isDigit() || it in 'a'..'f' }) shortId(refId) else refId

/** Every [plugin] row not in [skip] must match what upstream received. */
private fun assertTerminalBytes(
    rows: List<MeasRow>,
    plugin: String,
    skip: Set<String>,
    sent: Collection<String>,
) {
    for (r in rows.filter { it.refId != null && it.refId !in skip }) {
        val ref = r.refId!!
        val content = sent.firstOrNull { printedArchiveId(ref) in it } ?: error("$plugin $ref: not sent")
        assertEquals(r.afterBytes, content.encodeToByteArray().size.toLong(), "$plugin: bytes")
    }
}

private class BenchRun(
    val p: ProxyUnderTest,
    val sent: ByteArray,
    val cx: Runtime,
) : AutoCloseable {
    override fun close() {
        cx.store.close()
        p.close()
    }
}

private fun benchRun(
    tag: String,
    up: FakeUpstream,
    extra: (Config) -> Config = { it },
): BenchRun {
    val p =
        ProxyUnderTest.start(tag, { c: Config ->
            extra(c.copy(plugins = c.plugins.copy(compress = c.plugins.compress.copy(enabled = true))).proxy { copy(upstream = up.url, mode = "compress") })
        })
    assertTrue(ProxyHttp.isSuccess(call("POST", "${p.url}/v1/messages", proxyFixture("messages.json").decodeToString()).status), "proxy must forward the fixture")
    val sent = p.sent(p.usage(SESSION)[0])
    return BenchRun(p, sent, Runtime.open(p.state.cfg, "expand"))
}

class ProxyBenchTest {
    @Test fun proxySessionSavesOverTheFloor() {
        FakeUpstream(anthropicBody).use { up ->
            benchRun("t298-bench", up).use { run ->
                val store = run.p.state.store
                val sent = toolResults(run.sent)
                assertEquals(PASSTHROUGH_TEXT, sent[PASSTHROUGH_ID], "passthrough changed")
                for (plugin in plugins) {
                    val rows = store.listMeasurements(plugin)
                    assertTrue(rows.isNotEmpty(), "no $plugin rows")
                    assertTrue(rows.any { it.estAfter < it.estBefore }, "$plugin: no shrink")
                }
                val (before, after) = endToEnd(run.cx, sent)
                val pct = pctSaved(before, after)
                println("end-to-end $before $after $pct%")
                assertTrue(pct >= FLOOR_PCT, "$pct% below floor $FLOOR_PCT%")
                val compress = store.listMeasurements("compress")
                val toon = store.listMeasurements("toon")
                val archive = store.listMeasurements("archive")
                assertTerminalBytes(toon, "toon", emptySet(), sent.values)
                assertTerminalBytes(compress, "compress", emptySet(), sent.values)
                assertTerminalBytes(archive, "archive", compress.mapNotNull { it.refId }.toSet(), sent.values)
                val originals = toolResults(proxyFixture("messages.json")).values.toSet()
                val refs = (archive + toon + compress).mapNotNull { it.refId }.toSet()
                assertTrue(refs.isNotEmpty(), "no ref_id to expand")
                for (id in refs) {
                    val bytes = Expand.fetch(run.cx, id) ?: error("expand $id: empty")
                    assertTrue(bytes.decodeToString() in originals, "expand $id: wrong bytes")
                }
            }
        }
    }

    /** Disabling `archive` silences `compress` too, so `toon` alone cannot hold the floor. */
    @Test fun disablingArchivePluginDropsTheTotalBelowTheFloor() {
        FakeUpstream(anthropicBody).use { up ->
            benchRun("t298-bench-mut", up, { c -> c.copy(plugins = c.plugins.copy(archive = c.plugins.archive.copy(enabled = false))) }).use { run ->
                for (plugin in listOf("archive", "compress")) {
                    assertEquals(
                        0,
                        run.p.state.store
                            .measurementCount(plugin),
                        "$plugin: not silent",
                    )
                }
                val (before, after) = endToEnd(run.cx, toolResults(run.sent))
                val pct = pctSaved(before, after)
                assertTrue(pct < FLOOR_PCT, "should drop below floor, got $pct%")
            }
        }
    }
}

class ProxyHealthLoadTest {
    /** "Fast" is relative to the same run (T421): every poll under two thirds of the window the big request needs. */
    @Test fun healthStaysFastWhileALargeRequestIsRecorded() {
        val m = Mock({ it.path == "/v1/messages" }, headers = listOf("content-type" to "application/json"), chunks = listOf("""{"type":"message","usage":{"input_tokens":1,"output_tokens":1}}"""), delayMs = 300)
        FakeUpstream(m).use { up ->
            val tune = { c: Config -> c.copy(plugins = c.plugins.copy(compress = c.plugins.compress.copy(enabled = true))).proxy { copy(upstream = up.url, mode = "compress") } }
            ProxyUnderTest.start("t205-health", tune).use { p ->
                val word = "token "
                val big = word.repeat(BIG_MB * 1024 * 1024 / word.length)
                val body = """{"model":"$T51_MODEL","max_tokens":8,"messages":[{"role":"user","content":[{"type":"text","text":"$big"}]}],"metadata":{"user_id":"sess-t205-health"}}"""
                // One client for every poll, as reqwest's pooled client: a fresh connection per poll churns sockets.
                rtok.http.ktor.KtorHttp(rtok.http.HttpSettings()).use { client ->
                    runBlocking { polls(p, client, body, m) }
                }
            }
        }
    }

    private suspend fun polls(
        p: ProxyUnderTest,
        client: rtok.http.ktor.KtorHttp,
        body: String,
        m: Mock,
    ) {
        coroutineScope {
            val start = TimeSource.Monotonic.markNow()
            val bigReq = async(Dispatchers.Default) { runCatching { postJson("${p.url}/v1/messages", body).status } }
            var checks = 0
            var slowest = Duration.ZERO
            while (true) {
                val t0 = TimeSource.Monotonic.markNow()
                assertEquals(200, client.request("GET", rtok.http.HttpRequest("${p.url}/health", emptyList(), ByteArray(0))).status)
                slowest = maxOf(slowest, t0.elapsedNow())
                checks++
                if (bigReq.isCompleted || m.hits > 0) break
            }
            val window = start.elapsedNow()
            assertTrue(checks >= 2, "the big request must still overlap a /health poll")
            assertTrue(slowest < window * 2 / 3, "/health took $slowest of the $window the large request needed ($checks polls)")
            assertEquals(200, bigReq.await().getOrThrow())
            assertEquals(1, m.hits)
        }
    }
}
