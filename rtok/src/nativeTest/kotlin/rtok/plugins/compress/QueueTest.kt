// Port of src/plugins/compress/queue.rs tests, plus Compressor tests over a scripted rtok.http client.
package rtok.plugins.compress

import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import rtok.config.Config
import rtok.http.*
import rtok.plugin.Runtime
import rtok.store.*
import rtok.support.*

/** Scripted rtok.http client: answers every POST with [reply], records the requests. */
private class Scripted(val reply: (HttpRequest) -> HttpResponse) : HttpClient {
    val requests = ArrayList<HttpRequest>()
    override suspend fun post(req: HttpRequest) = reply(req).also { requests += req }
    override fun close() {}
}

private fun llmOn(cap: UInt = 2u): (Config) -> Config = { c -> c.copy(plugins = c.plugins.copy(compress = c.plugins.compress.copy(llm = c.plugins.compress.llm.copy(enabled = true, maxInFlight = cap)))) }
private fun anthropic(text: String, stop: String = "end_turn") = HttpResponse(200, buildJsonObject {
    put("stop_reason", stop); putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } } }.toString().encodeToByteArray())
private fun queue(r: Runtime, cap: UInt) = Queue(assertNotNull(Compressor.create(r.config) { Scripted { anthropic("x") } }), r.store, cap)
private val AUTH = assertNotNull(Auth.of(listOf("x-api-key" to "k-test")))
private const val LOG = "running 400 checks\n" + "ok line with nothing special in it at all\n"

class QueueTest {
    @OptIn(ObsoleteWorkersApi::class)
    @Test fun concurrentDistinctIdsNeverExceedTheCap() = withRuntime("t655-race", llmOn(1u)) { r ->
        repeat(20) { round ->
            val q = queue(r, 1u); val threads = 32; val ready = AtomicInt(0); val go = AtomicInt(0); val done = AtomicInt(0)
            val workers = (0 until threads).map { kotlin.native.concurrent.Worker.start() }
            val futures = workers.mapIndexed { i, w -> w.execute(kotlin.native.concurrent.TransferMode.SAFE, { Triple(q, i, Triple(ready, go, done)) }) { (q, i, sync) ->
                sync.first.incrementAndGet(); while (sync.second.value == 0) {}
                val got = q.admit("id-$i")
                sync.third.incrementAndGet(); while (sync.third.value < 32) {} // slots stay held until every thread has tried
                (got as? Queue.Admit.Run)?.slot?.close(); got is Queue.Admit.Run
            } }
            while (ready.value < threads) {}; go.value = 1
            val admitted = futures.count { it.result }
            workers.forEach { it.requestTermination().result }
            assertTrue(admitted <= 1, "round $round: cap 1 admitted $admitted"); assertEquals(0, q.running, "every slot is released")
        }
    }

    @Test fun anIdRefusedAtTheCapIsRetriedOnceASlotFrees() = withRuntime("t655-retry", llmOn(1u)) { r ->
        val q = queue(r, 1u)
        val first = assertIs<Queue.Admit.Run>(q.admit("a"))
        assertIs<Queue.Admit.Full>(q.admit("b")); assertEquals(1, q.running)
        first.slot.close()
        assertIs<Queue.Admit.Run>(q.admit("b"), "b was not marked seen").slot.close()
        assertEquals(0, q.running)
    }

    @Test fun aDuplicateHandsItsReservationBack() = withRuntime("t655-dup", llmOn(2u)) { r ->
        val q = queue(r, 2u)
        assertIs<Queue.Admit.Run>(q.admit("a")); assertIs<Queue.Admit.Seen>(q.admit("a"))
        assertEquals(1, q.running, "only the running job holds a slot")
    }
}

class CompressorTest {
    private fun archived(r: Runtime, text: String) = r.store.putArchive("s", text.encodeToByteArray(), r.config.core.archiveDir)

