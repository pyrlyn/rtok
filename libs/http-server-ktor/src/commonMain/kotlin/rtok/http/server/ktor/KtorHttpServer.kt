// The Ktor (CIO) implementation of rtok.http.server.HttpServer.
package rtok.http.server.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.discard
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import platform.posix.SIGINT
import platform.posix.SIGPIPE
import platform.posix.SIGTERM
import platform.posix.SIG_IGN
import platform.posix.signal
import rtok.http.server.BindException
import rtok.http.server.BodySink
import rtok.http.server.HttpServer
import rtok.http.server.RequestHandler
import rtok.http.server.ServerRequest
import rtok.http.server.ServerResponse
import rtok.http.server.StreamingHandler
import rtok.http.server.StreamingHttpServer
import rtok.http.server.StreamingResponse
import rtok.http.server.WsHandler
import rtok.http.server.WsSocket
import kotlin.concurrent.AtomicInt

/** The sink of a response whose client is gone before the body started: every write fails. */
private val ABANDONED = BodySink { throw IllegalStateException("response abandoned before its body") }

object KtorHttpServer : HttpServer, StreamingHttpServer {
    private const val STOP_TICK_MS = 200L

    /** Largest refused body still read off the socket before the 413 (see [drainRefused]). */
    private const val DRAIN_LIMIT = 1L shl 20
    private const val PAYLOAD_TOO_LARGE = 413

    override fun serveBlocking(
        host: String,
        port: Int,
        handler: RequestHandler,
        maxBody: Long,
        stop: () -> Boolean,
        websockets: Map<String, WsHandler>,
        onBound: (String) -> Unit,
    ) {
        // The handler owns one SQLite connection and per-process state: calls never overlap. The lock is taken only
        // around the handler, after the body is in: a client that stalls mid-upload must not hold up the others.
        val lock = Mutex()
        val server =
            embeddedServer(CIO, port = port, host = host) {
                if (websockets.isNotEmpty()) install(WebSockets)
                // Before routing: plain requests are answered here; a WebSocket path is admitted (or refused)
                // under the same lock, then proceeds to its `webSocket` route.
                intercept(ApplicationCallPipeline.Plugins) {
                    val ws = websockets[context.request.path()]
                    val refused = ws?.let { h -> lock.withLock { h.admit(request(context, ByteArray(0))) } }
                    when {
                        ws == null -> {
                            val req = receive(context, maxBody)
                            respond(context, if (req == null) ServerResponse(PAYLOAD_TOO_LARGE) else lock.withLock { handler.handle(req) })
                            finish()
                        }

                        refused != null -> {
                            respond(context, ServerResponse(refused))
                            finish()
                        }
                    }
                }
                routing {
                    for ((path, h) in websockets) {
                        webSocket(path) { h.run(KtorWsSocket(this)) }
                    }
                }
            }
        run(server, host, port, stop, onBound)
    }

    override fun serveStreaming(
        host: String,
        port: Int,
        handler: StreamingHandler,
        maxBody: Long,
        stop: () -> Boolean,
        onBound: (String) -> Unit,
    ) {
        val server =
            embeddedServer(CIO, port = port, host = host) {
                intercept(ApplicationCallPipeline.Plugins) {
                    val res = receive(context, maxBody)?.let { handler.handle(it, context.request.local.remoteAddress) } ?: StreamingResponse(PAYLOAD_TOO_LARGE)
                    stream(context, res)
                    finish()
                }
            }
        run(server, host, port, stop, onBound)
    }

    private fun run(
        server: io.ktor.server.engine.EmbeddedServer<*, *>,
        host: String,
        port: Int,
        stop: () -> Boolean,
        onBound: (String) -> Unit,
    ) {
        // Ktor's own SIGINT/SIGTERM hook calls server.stop() inside the signal handler. On Linux the signal lands
        // on this thread, which is parked in the loop below and is needed for the shutdown, so the process hangs.
        // Replace it with a handler that only raises a flag; the loop sees it and stops the server normally.
        val running = Job()
        server.monitor.subscribe(ApplicationStopped) { running.complete() }
        try {
            server.start(wait = false)
        } catch (e: Exception) {
            throw BindException("bind $host:$port: ${e.message}", e)
        }
        installStopSignals()
        try {
            runBlocking {
                val bound = server.engine.resolvedConnectors().first()
                onBound("${bound.host}:${bound.port}")
                while (running.isActive && !stop() && stopSignal.value == 0) delay(STOP_TICK_MS)
            }
        } finally {
            if (running.isActive) server.stop(0, 0)
        }
    }

