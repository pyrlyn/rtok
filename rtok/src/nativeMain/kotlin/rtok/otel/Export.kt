// Port of src/otel/export.rs: read past each watermark, encode, POST, advance on 2xx. Never throws: a failure is one
// `logs` row (`source = otel`), the marks stay, and the report says so. An exclusive file lock beside the DB
// serialises concurrent flushers across processes (T16.9); hook-spawned flushes coalesce to one running + one
// queued (T143). Each stream is isolated: a POST error keeps its own mark and the next stream still posts. A 404 is
// a backend without that pipeline: the stream is skipped, its mark stays, nothing is logged.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package rtok.otel

import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.config.Endpoint
import rtok.config.envVar
import rtok.http.HttpClient
import rtok.http.HttpClientFactory
import rtok.http.HttpException
import rtok.http.HttpRequest
import rtok.http.HttpSettings
import rtok.http.ktor.KtorHttp
import rtok.plugin.Runtime
import rtok.store.Call
import rtok.store.callDetail
import rtok.store.callsAfter
import rtok.store.hostSlug
import rtok.store.lastLog
import rtok.store.logsAfter
import rtok.store.otelAdvance
import rtok.store.otelMark
import rtok.store.otelPending
import rtok.store.sessionsPendingExport

/** Rows per stream per flush; the rest goes next time. */
const val BATCH = 1000L

/**
 * A `calls` row this young with no `ms` is still running (the proxy inserts the row before forwarding; a hook's row
 * closes with `dispatch`). Older than this it is a crashed call and drains as it is.
 */
private const val IN_FLIGHT_SECS = 300L

private const val NOT_FOUND = 404
private const val ERROR_BODY_CHARS = 200
private const val MS_PER_SEC = 1000L
private const val NS_PER_MS = 1_000_000L

internal const val FLUSH_LOCK = "otel-flush.lock"
internal const val QUEUED_LOCK = "otel-flush-queued.lock"

/** `lane::is_api_request`: the bare agent lane or any `api_request:<lane>`. */
private fun isApiRequest(kind: String) = kind == "api_request" || kind.startsWith("api_request:")

internal fun inFlight(
    c: Call,
    now: Long,
) = c.ms == null && (isApiRequest(c.kind) || c.kind == "hook") && c.ts > now - IN_FLIGHT_SECS

data class Report(
    val enabled: Boolean = false,
    var spans: Int = 0,
    var logs: Int = 0,
    var points: Int = 0,
    var posted: Int = 0,
    /** Streams the backend answered 404 to (`traces`, `logs`, `metrics`). */
    val skipped: MutableList<String> = mutableListOf(),
    var error: String? = null,
) {
    /** A failed stream keeps its watermark; the message joins the report so one `logs` row covers every failure. */
    fun pushError(msg: String) {
        error = error?.let { "$it; $msg" } ?: msg
    }

    override fun toString(): String {
        if (!enabled) return "otel: no endpoint"
        val sb = StringBuilder("otel: $spans spans · $logs logs · $points metric points · $posted posts")
        if (skipped.isNotEmpty()) sb.append(" · not served: ${skipped.joinToString(", ")}")
        error?.let { sb.append("\notel: error: $it") }
        return sb.toString()
    }
}

fun resource(cx: Runtime) =
    Resource(
        listOf(
            s("service.name", cx.config.otel.serviceName),
            s("service.version", rtok.VERSION),
            s("telemetry.sdk.name", "rtok"),
            s("telemetry.sdk.language", "kotlin"),
        ),
    )

/** `[otel]` resolved against the environment; null when export is off. */
fun endpoint(cfg: Config): Endpoint? = cfg.otel.resolveWith(::envVar)

/** `Path::with_extension`: the lock files live beside the DB (`rtok.db` → `rtok.<suffix>`). */
internal fun lockPath(
    cfg: Config,
    suffix: String,
): String {
    val db = cfg.core.dbPath
    val slash = db.lastIndexOf('/')
    val dot = db.lastIndexOf('.')
    return if (dot > slash + 1) "${db.substring(0, dot)}.$suffix" else "$db.$suffix"
}

/** An exclusive flock held through its fd; [close] releases it. */
internal class FileLock(
    private val fd: Int,
) : AutoCloseable {
    override fun close() {
        platform.posix.close(fd)
    }
}

internal fun flushLock(cfg: Config) = FileLock(rtok.sys.lockExclusive(lockPath(cfg, FLUSH_LOCK)))

/** T143: the queued slot when this caller claimed it; null when another hook-spawned flush holds it. */
internal fun tryQueue(cfg: Config): FileLock? {
    val path = lockPath(cfg, QUEUED_LOCK)
    path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { rtok.sys.createDirAll(it) }
    return rtok.sys.tryLockExclusive(path)?.let(::FileLock)
}