    @Test fun aShorterSummaryKeepingEverySpanIsStoredAndTheRequestHasTheRustShape() = withRuntime("llm-store", llmOn()) { r ->
        val text = "error[E0308]: mismatched types at src/lib.rs:42:7\n" + "noise line with plain words only\n".repeat(40)
        val id = archived(r, text); val http = Scripted { anthropic("  error[E0308]: mismatched types at src/lib.rs:42:7 (40 noise lines)  ") }
        val c = assertNotNull(Compressor.create(r.config) { http })
        assertEquals(Outcome.Stored, runBlocking { c.run(r.store, AUTH, id) })
        assertEquals("error[E0308]: mismatched types at src/lib.rs:42:7 (40 noise lines)", r.store.archiveSummary(id, c.version))
        assertEquals(Outcome.Cached, runBlocking { c.run(r.store, AUTH, id) }); assertEquals(1, http.requests.size)
        val req = http.requests.single()
        assertEquals("http://127.0.0.1:${r.config.proxy.port}/v1/messages", req.url)
        assertEquals(listOf("content-type" to "application/json", "x-rtok-lane" to "internal", "anthropic-version" to "2023-06-01", "x-api-key" to "k-test"), req.headers)
        val body = Json.parseToJsonElement(req.body.decodeToString()).jsonObject
        assertEquals(listOf("model", "max_tokens", "system", "messages"), body.keys.toList())
        val user = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(text in user && "- src/lib.rs:42:7" in user && "{{" !in user, user)
    }

    @Test fun aSummaryThatDropsASpanIsRejectedAndWarned() = withRuntime("llm-reject", llmOn()) { r ->
        val id = archived(r, "connect failed addr=10.0.0.1:5432\n" + "plain words only here\n".repeat(40))
        val c = assertNotNull(Compressor.create(r.config) { Scripted { anthropic("connection failed") } })
        val out = assertIs<Outcome.Rejected>(runBlocking { c.run(r.store, AUTH, id) })
        assertTrue("10.0.0.1:5432" in out.dropped); assertNull(r.store.archiveSummary(id, c.version))
    }

    @Test fun upstreamErrorsAndCutOffRepliesFail() = withRuntime("llm-fail", llmOn()) { r ->
        val id = archived(r, "plain words only here\n".repeat(40))
        val down = assertNotNull(Compressor.create(r.config) { Scripted { HttpResponse(503, ByteArray(0)) } })
        assertEquals(Outcome.Failed("upstream status 503 Service Unavailable"), runBlocking { down.run(r.store, AUTH, id) })
        val cut = assertNotNull(Compressor.create(r.config) { Scripted { anthropic("short", stop = "max_tokens") } })
        assertEquals(Outcome.Failed("summary cut off at max_output_tokens"), runBlocking { cut.run(r.store, AUTH, id) })
        val gone = assertNotNull(Compressor.create(r.config) { Scripted { throw HttpException(HttpException.Kind.ConnectTimeout, "connect timeout: x") } })
        assertEquals(Outcome.Failed("connect timeout: x"), runBlocking { gone.run(r.store, AUTH, id) })
    }

    @Test fun codeAndOversizedInputsAreSkippedWithoutACall() = withRuntime("llm-skip", llmOn()) { r ->
        val http = Scripted { anthropic("x") }; val c = assertNotNull(Compressor.create(r.config) { http })
        assertEquals(Outcome.Skipped("diff or source"), runBlocking { c.run(r.store, AUTH, archived(r, "fn a() {\n    x;\n}\n")) })
        assertEquals(Outcome.Skipped("too large"), runBlocking { c.run(r.store, AUTH, archived(r, "y".repeat(70_000))) })
        assertEquals(Outcome.Skipped("archive payload missing"), runBlocking { c.run(r.store, AUTH, "nope") })
        assertTrue(http.requests.isEmpty())
    }

    @Test fun aDisabledFlagBuildsNoClientAndAConfiguredUrlWins() = withRuntime("llm-off") { r ->
        assertNull(Compressor.create(r.config) { error("no client while off") })
        val on = r.config.let { llmOn()(it) }.let { it.copy(plugins = it.plugins.copy(compress = it.plugins.compress.copy(llm = it.plugins.compress.llm.copy(url = " https://api.example/ ")))) }
        var settings: HttpSettings? = null
        assertEquals("https://api.example/v1/messages", assertNotNull(Compressor.create(on) { settings = it; Scripted { anthropic("x") } }).url)
        assertEquals(HttpSettings(10_000, 60_000, 60_000), settings)
    }
}
