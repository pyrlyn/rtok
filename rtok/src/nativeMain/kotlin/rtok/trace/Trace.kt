// R3 self-tracing: spans of rtok's own work (command, hook, MCP tool, proxy request, plugin) posted as OTLP/HTTP JSON
// to a collector, for debugging the port in Jaeger or Grafana. Separate from `rtok otel`, which exports the agents'
// ledgers: nothing here touches the store, stdout or the log format. Off unless `RTOK_TRACE` is set; a collector that
// is down costs one short timeout and one log line, never the command.
@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)
@file:Suppress("TooGenericExceptionCaught", "TooManyFunctions")

package rtok.trace

import kotlinx.coroutines.runBlocking
import rtok.VERSION
import rtok.appendLog
import rtok.config.Config
import rtok.config.Endpoint
import rtok.config.Otel
import rtok.config.envVar
import rtok.http.HttpClientFactory
import rtok.http.HttpException
import rtok.http.HttpRequest
import rtok.http.HttpSettings
import rtok.http.ktor.KtorHttp
import rtok.otel.Attr
import rtok.otel.Event
import rtok.otel.Kind
import rtok.otel.Resource
import rtok.otel.Span
import rtok.otel.Status
import rtok.otel.i
import rtok.otel.s
import rtok.otel.traces
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.ThreadLocal
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.uuid.Uuid

private const val DEFAULT_URL = "http://localhost:4318"
private const val TRACES_PATH = "/v1/traces"
private const val DEFAULT_TIMEOUT_MS = 2_000L
private const val DEFAULT_DELAY_MS = 5_000L

/** Buffered spans beyond this are dropped (counted) so a long-lived process with no collector stays bounded. */
private const val MAX_BUFFERED = 8_192
private const val MAX_TEXT = 1_000
private const val TRACE_HEX = 32
private const val SPAN_HEX = 16
private const val FLAGS_HEX = 2
private const val PARTS = 4
private const val NS_PER_SEC = 1_000_000_000L

/** What the environment asks for; null when tracing is off. */
data class Settings(
    /** Full URL of the traces endpoint, `/v1/traces` included. */
    val url: String,
    val headers: List<Pair<String, String>>,
    val timeoutMs: Long,
    val delayMs: Long,
    val serviceName: String,
    val parent: TraceParent?,
) {
    companion object {
        /**
         * `RTOK_TRACE` = `1`/`true`/`on` turns tracing on. The endpoint and headers follow the OTLP exporter spec:
         * `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` as is, else `OTEL_EXPORTER_OTLP_ENDPOINT` + `/v1/traces`, else
         * localhost:4318. A short default timeout because a hook must not wait on a missing collector.
         */
        fun from(env: (String) -> String?): Settings? {
            if (env("RTOK_TRACE")?.trim()?.lowercase() !in setOf("1", "true", "on", "yes")) return null
            // The second resolve keeps OTEL_EXPORTER_OTLP_HEADERS when only the default URL applies.
            val base = Otel().resolveWith(env) ?: Otel(endpoint = DEFAULT_URL).resolveWith(env) ?: Endpoint(DEFAULT_URL, emptyList())
            val url = env("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")?.trim()?.takeIf { it.isNotEmpty() } ?: (base.url + TRACES_PATH)

            fun ms(
                key: String,
                default: Long,
            ) = env(key)?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: default
            return Settings(
                url = url,
                headers = base.headers,
                timeoutMs = ms("OTEL_EXPORTER_OTLP_TIMEOUT", DEFAULT_TIMEOUT_MS),
                delayMs = ms("OTEL_BSP_SCHEDULE_DELAY", DEFAULT_DELAY_MS),
                serviceName = env("OTEL_SERVICE_NAME")?.trim()?.takeIf { it.isNotEmpty() } ?: "rtok",
                parent = env("TRACEPARENT")?.let(TraceParent::parse),
            )
        }
    }
}

