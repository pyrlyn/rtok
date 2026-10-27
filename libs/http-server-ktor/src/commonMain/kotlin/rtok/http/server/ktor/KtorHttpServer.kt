// The Ktor (CIO) implementation of rtok.http.server.HttpServer.
package rtok.http.server.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.readRemaining
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

object KtorHttpServer : HttpServer {
    private const val STOP_TICK_MS = 200L
    private const val PAYLOAD_TOO_LARGE = 413

    override fun serveBlocking(
        host: String,
        port: Int,
        handler: RequestHandler,
        maxBody: Long,
        stop: () -> Boolean,
        onBound: (String) -> Unit,
    ) {
        // The handler owns one SQLite connection and per-process state: calls never overlap.
        val lock = Mutex()
        val server =
            embeddedServer(CIO, port = port, host = host) {
                intercept(ApplicationCallPipeline.Call) {
                    val res = lock.withLock { answer(context, handler, maxBody) }
                    respond(context, res)
                    finish()
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
        val headers =
            call.request.headers
                .entries()
                .groupBy({ it.key.lowercase() }, { it.value })
                .mapValues { (_, v) -> v.flatten() }
        return handler.handle(ServerRequest(call.request.httpMethod.value, call.request.path(), headers, body, call.request.queryString().ifEmpty { null }))
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
