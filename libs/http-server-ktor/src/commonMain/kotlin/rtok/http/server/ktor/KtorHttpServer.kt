// The Ktor (CIO) implementation of rtok.http.server.HttpServer.
package rtok.http.server.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import rtok.http.server.BindException
import rtok.http.server.HttpServer
import rtok.http.server.RequestHandler
import rtok.http.server.ServerRequest
import rtok.http.server.ServerResponse
import rtok.http.server.WsHandler
import rtok.http.server.WsSocket

object KtorHttpServer : HttpServer {
    private const val STOP_TICK_MS = 200L
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
        // The handler owns one SQLite connection and per-process state: calls never overlap.
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
                            respond(context, lock.withLock { answer(context, handler, maxBody) })
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
        try {
            server.start(wait = false)
        } catch (e: Exception) {
            throw BindException("bind $host:$port: ${e.message}", e)
        }
        try {
            runBlocking {
                val bound = server.engine.resolvedConnectors().first()
                onBound("${bound.host}:${bound.port}")
                while (!stop()) delay(STOP_TICK_MS)
            }
        } finally {
            server.stop(0, 0)
        }
    }

    private suspend fun answer(
        call: ApplicationCall,
        handler: RequestHandler,
        maxBody: Long,
    ): ServerResponse {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > maxBody) return ServerResponse(PAYLOAD_TOO_LARGE)
        val body = call.receiveChannel().readRemaining(maxBody + 1).readByteArray()
        if (body.size > maxBody) return ServerResponse(PAYLOAD_TOO_LARGE)
        return handler.handle(request(call, body))
    }

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
                type = ContentType.parse(value)
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
