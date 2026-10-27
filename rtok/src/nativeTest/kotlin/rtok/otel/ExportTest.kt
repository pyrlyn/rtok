// Port of export.rs's unit tests and tests/otel.rs (the exporter against a scripted collector): every row once,
// marks only on 2xx, a 404 stream skipped, failures logged, the flush lock and the T143 queued slot.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("MagicNumber")

package rtok.otel

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.bind
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.Measurement
import rtok.store.endSession
import rtok.store.insertCall
import rtok.store.insertCallIo
import rtok.store.insertLog
import rtok.store.insertMeasurement
import rtok.store.insertUsage
import rtok.store.lastLog
import rtok.store.otelMark
import rtok.store.otelPending
import rtok.store.setCallMs
import rtok.store.upsertModel
import rtok.store.upsertSession
import rtok.support.parallel
import rtok.support.tmpDir
import rtok.sys.exists
import rtok.sys.removeDirAll
import rtok.tasks.FakeHttp
import rtok.tasks.ok
import rtok.tasks.status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ExportTest {
    private val dirs = mutableListOf<String>()

    private fun config(
        tag: String,
        endpoint: String,
        flushSecs: UInt = 2u,
        serviceName: String = "rtok",
    ): Config {
        val dir = tmpDir("otel-$tag").also { dirs += it }
        val c = Config.loadFrom(dir)
        val otel = c.otel.copy(endpoint = endpoint, headers = "x-key=k", flushSecs = flushSecs, serviceName = serviceName)
        return c.copy(core = c.core.copy(dbPath = "$dir/rtok.db", archiveDir = "$dir/archive"), otel = otel).also { it.home = dir }
    }

    /** `ctx` of tests/otel.rs: a runtime on [cfg] with session `s1` in project `p`. */
    private fun ctx(cfg: Config): Runtime = Runtime.open(cfg, "s1").also { it.store.upsertSession("s1", null, "p", "/w", "startup") }

    private fun <T> with(
        tag: String,
        endpoint: String = "http://collector.test",
        body: (Runtime) -> T,
    ): T {
        val cx = ctx(config(tag, endpoint))
        try {
            return body(cx)
        } finally {
            cx.close()
            dirs.forEach(::removeDirAll)
        }
    }

    /** Three calls (hook, mcp, proxy) with io, one measurement, one log row. */
    private fun seed(cx: Runtime) {
        val s = cx.store
        val hook = s.insertCall("s1", "hook", "hook", null, null, null, null, "PostToolUse")
        val req = """{"hook_event_name":"PostToolUse","tool_name":"Read","tool_use_id":"toolu_1","tool_input":{"file_path":"/x"},"tool_response":"body"}"""
        s.insertCallIo(hook, req.encodeToByteArray(), null, 65536, null)
        s.setCallMs(hook, 1.5)
        val mcp = s.insertCall("s1", "mcp", "tool_call", null, null, null, "graph", "symbol")
        s.insertCallIo(mcp, """{"name":"b"}""".encodeToByteArray(), "chain.rs:4".encodeToByteArray(), 65536, null)
        val (pid, mid) = s.upsertModel("anthropic", "claude-x")
        val chat = s.insertCall("s1", "proxy", "api_request", null, pid, mid, null, "/v1/messages")
        s.setCallMs(chat, 25.0)
        s.insertUsage("s1", "claude-x", "anthropic", 100, 10, 20, 30, chat)
        s.insertMeasurement("s1", Measurement("proxy", "raw", 10u, 5u, 3u, 1u, callId = chat))
        s.insertLog("info", "hook", "dispatch", "hello", "s1", hook, null)
    }

    /** A collector answering 200 on every stream unless [except] names one. */
    private fun collector(vararg except: String) = FakeHttp().apply { listOf("/v1/traces", "/v1/logs", "/v1/metrics").filter { it !in except }.forEach { on("POST", it) { ok("{}") } } }

    private fun envEndpointSet() =
        !platform.posix
            .getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
            ?.toKString()
            .isNullOrEmpty()

    private fun flush(
        cx: Runtime,
        http: FakeHttp,
    ) = Export.flush(cx) { http }

    @Test fun resourceNamesTheServiceVersionAndSdk() {
        Runtime.open(config("res", "", serviceName = "my-svc"), "s").use { cx ->
            assertEquals(
                listOf(s("service.name", "my-svc"), s("service.version", rtok.VERSION), s("telemetry.sdk.name", "rtok"), s("telemetry.sdk.language", "kotlin")),
                resource(cx).attrs,
            )
        }
        dirs.forEach(::removeDirAll)
    }

    @Test fun everyRowPostsOnceAndTheMarksAdvance() =
        with("ok") { cx ->
            seed(cx)
            val http = collector()
            val r = flush(cx, http)
            assertNull(r.error, r.toString())
            assertEquals(listOf(3, 1, 3), listOf(r.spans, r.logs, r.posted))
            assertTrue(r.enabled)
            val t = http.calls.single { it.path == "/v1/traces" }
            assertTrue("content-type" to "application/json" in t.headers && "x-key" to "k" in t.headers, t.headers.toString())
            for (want in listOf("execute_tool Read", "execute_tool symbol", "chat claude-x", "gen_ai.usage.cache_read.input_tokens", "rtok.measurement")) {
                assertTrue(want in t.body, want)
            }
            assertEquals(1, http.hits("POST", "/v1/logs", """"stringValue":"hello""""))
            assertEquals(3, cx.store.otelMark("calls"))
            assertEquals(1, cx.store.otelMark("logs"))
            val again = flush(cx, http)
            assertEquals(listOf(0, 0, 1), listOf(again.spans, again.logs, again.posted))
            assertEquals(1, http.hits("POST", "/v1/traces"))
            assertEquals(1, http.hits("POST", "/v1/logs"))
            assertEquals(2, http.hits("POST", "/v1/metrics"))
            // An ended session ships its root span; a second flush does not repeat it.
            cx.store.endSession("s1", 1_700_000_000)
            assertEquals(1, flush(cx, http).spans)
            assertEquals(0, flush(cx, http).spans)
            assertEquals(1, http.hits("POST", "/v1/traces", "invoke_agent"))
            assertEquals(1, cx.store.otelMark("sessions_seq"))
        }

    @Test fun sessionInWatermarkSecondPostsOnce() =
        with("tie") { cx ->
            seed(cx)
            val http = collector()
            assertNull(flush(cx, http).error)
            cx.store.endSession("s1", 1_700_000_000)
            assertEquals(1, flush(cx, http).spans)
            cx.store.upsertSession("s2", null, "p", "/w", "startup")
            cx.store.endSession("s2", 1_700_000_000)
            assertEquals(1, flush(cx, http).spans)
            assertEquals(0, flush(cx, http).spans)
            assertEquals(3, http.hits("POST", "/v1/traces"))
            assertEquals(1, http.hits("POST", "/v1/logs"))
            assertEquals(2, cx.store.otelMark("sessions_seq"))
        }

    /** T592: a session ending in an already-posted second ships whatever its id sorts like. */
    private fun lateSessionShipsOnce(
        tag: String,
        first: String,
        late: String,
    ) {
        val cfg = config(tag, "http://collector.test")
        val http = collector()

        fun posts(id: String) = http.calls.count { it.path == "/v1/traces" && "invoke_agent" in it.body && "\"stringValue\":\"$id\"" in it.body }
        try {
            ctx(cfg).use { cx ->
                for (id in listOf(first, late)) cx.store.upsertSession(id, null, "p", "/w", "startup")
                cx.store.endSession(first, 1_700_000_000)
                val r = flush(cx, http)
                assertEquals(null to 1, r.error to r.spans)
                assertEquals(1, posts(first))
                cx.store.endSession(late, 1_700_000_000)
                assertEquals(1, flush(cx, http).spans)
                assertEquals(1, posts(late))
                assertEquals(1, posts(first))
                assertEquals(0, flush(cx, http).spans, "the next flush drains")
            }
            // The mark is stored, so a fresh process picks up where this one stopped.
            ctx(cfg).use { cx -> assertEquals(0, flush(cx, http).spans) }
            assertEquals(1, posts(late))
        } finally {
            dirs.forEach(::removeDirAll)
        }
    }

    @Test fun aLateSessionWithALowerIdShipsInTheWatermarkSecond() = lateSessionShipsOnce("late-low", "z-first", "a-late")

    @Test fun aLateSessionWithAHigherIdShipsInTheWatermarkSecond() = lateSessionShipsOnce("late-high", "a-first", "z-late")

    /** A still-streaming proxy call stops the batch in front of it; the finished span ships on the next flush. */
    @Test fun anInFlightCallWaitsForItsFinish() =
        with("inflight") { cx ->
            seed(cx)
            val s = cx.store
            val (pid, mid) = s.upsertModel("anthropic", "claude-x")
            val open = s.insertCall("s1", "proxy", "api_request", null, pid, mid, null, "/v1/messages")
            val later = s.insertCall("s1", "hook", "hook", null, null, null, null, "Stop")
            s.setCallMs(later, 2.0)
            val http = collector()
            val r = flush(cx, http)
            assertNull(r.error, r.toString())
            assertEquals(3, r.spans, "the seeded calls ship; the open one and its followers wait")
            assertEquals(3, s.otelMark("calls"))
            s.setCallMs(open, 40.0)
            assertEquals(2, flush(cx, http).spans, "finished: both ship")
            assertEquals(later.toLong(), s.otelMark("calls"))
            assertEquals(0, flush(cx, http).spans)
            assertEquals(2, http.hits("POST", "/v1/traces"))
        }

    @Test fun aTraces404StillPostsLogsAndMetrics() =
        with("t404") { cx ->
            seed(cx)
            val http = collector("/v1/traces")
            val r = flush(cx, http)
            assertNull(r.error, r.toString())
            assertEquals(listOf(0, 1, 8, 2), listOf(r.spans, r.logs, r.points, r.posted))
            assertEquals(listOf("traces"), r.skipped)
            assertTrue(r.toString().endsWith("not served: traces"), r.toString())
            assertEquals(0, cx.store.otelMark("calls"))
            assertEquals(1, cx.store.otelMark("logs"))
            assertNull(cx.store.lastLog("otel"), "no error row")
            flush(cx, http)
            assertEquals(1, http.hits("POST", "/v1/logs"))
            assertEquals(2, http.hits("POST", "/v1/metrics"))
        }

    @Test fun traces500StillPostsLogsAndMetrics() =
        with("t500") { cx ->
            seed(cx)
            val http = collector().on("POST", "/v1/traces") { status(500, "nope") }
            val r = flush(cx, http)
            val err = assertNotNull(r.error, "a 500 is an error")
            assertTrue("/v1/traces: HTTP 500" in err, err)
            assertEquals(listOf(0, 1, 8, 2), listOf(r.spans, r.logs, r.points, r.posted))
            assertTrue(r.skipped.isEmpty())
            assertEquals(listOf(1, 1, 1), listOf("/v1/traces", "/v1/logs", "/v1/metrics").map { http.hits("POST", it) })
            assertEquals(0, cx.store.otelMark("calls"))
            assertEquals(1, cx.store.otelMark("logs"))
        }

    @Test fun aFailureKeepsTheMarksAndIsLogged() =
        with("fail") { cx ->
            seed(cx)
            val http = FakeHttp().on("POST", "/v1/traces") { status(500, "nope") }
            val r = flush(cx, http)
            assertTrue("/v1/traces: HTTP 500" in assertNotNull(r.error))
            assertEquals(0, r.posted)
            assertEquals(0, cx.store.otelMark("calls"))
            assertEquals(0, cx.store.otelMark("logs"))
            val last = assertNotNull(cx.store.lastLog("otel"), "failure logged")
            assertEquals("error" to "flush", last.level to last.name)
            assertEquals(3L to 2L, cx.store.otelPending())
        }

    /** A backend with no logs or metrics pipeline (Jaeger) answers 404: skipped, mark kept, nothing logged. */
    @Test fun a404StreamIsSkippedNotLogged() =
        with("nf") { cx ->
            seed(cx)
            val http = collector("/v1/logs", "/v1/metrics")
            val r = flush(cx, http)
            assertNull(r.error, r.toString())
            assertEquals(listOf(3, 0, 0, 1), listOf(r.spans, r.logs, r.points, r.posted))
            assertEquals(listOf("logs", "metrics"), r.skipped)
            assertTrue(r.toString().endsWith("not served: logs, metrics"), r.toString())
            assertEquals(3, cx.store.otelMark("calls"))
            assertEquals(0, cx.store.otelMark("logs"))
            assertNull(cx.store.lastLog("otel"))
            flush(cx, http)
            assertEquals(0L to 1L, cx.store.otelPending(), "pending logs do not grow per flush")
        }

    @Test fun metricsRepeatTheTotalsEveryFlush() =
        with("metrics") { cx ->
            seed(cx)
            val http = collector()
            val r = flush(cx, http)
            assertNull(r.error, r.toString())
            // 4 token types × 1 model, 1 saved row, 3 call rows.
            assertEquals(8, r.points)
            val m = http.calls.single { it.path == "/v1/metrics" }.body
            val wants =
                listOf("rtok.tokens", "rtok.tokens.saved", "rtok.calls", """"isMonotonic":true""", """"isMonotonic":false""", """"aggregationTemporality":2""", """"asInt":"100"""", """"asInt":"2"""")
            for (want in wants) assertTrue(want in m, want)
            val again = flush(cx, http)
            assertEquals(listOf(0, 0, 8), listOf(again.spans, again.logs, again.points))
            assertEquals(2, http.hits("POST", "/v1/metrics"))
        }

    /** T16.9: two overlapping flushes against one store post each row once; the flush lock serialises them. */
    @Test fun concurrentFlushesPostEachRowOnce() {
        val cfg = config("race", "http://collector.test")
        try {
            ctx(cfg).use(::seed)
            val http =
                collector().on("POST", "/v1/traces") {
                    platform.posix.usleep(250_000u)
                    ok("{}")
                }
            val reports = Array<Report?>(2) { null }
            val errs =
                parallel(2) { i ->
                    if (i == 1) platform.posix.usleep(30_000u)
                    ctx(cfg).use { cx -> reports[i] = flush(cx, http) }
                }
            assertEquals(emptyList(), errs)
            val (a, b) = reports.map { assertNotNull(it) }
            assertNull(a.error)
            assertNull(b.error)
            assertEquals(3, a.spans + b.spans, "spans must not double: $a $b")
            assertEquals(1, a.logs + b.logs, "logs must not double: $a $b")
            assertEquals(1, http.hits("POST", "/v1/traces"))
            assertEquals(1, http.hits("POST", "/v1/logs"))
            ctx(cfg).use { cx ->
                assertEquals(3, cx.store.otelMark("calls"))
                assertEquals(1, cx.store.otelMark("logs"))
                assertEquals(0L to 0L, cx.store.otelPending())
            }
        } finally {
            dirs.forEach(::removeDirAll)
        }
    }

    /** A loopback port nothing listens on is refused: every stream errors, no mark moves, the failure is logged. */
    @Test fun unreachableCollectorReturnsTheErrorAndMarksNothing() =
        with("unreach", "http://127.0.0.1:1") { cx ->
            cx.store.insertCall("s1", "cli", "cmd", null, null, null, null, null)
            cx.log("info", "test", "seed", "one log row")
            val pending = cx.store.otelPending()
            val r = Export.flush(cx)
            assertTrue(r.enabled)
            val err = assertNotNull(r.error, "a refused connect is an error")
            for (stream in listOf("/v1/traces", "/v1/logs", "/v1/metrics")) assertTrue(stream in err, err)
            assertEquals(listOf(0, 0, 0, 0), listOf(r.spans, r.logs, r.points, r.posted))
            assertTrue(r.skipped.isEmpty(), "a refusal is not a 404")
            for (stream in listOf("calls", "logs", "sessions_seq")) assertEquals(0, cx.store.otelMark(stream), stream)
            val last = assertNotNull(cx.store.lastLog("otel"))
            assertEquals("error" to "flush", last.level to last.name)
            val (calls, logs) = cx.store.otelPending()
            assertEquals(pending.first, calls)
            assertEquals(pending.second + 1, logs, "the error row joins the pending logs")
        }

    @Test fun flushWithoutAnEndpointIsADisabledNoOp() {
        if (envEndpointSet()) return
        with("off", "") { cx ->
            val r = Export.flush(cx)
            assertEquals(Report(), r)
            assertEquals("otel: no endpoint", r.toString())
            assertNull(cx.store.lastLog("otel"))
        }
    }

    @Test fun errorsFromSeveralStreamsJoinInOneReportLine() {
        val rep = Report(enabled = true)
        rep.pushError("/v1/traces: a")
        rep.pushError("/v1/logs: b")
        assertEquals("/v1/traces: a; /v1/logs: b", rep.error)
        assertEquals("otel: 0 spans · 0 logs · 0 metric points · 0 posts\notel: error: /v1/traces: a; /v1/logs: b", rep.toString())
    }

    /** T143: the queued slot is a plain non-blocking lock: first caller wins, the rest back off until it drops. */
    @Test fun queuedSlotFirstAttemptWinsTheRestBackOffUntilReleased() {
        val cfg = config("queue", "")
        val first = assertNotNull(tryQueue(cfg), "the first attempt claims the slot")
        assertNull(tryQueue(cfg), "a second attempt backs off while the slot is held")
        assertNull(tryQueue(cfg), "a third attempt also backs off")
        first.close()
        val fourth = assertNotNull(tryQueue(cfg), "the slot frees once the guard drops")
        assertTrue(exists(lockPath(cfg, QUEUED_LOCK)), "the lock file lives next to the DB")
        assertEquals("${cfg.home}/rtok.$QUEUED_LOCK", lockPath(cfg, QUEUED_LOCK))
        fourth.close()
        dirs.forEach(::removeDirAll)
    }

    /** T143: `spawnChild`'s pre-check mirrors the slot's state without taking it. */
    @Test fun queuedLockHeldReportsTheSlotWithoutTakingIt() {
        val cfg = config("queue-held", "")
        assertFalse(queuedLockHeld(cfg), "nothing queued yet")
        val guard = assertNotNull(tryQueue(cfg))
        assertTrue(queuedLockHeld(cfg))
        assertTrue(queuedLockHeld(cfg), "checking again does not release it")
        guard.close()
        assertFalse(queuedLockHeld(cfg), "released once the guard drops")
        dirs.forEach(::removeDirAll)
    }

    /** T143: the flush lock and the queued slot are independent files. */
    @Test fun theQueuedSlotAndTheFlushLockAreIndependent() {
        val cfg = config("queue-indep", "")
        flushLock(cfg).use { assertNotNull(tryQueue(cfg), "the flush lock must not block the queued slot").close() }
        dirs.forEach(::removeDirAll)
    }

    /** T143: a coalesced flush that finds the slot taken returns at once, posting nothing. */
    @Test fun aCoalescedFlushBacksOffWhileAFlushIsQueued() =
        with("coalesce") { cx ->
            seed(cx)
            val http = collector()
            tryQueue(cx.config)!!.use { assertEquals(Report(), Export.flushCoalesced(cx) { http }) }
            assertTrue(http.calls.isEmpty())
            assertEquals(3, Export.flushCoalesced(cx) { http }.spans, "a free slot flushes")
            tryQueue(cx.config)!!.use { assertEquals(1, Export.flush(cx) { http }.posted, "a manual flush ignores the slot") }
        }

    /** A listening socket nobody accepts from: the client connects, then waits for a reply that never comes. */
    private fun hungCollector(): Pair<Int, String> =
        memScoped {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_addr.s_addr = 0x0100007Fu // 127.0.0.1, network order
            addr.sin_port = 0u
            check(bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) == 0)
            check(listen(fd, 16) == 0)
            val len = alloc<socklen_tVar>().also { it.value = sizeOf<sockaddr_in>().convert() }
            getsockname(fd, addr.ptr.reinterpret(), len.ptr)
            val port = ((addr.sin_port.toInt() and 0xff) shl 8) or (addr.sin_port.toInt() shr 8)
            fd to "http://127.0.0.1:$port"
        }

    /** T143: a collector that never answers must not hold the flush lock forever: `flush_secs` bounds each POST. */
    @Test fun aHungCollectorStillReleasesTheLockWithinTheTimeout() {
        val (fd, url) = hungCollector()
        try {
            Runtime.open(config("hung", url, flushSecs = 1u), "s1").use { cx ->
                cx.log("info", "test", "seed", "one log row")
                val start = TimeSource.Monotonic.markNow()
                val r = Export.flush(cx)
                assertNotNull(r.error, "a hang must surface as an error: $r")
                assertTrue(start.elapsedNow() < 10.seconds, "took ${start.elapsedNow()}")
                val again = TimeSource.Monotonic.markNow()
                assertNotNull(Export.flush(cx).error)
                assertTrue(again.elapsedNow() < 10.seconds, "the first flush's lock must already be released")
            }
        } finally {
            platform.posix.close(fd)
            dirs.forEach(::removeDirAll)
        }
    }
}