/** W3C `traceparent`: `00-<trace id>-<parent span id>-<flags>`; the link between an rtok process and its parent. */
data class TraceParent(
    val traceId: String,
    val spanId: String,
) {
    fun format() = "00-$traceId-$spanId-01"

    companion object {
        private fun hex(
            s: String,
            len: Int,
        ) = s.length == len && s.all { it in '0'..'9' || it in 'a'..'f' } && s.any { it != '0' }

        /** Null for anything but a well-formed version-00 header with non-zero ids. */
        fun parse(header: String): TraceParent? {
            val p = header.trim().split('-').takeIf { it.size == PARTS } ?: return null
            val ids = hex(p[1], TRACE_HEX) && hex(p[2], SPAN_HEX)
            return TraceParent(p[1], p[2]).takeIf { p[0] == "00" && ids && p[PARTS - 1].length == FLAGS_HEX }
        }
    }
}

/** A span still running; [end] turns it into an immutable [Span]. */
class OpenSpan internal constructor(
    val traceId: String,
    val spanId: String,
    val parent: String?,
    var name: String,
    val kind: Kind,
    val startNs: Long,
    attrs: List<Attr>,
) {
    internal val attrs = attrs.toMutableList()
    internal val events = mutableListOf<Event>()
    internal var status: Status = Status.Unset
    internal val closed = AtomicInt(0)

    val traceParent get() = TraceParent(traceId, spanId)
}

/**
 * One process's tracer. Spans nest per thread (a thread-local stack); a span started on one thread and ended on
 * another still ends, it only stops being a parent. Finished spans wait in a lock-free buffer until [flush].
 */
class Tracer(
    val settings: Settings,
    private val http: HttpClientFactory = KtorHttp.FACTORY,
    private val clock: () -> Long = ::nowNs,
    private val warn: (String) -> Unit = { appendLog(Config(), "warn", "trace", "flush", it) },
) {
    private val buffer = AtomicReference<List<Span>>(emptyList())
    private val dropped = AtomicInt(0)
    private val warned = AtomicInt(0)

    private val resource =
        Resource(listOf(s("service.name", settings.serviceName), s("service.version", VERSION), i("process.pid", platform.posix.getpid().toLong())))

    /** The innermost running span on this thread, else the process's parent from `TRACEPARENT`. */
    fun current(): TraceParent? =
        Stack.spans
            .lastOrNull()
            ?.takeIf { it.tracer === this }
            ?.span
            ?.traceParent ?: settings.parent

    fun start(
        name: String,
        kind: Kind,
        attrs: List<Attr>,
        parent: TraceParent? = current(),
        push: Boolean = true,
    ): OpenSpan {
        val span = OpenSpan(parent?.traceId ?: id(TRACE_HEX), id(SPAN_HEX), parent?.spanId, name, kind, clock(), attrs)
        if (push) Stack.spans += Frame(this, span)
        return span
    }

    /** Error status plus an `exception` event; the message only, never a stack trace. */
    fun fail(
        span: OpenSpan,
        error: Throwable,
    ) {
        val msg = (error.message ?: error::class.simpleName ?: "error").take(MAX_TEXT)
        span.status = Status.Error(msg)
        span.events += Event("exception", clock(), listOf(s("exception.type", error::class.qualifiedName ?: "Throwable"), s("exception.message", msg)))
    }

    fun end(
        span: OpenSpan,
        error: Throwable? = null,
    ) {
        Stack.spans.removeAll { it.span === span }
        if (error != null) fail(span, error)
        val done = Span(span.traceId, span.spanId, span.parent, span.name, span.kind, span.startNs, clock(), span.attrs.toList(), span.events.toList(), span.status)
        while (true) {
            val cur = buffer.value
            if (cur.size >= MAX_BUFFERED) {
                dropped.incrementAndGet()
                return
            }
            if (buffer.compareAndSet(cur, cur + done)) return
        }
    }

    /** Posts what is buffered; never throws. The first failure of the process is logged, later ones are not. */
    fun flush() {
        val spans = buffer.getAndSet(emptyList())
        if (spans.isEmpty()) return
        val lost = dropped.getAndSet(0)
        val res = if (lost > 0) Resource(resource.attrs + i("rtok.trace.dropped_spans", lost.toLong())) else resource
        val body = traces(res, spans).toString().encodeToByteArray()
        val req = HttpRequest(settings.url, listOf("content-type" to "application/json") + settings.headers, body)
        val problem =
            try {
                val t = settings.timeoutMs
                http.create(HttpSettings(t, t, t)).use { c -> runBlocking { c.post(req) } }.let { r ->
                    if (r.isSuccess) null else "HTTP ${r.status} ${r.text().take(MAX_TEXT)}".trimEnd()
                }
            } catch (e: HttpException) {
                e.message
            } catch (e: Exception) {
                e.message ?: e::class.simpleName
            }
        if (problem != null && warned.compareAndSet(0, 1)) warn("self-trace export to ${settings.url} failed: $problem")
    }

    // Uuid.random is the platform's secure random: safe from the proxy's many threads, never all zeros (version bits).
    private fun id(hexLen: Int): String = Uuid.random().toHexString().take(hexLen)
}

