// R3 self-tracing: the env switch, TRACEPARENT, span nesting and the OTLP POST against a scripted collector.
@file:Suppress("MagicNumber", "TooManyFunctions")

package rtok.trace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.http.HttpClientFactory
import rtok.http.HttpException
import rtok.otel.Kind
import rtok.otel.s
import rtok.tasks.FakeHttp
import rtok.tasks.ok
import rtok.tasks.status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceTest {
    private fun env(vararg kv: Pair<String, String>): (String) -> String? = kv.toMap()::get

    private val parent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

    private fun tracer(
        http: FakeHttp,
        warnings: MutableList<String> = mutableListOf(),
        vararg kv: Pair<String, String>,
    ): Tracer {
        var t = 1_000L
        return Tracer(Settings.from(env("RTOK_TRACE" to "1", *kv))!!, HttpClientFactory { http }, { t++ }, { warnings += it })
    }

    private fun spans(http: FakeHttp): List<JsonObject> =
        http.calls.flatMap { c ->
            Json
                .parseToJsonElement(c.body)
                .jsonObject["resourceSpans"]!!
                .jsonArray[0]
                .jsonObject["scopeSpans"]!!
                .jsonArray[0]
                .jsonObject["spans"]!!
                .jsonArray
                .map { it.jsonObject }
        }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun offUnlessRtokTraceIsSet() {
        assertNull(Settings.from(env()))
        assertNull(Settings.from(env("RTOK_TRACE" to "0", "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://c:4318")))
    }

    @Test
    fun endpointFollowsTheOtlpSpec() {
        assertEquals("http://localhost:4318/v1/traces", Settings.from(env("RTOK_TRACE" to "1"))!!.url)
        val base = Settings.from(env("RTOK_TRACE" to "true", "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://c:4318/", "OTEL_EXPORTER_OTLP_HEADERS" to "k=v"))!!
        assertEquals("http://c:4318/v1/traces", base.url)
        assertEquals(listOf("k" to "v"), base.headers)
        val full = Settings.from(env("RTOK_TRACE" to "on", "OTEL_EXPORTER_OTLP_ENDPOINT" to "http://c:4318", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT" to "http://t/x"))!!
        assertEquals("http://t/x", full.url)
        val tuned = Settings.from(env("RTOK_TRACE" to "1", "OTEL_EXPORTER_OTLP_TIMEOUT" to "300", "OTEL_SERVICE_NAME" to "dbg"))!!
        assertEquals(300L, tuned.timeoutMs)
        assertEquals("dbg", tuned.serviceName)
        assertEquals(2_000L, Settings.from(env("RTOK_TRACE" to "1", "OTEL_EXPORTER_OTLP_TIMEOUT" to "-5"))!!.timeoutMs)
    }

    @Test
    fun traceParentRoundTripsAndRejectsJunk() {
        val p = TraceParent.parse(parent)!!
        assertEquals("0af7651916cd43dd8448eb211c80319c", p.traceId)
        assertEquals("b7ad6b7169203331", p.spanId)
        assertEquals(parent, p.format())
        for (bad in listOf("", "00-abc-def-01", "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "00-00000000000000000000000000000000-b7ad6b7169203331-01", "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01")) {
            assertNull(TraceParent.parse(bad), bad)
        }
    }

    @Test
    fun spansNestAndPostOnce() {
        val http = FakeHttp().on("POST", "/v1/traces") { ok("{}") }
        val t = tracer(http, mutableListOf(), "OTEL_EXPORTER_OTLP_HEADERS" to "auth=x")
        val root = t.start("rtok", Kind.Internal, emptyList())
        val child = t.start("hook", Kind.Internal, listOf(s("rtok.hook.event", "pre-tool-use")))
        t.end(child)
        t.end(root)
        t.flush()
        t.flush()
        assertEquals(1, http.calls.size)
        assertTrue("auth" to "x" in http.calls[0].headers)
        val (c, r) = spans(http)
        assertEquals("hook", c.str("name"))
        assertEquals(r.str("traceId"), c.str("traceId"))
        assertEquals(r.str("spanId"), c.str("parentSpanId"))
        assertNull(r.str("parentSpanId"))
        assertEquals(32, r.str("traceId")!!.length)
        assertNotEquals(r.str("spanId"), c.str("spanId"))
    }

    @Test
    fun traceParentFromTheEnvironmentIsTheRootParent() {
        val http = FakeHttp().on("POST", "/v1/traces") { ok("{}") }
        val t = tracer(http, mutableListOf(), "TRACEPARENT" to parent)
        t.end(t.start("rtok", Kind.Internal, emptyList()))
        t.flush()
        val r = spans(http).single()
        assertEquals("0af7651916cd43dd8448eb211c80319c", r.str("traceId"))
        assertEquals("b7ad6b7169203331", r.str("parentSpanId"))
    }

    @Test
    fun anExceptionMarksTheSpan() {
        val http = FakeHttp().on("POST", "/v1/traces") { ok("{}") }
        val t = tracer(http)
        val open = t.start("plugin", Kind.Internal, emptyList())
        t.end(open, IllegalStateException("boom"))
        t.flush()
        val sp = spans(http).single()
        assertEquals("boom", sp["status"]!!.jsonObject.str("message"))
        assertEquals("exception", sp["events"]!!.jsonArray[0].jsonObject.str("name"))
    }

    @Test
    fun aFailingCollectorWarnsOnceAndNeverThrows() {
        val warnings = mutableListOf<String>()
        val http = FakeHttp().on("POST", "/v1/traces") { status(503) }
        val t = tracer(http, warnings)
        repeat(2) {
            t.end(t.start("rtok", Kind.Internal, emptyList()))
            t.flush()
        }
        assertEquals(2, http.calls.size)
        assertEquals(1, warnings.size)
        assertTrue("HTTP 503" in warnings[0], warnings[0])
        val down = Tracer(Settings.from(env("RTOK_TRACE" to "1"))!!, { throw HttpException(HttpException.Kind.Connect, "refused") }, warn = { warnings += it })
        down.end(down.start("rtok", Kind.Internal, emptyList()))
        down.flush()
        assertTrue("refused" in warnings.last())
    }

    @Test
    fun spanIsTransparentWhenOff() {
        assertEquals(42, Trace.span("x") { 42 })
        assertFailsWith<IllegalStateException> { Trace.span("x") { error("still thrown") } }
    }
}
