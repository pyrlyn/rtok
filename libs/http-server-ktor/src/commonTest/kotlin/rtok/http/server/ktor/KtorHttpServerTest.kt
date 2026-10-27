package rtok.http.server.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import rtok.http.HttpRequest
import rtok.http.HttpSettings
import rtok.http.ktor.KtorHttp
import rtok.http.server.ServerRequest
import rtok.http.server.ServerResponse
import rtok.http.server.WsHandler
import rtok.http.server.WsSocket
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ObsoleteWorkersApi::class)
class KtorHttpServerTest {
    @Test fun servesTheHandlersAnswerAndStops() {
        val bound = AtomicReference<String?>(null)
        val stop = AtomicInt(0)
        val worker = Worker.start(name = "http-server-test")
        val done =
            worker.execute(TransferMode.SAFE, { Triple(bound, stop, Unit) }) { (b, s, _) ->
                KtorHttpServer.serveBlocking(
                    "127.0.0.1",
                    0,
                    { req -> ServerResponse(201, listOf("Content-Type" to "text/plain", "X-Echo" to req.method), req.body + req.path.encodeToByteArray()) },
                    maxBody = 8,
                    stop = { s.value != 0 },
                    onBound = { b.value = it },
                )
            }
        while (bound.value == null) platform.posix.usleep(10_000u)
        KtorHttp(HttpSettings()).use { client ->
            runBlocking {
                val ok = client.post(HttpRequest("http://${bound.value}/x", body = "hi".encodeToByteArray()))
                assertEquals(201, ok.status)
                assertEquals("hi/x", ok.text())
                assertEquals(413, client.post(HttpRequest("http://${bound.value}/x", body = ByteArray(9))).status)
            }
        }
        stop.value = 1
        done.result
        worker.requestTermination().result
    }

    @Test fun websocketRoutesAreAdmittedThenEcho() {
        val bound = AtomicReference<String?>(null)
        val stop = AtomicInt(0)
        val echo =
            object : WsHandler {
                override fun admit(req: ServerRequest) = if (req.query == "ok=1") null else 401

                override suspend fun run(socket: WsSocket) {
                    while (true) socket.send("echo:" + (socket.receive() ?: break))
                }
            }
        val worker = Worker.start(name = "ws-server-test")
        val done =
            worker.execute(TransferMode.SAFE, { Triple(bound, stop, echo) }) { (b, s, e) ->
                KtorHttpServer.serveBlocking(
                    "127.0.0.1",
                    0,
                    { ServerResponse(200) },
                    stop = { s.value != 0 },
                    onBound = { b.value = it },
                    websockets = mapOf("/ws" to e),
                )
            }
        while (bound.value == null) platform.posix.usleep(10_000u)
        val client = HttpClient(CIO) { install(WebSockets) }
        runBlocking {
            client.webSocket("ws://${bound.value}/ws?ok=1") {
                send(Frame.Text("hi"))
                assertEquals("echo:hi", (incoming.receive() as Frame.Text).readText())
            }
            val refused = runCatching { client.webSocket("ws://${bound.value}/ws") { incoming.receive() } }
            assertEquals(true, refused.isFailure)
        }
        client.close()
        KtorHttp(HttpSettings()).use { c -> runBlocking { assertEquals(200, c.post(HttpRequest("http://${bound.value}/x")).status) } }
        stop.value = 1
        done.result
        worker.requestTermination().result
    }
}