/** Non-blocking check for [spawnChild]: true while another process holds the queued slot. Errors fail open. */
internal fun queuedLockHeld(cfg: Config): Boolean = runCatching { tryQueue(cfg)?.close() == null }.getOrDefault(false)

object Export {
    /**
     * One flush: traces (ended sessions + calls), logs, then metrics. Errors are reported, not thrown. Waiting on
     * the flush lock keeps at-least-once: the loser runs after and finds the marks already advanced.
     */
    fun flush(
        cx: Runtime,
        http: HttpClientFactory = KtorHttp.FACTORY,
    ): Report {
        val ep = endpoint(cx.config) ?: return Report()
        val guard = runCatching { flushLock(cx.config) }.getOrElse { return lockError(cx, "flush lock", it) }
        return guard.use { run(cx, ep, http) }
    }

    /**
     * Hook-spawned flushes only (T143): a burst of `Stop`/`SessionEnd` events coalesces into at most one running +
     * one queued flush. A caller that finds the queued slot taken returns at once; the queued flush starts later and
     * still sees every row written before it. The slot is released as soon as the flush lock is held.
     */
    fun flushCoalesced(
        cx: Runtime,
        http: HttpClientFactory = KtorHttp.FACTORY,
    ): Report {
        val ep = endpoint(cx.config) ?: return Report()
        val queued = runCatching { tryQueue(cx.config) }.getOrElse { return lockError(cx, "queue lock", it) } ?: return Report()
        val guard =
            runCatching { flushLock(cx.config) }.getOrElse {
                queued.close()
                return lockError(cx, "flush lock", it)
            }
        queued.close()
        return guard.use { run(cx, ep, http) }
    }

    private fun lockError(
        cx: Runtime,
        what: String,
        e: Throwable,
    ): Report {
        val msg = "$what: ${e.message}"
        cx.log("error", "otel", "flush", msg)
        return Report(enabled = true, error = msg)
    }

    private fun run(
        cx: Runtime,
        ep: Endpoint,
        http: HttpClientFactory,
    ): Report {
        val rep = Report(enabled = true)
        val timeoutMs =
            cx.config.otel.flushSecs
                .coerceAtLeast(1u)
                .toLong() * MS_PER_SEC
        try {
            http.create(HttpSettings(timeoutMs, timeoutMs, timeoutMs)).use { client -> flushInto(cx, ep, client, rep) }
        } catch (e: Exception) {
            rep.error = e.message ?: e.toString()
        }
        rep.error?.let { cx.log("error", "otel", "flush", it) }
        return rep
    }

    private fun flushInto(
        cx: Runtime,
        ep: Endpoint,
        client: HttpClient,
        rep: Report,
    ) {
        val store = cx.store
        val res = resource(cx)
        val sessions = store.sessionsPendingExport(store.otelMark("sessions_seq"), BATCH)
        val now = rtok.log.now()
        // Stop in front of the first row still running: the mark advances to the last exported id.
        val calls = store.callsAfter(store.otelMark("calls"), BATCH).takeWhile { !inFlight(it, now) }
        if (sessions.isNotEmpty() || calls.isNotEmpty()) {
            val spans =
                sessions.map { (_, se) -> sessionSpan(se, se.hostId?.let(store::hostSlug)) } +
                    calls.map { callSpan(it, store.callDetail(it), cx.config.otel) }
            when (val r = post(client, ep, "/v1/traces", traces(res, spans))) {
                Posted.Ok -> {
                    rep.spans = spans.size
                    rep.posted++
                    calls.lastOrNull()?.let { store.otelAdvance("calls", it.id.toLong()) }
                    sessions.lastOrNull()?.let { store.otelAdvance("sessions_seq", it.first) }
                }

                Posted.NotServed -> {
                    rep.skipped += "traces"
                }

                is Posted.Failed -> {
                    rep.pushError(r.msg)
                }
            }
        }
        postLogs(cx, ep, client, res, rep)
        postMetrics(cx, ep, client, res, rep)
    }

    private fun postLogs(
        cx: Runtime,
        ep: Endpoint,
        client: HttpClient,
        res: Resource,
        rep: Report,
    ) {
        val store = cx.store
        val rows = store.logsAfter(store.otelMark("logs"), BATCH)
        if (rows.isNotEmpty()) {
            val recs = rows.map(::logRecord)
            when (val r = post(client, ep, "/v1/logs", logs(res, recs))) {
                Posted.Ok -> {
                    rep.logs = recs.size
                    rep.posted++
                    store.otelAdvance("logs", rows.last().id.toLong())
                }

                Posted.NotServed -> {
                    rep.skipped += "logs"
                }

                is Posted.Failed -> {
                    rep.pushError(r.msg)
                }
            }
        }
    }