    /**
     * Reads and drops a refused body of at most [DRAIN_LIMIT] bytes. Closing a socket with unread input makes
     * Linux send a reset, so the client sees a broken connection instead of the 413.
     */
    private suspend fun drainRefused(
        call: ApplicationCall,
        declared: Long,
    ) {
        if (declared <= DRAIN_LIMIT) call.receiveChannel().discard(declared)
    }

    /** The request with its body, or null when the body is over [maxBody]. */
    private suspend fun receive(
        call: ApplicationCall,
        maxBody: Long,
    ): ServerRequest? {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > maxBody) {
            drainRefused(call, declared)
            return null
        }
        val body = call.receiveChannel().readRemaining(maxBody + 1).readByteArray()
        return body?.takeIf { it.size <= maxBody }?.let { request(call, it) }
    }

    private suspend fun stream(
        call: ApplicationCall,
        res: StreamingResponse,
    ) {
        var type: ContentType? = null
        for ((name, value) in res.headers) {
            if (name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                type = contentType(value)
            } else if (!name.equals(HttpHeaders.ContentLength, ignoreCase = true) && !name.equals(HttpHeaders.TransferEncoding, ignoreCase = true)) {
                call.response.header(name, value)
            }
        }
        var started = false
        try {
            call.respondBytesWriter(type, HttpStatusCode.fromValue(res.status)) {
                started = true
                val channel = this
                res.body(
                    BodySink { bytes ->
                        channel.writeFully(bytes)
                        channel.flush()
                    },
                )
            }
        } finally {
            // A body that never ran still owns whatever feeds it (an upstream stream, a queue slot): it is run against
            // a sink that refuses every write, so it sees the client gone and lets go instead of waiting forever.
            if (!started) runCatching { res.body(ABANDONED) }
        }
    }

    /** A header value from a handler or an upstream may be malformed: it is dropped rather than failing the call. */
    private fun contentType(value: String): ContentType? = runCatching { ContentType.parse(value) }.getOrNull()

    private fun request(
        call: ApplicationCall,
        body: ByteArray,
    ): ServerRequest {
        val headers =
            call.request.headers
                .entries()
                .groupBy({ it.key.lowercase() }, { it.value })
                .mapValues { (_, v) -> v.flatten() }
        return ServerRequest(call.request.httpMethod.value, call.request.path(), headers, body, call.request.queryString().ifEmpty { null })
    }

    private suspend fun respond(
        call: ApplicationCall,
        res: ServerResponse,
    ) {
        var type: ContentType? = null
        for ((name, value) in res.headers) {
            if (name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                type = contentType(value)
            } else {
                call.response.header(name, value)
            }
        }
        call.respondBytes(res.body, type, HttpStatusCode.fromValue(res.status))
    }
}

private class KtorWsSocket(
    private val session: io.ktor.server.websocket.DefaultWebSocketServerSession,
) : WsSocket {
    override suspend fun send(text: String): Boolean = runCatching { session.send(Frame.Text(text)) }.isSuccess

    override suspend fun receive(): String? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) return frame.readText()
        }
    }
}

/** Set by SIGINT/SIGTERM; only an atomic store happens inside the handler. */
private val stopSignal = AtomicInt(0)

@OptIn(ExperimentalForeignApi::class)
private fun installStopSignals() {
    stopSignal.value = 0
    val handler = staticCFunction<Int, Unit> { stopSignal.value = 1 }
    signal(SIGINT, handler)
    signal(SIGTERM, handler)
    // A client that hangs up mid-response must fail that write, not end the server for every other client.
    signal(SIGPIPE, SIG_IGN)
}