private class Frame(
    val tracer: Tracer,
    val span: OpenSpan,
)

@ThreadLocal
private object Stack {
    val spans = mutableListOf<Frame>()
}

private fun nowNs(): Long =
    kotlin.time.Clock.System
        .now()
        .let { it.epochSeconds * NS_PER_SEC + it.nanosecondsOfSecond }

/** The process-wide tracer: null (and every call a no-op) unless `RTOK_TRACE` turns it on. */
object Trace {
    @PublishedApi
    internal val tracer: Tracer? by lazy {
        Settings.from(::envVar)?.let { Tracer(it) }?.also(::startTicker)
    }

    val enabled get() = tracer != null

    /**
     * Runs [block] inside a span named [name]; an exception marks the span and propagates. [newTrace] starts a trace of
     * its own: one per request in long-lived processes, whose root span is only posted when they exit.
     */
    inline fun <T> span(
        name: String,
        attrs: List<Attr> = emptyList(),
        kind: Kind = Kind.Internal,
        newTrace: Boolean = false,
        block: () -> T,
    ): T {
        val t = tracer ?: return block()
        val open = t.start(name, kind, attrs, if (newTrace) null else t.current())
        val result =
            try {
                block()
            } catch (e: Throwable) {
                t.end(open, e)
                throw e
            }
        t.end(open)
        return result
    }

    /**
     * Adds [attrs] to the innermost running span on this thread, renames it when [name] is given, and sets error status
     * with [error] for a failure that is an answer, not an exception (a tool that answered `isError`).
     */
    fun annotate(
        attrs: List<Attr>,
        name: String? = null,
        error: String? = null,
    ) {
        val frame = Stack.spans.lastOrNull() ?: return
        frame.span.attrs += attrs
        if (name != null) frame.span.name = name
        if (error != null) frame.span.status = Status.Error(error)
    }

    /** Marks the innermost running span on this thread as failed, for code that turns an exception into an exit code. */
    fun fail(error: Throwable) {
        val frame = Stack.spans.lastOrNull() ?: return
        frame.tracer.fail(frame.span, error)
    }

    /**
     * The root of a new trace not tied to this thread's stack, for a request that hops threads (the proxy's
     * coroutines); end it with [close]. Null when tracing is off.
     */
    fun open(
        name: String,
        attrs: List<Attr> = emptyList(),
        kind: Kind = Kind.Internal,
    ): OpenSpan? = tracer?.start(name, kind, attrs, null, push = false)

    /** Ends a span from [open] once; later calls are ignored so every exit path may call it. */
    fun close(
        span: OpenSpan?,
        attrs: List<Attr> = emptyList(),
        error: Throwable? = null,
    ) {
        if (span == null || !span.closed.compareAndSet(0, 1)) return
        span.attrs += attrs
        tracer?.end(span, error)
    }

    /** `TRACEPARENT` for a child process, so its spans join this trace; null when tracing is off. */
    fun childEnv(): Pair<String, String>? = tracer?.current()?.let { "TRACEPARENT" to it.format() }

    fun flush() {
        tracer?.flush()
    }

    /** Long-lived processes (`mcp`, `proxy`, `demon`, `web`) never reach [flush] in `main`; a worker posts every delay. */
    @OptIn(ObsoleteWorkersApi::class)
    private fun startTicker(t: Tracer) {
        runCatching {
            Worker.start(name = "rtok-trace").execute(TransferMode.SAFE, { t }) { tr ->
                runBlocking {
                    while (true) {
                        kotlinx.coroutines.delay(tr.settings.delayMs)
                        tr.flush()
                    }
                }
            }
        }
    }
}