    private fun postMetrics(
        cx: Runtime,
        ep: Endpoint,
        client: HttpClient,
        res: Resource,
        rep: Report,
    ) {
        val sums = sums(cx.store, nowNs())
        when (val r = post(client, ep, "/v1/metrics", metrics(res, sums))) {
            Posted.Ok -> {
                rep.points = sums.sumOf { it.points.size }
                rep.posted++
            }

            Posted.NotServed -> {
                rep.skipped += "metrics"
            }

            is Posted.Failed -> {
                rep.pushError(r.msg)
            }
        }
    }

    /** One stream's POST: 2xx, a 404 (no pipeline for it), or an error with its text. */
    private sealed class Posted {
        object Ok : Posted()

        object NotServed : Posted()

        class Failed(
            val msg: String,
        ) : Posted()
    }

    private fun post(
        client: HttpClient,
        ep: Endpoint,
        path: String,
        body: JsonObject,
    ): Posted {
        val req = HttpRequest("${ep.url}$path", listOf("content-type" to "application/json") + ep.headers, body.toString().encodeToByteArray())
        val resp =
            try {
                runBlocking { client.post(req) }
            } catch (e: HttpException) {
                return Posted.Failed("$path: ${e.message}")
            }
        if (resp.status == NOT_FOUND) return Posted.NotServed
        if (!resp.isSuccess) return Posted.Failed("$path: HTTP ${resp.status} ${resp.text().take(ERROR_BODY_CHARS)}".trimEnd())
        return Posted.Ok
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun nowNs(): Long =
        kotlin.time.Clock.System
            .now()
            .let { it.epochSeconds * MS_PER_SEC * NS_PER_MS + it.nanosecondsOfSecond }

    /** `rtok otel status`: endpoint, marks, what is pending, the exporter's last log line. */
    fun status(cx: Runtime): String =
        buildString {
            val ep = endpoint(cx.config)
            appendLine(if (ep != null) "endpoint: ${ep.url}" else "endpoint: none (set [otel] endpoint or OTEL_EXPORTER_OTLP_ENDPOINT)")
            val store = cx.store
            val (calls, logs) = store.otelPending()
            appendLine("calls: mark ${store.otelMark("calls")} · $calls pending")
            appendLine("logs: mark ${store.otelMark("logs")} · $logs pending")
            appendLine("sessions: mark ${store.otelMark("sessions_seq")}")
            store.lastLog("otel")?.let { appendLine("last: ${it.level} ${it.name} ${it.message}") }
        }

    /** `rtok otel status --json` (model `otel_status`): the same facts as [status]; `last` only when there is one. */
    fun statusJson(cx: Runtime): JsonObject =
        buildJsonObject {
            val store = cx.store
            val (calls, logs) = store.otelPending()
            put("endpoint", endpoint(cx.config)?.url)
            put("calls_mark", store.otelMark("calls"))
            put("calls_pending", calls)
            put("logs_mark", store.otelMark("logs"))
            put("logs_pending", logs)
            put("sessions_mark", store.otelMark("sessions_seq"))
            store.lastLog("otel")?.let { l ->
                put(
                    "last",
                    buildJsonObject {
                        put("level", l.level)
                        put("name", l.name)
                        put("message", l.message)
                    },
                )
            }
        }

    /**
     * Hooks (`Stop`, `SessionEnd`): hand the flush to a detached `rtok otel flush --coalesce` and return at once.
     * Skipped when a flush is already queued (it will post whatever this event wrote). `RTOK_HOME` names the config.
     */
    fun spawnChild(cx: Runtime) {
        if (endpoint(cx.config) == null || queuedLockHeld(cx.config)) return
        val exe = rtok.sys.currentExe() ?: return
        val env = if (cx.config.home.isNotEmpty()) mapOf("RTOK_HOME" to cx.config.home) else emptyMap()
        rtok.sys.spawnDetached(exe, listOf("otel", "flush", "--coalesce"), env, ByteArray(0))
    }
}

/**
 * T143 test hook: when `RTOK_OTEL_FLUSH_TRACE` names a directory, a hook-spawned flush marks its lifetime with
 * `<dir>/<pid>.run`, renamed to `<pid>.done` on exit, so tests count concurrent flush children without `ps`.
 */
class FlushTrace private constructor(
    private val path: String?,
) : AutoCloseable {
    override fun close() {
        path?.let { platform.posix.rename(it, it.removeSuffix(".run") + ".done") }
    }

    companion object {
        fun start(): FlushTrace {
            val dir = platform.posix.getenv("RTOK_OTEL_FLUSH_TRACE")?.toKString() ?: return FlushTrace(null)
            val path = "$dir/${platform.posix.getpid()}.run"
            runCatching { rtok.sys.writeString(path, "") }
            return FlushTrace(path)
        }
    }
}
