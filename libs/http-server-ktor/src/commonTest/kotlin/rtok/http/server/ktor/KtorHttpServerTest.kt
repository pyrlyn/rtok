package rtok.http.server.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.async
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

/** The additive streaming APIs: concurrent handler, chunked body, streaming client without redirects. */
@OptIn(ObsoleteWorkersApi::class)
class KtorStreamingTest {
    private fun <T> serving(
        handler: rtok.http.server.StreamingHandler,
        body: suspend (String) -> T,
    ): T {
        val bound = AtomicReference<String?>(null)
        val stop = AtomicInt(0)
        val worker = Worker.start(name = "stream-server-test")
        val done =
            worker.execute(TransferMode.SAFE, { Triple(bound, stop, handler) }) { (b, s, h) ->
                KtorHttpServer.serveStreaming("127.0.0.1", 0, h, maxBody = 8, stop = { s.value != 0 }, onBound = { b.value = it })
            }
        while (bound.value == null) platform.posix.usleep(10_000u)
        try {
            return runBlocking { body(bound.value!!) }
        } finally {
            stop.value = 1
            done.result
            worker.requestTermination().result
        }
    }

    @Test fun chunksArriveInOrderAndCallsOverlap() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val handler =
            rtok.http.server.StreamingHandler { req, peer ->
                if (req.path == "/release") {
                    gate.complete(Unit)
                    rtok.http.server.StreamingResponse(200, listOf("Content-Type" to "text/plain"), { it.write("ok:$peer".encodeToByteArray()) })
                } else {
                    rtok.http.server.StreamingResponse(200, listOf("Content-Type" to "text/event-stream", "X-Lane" to "bulk")) { sink ->
                        sink.write("data: 1\n\n".encodeToByteArray())
                        gate.await() // only a second, concurrent call can release this one
                        sink.write("data: 2\n\n".encodeToByteArray())
                    }
                }
            }
        serving(handler) { host ->
            KtorHttp(HttpSettings()).use { client ->
                kotlinx.coroutines.coroutineScope {
                    val chunks = mutableListOf<String>()
                    val streamed =
                        async {
                            client.stream("GET", HttpRequest("http://$host/sse")) { r ->
                                assertEquals("bulk", r.header("x-lane"))
                                while (true) chunks += (r.read() ?: break).decodeToString()
                                r.status
                            }
                        }
                    val release = client.request("POST", HttpRequest("http://$host/release"))
                    assertEquals("ok:127.0.0.1", release.text())
                    assertEquals(200, streamed.await())
                    assertEquals("data: 1\n\ndata: 2\n\n", chunks.joinToString(""))
                }
            }
        }
    }

    @Test fun anOversizedBodyIs413AndRedirectsAreNotFollowed() {
        val handler = rtok.http.server.StreamingHandler { _, _ -> rtok.http.server.StreamingResponse(302, listOf("Location" to "/elsewhere")) }
        serving(handler) { host ->
            KtorHttp(HttpSettings()).use { client ->
                assertEquals(413, client.stream("PUT", HttpRequest("http://$host/x", body = ByteArray(9))) { it.status })
                assertEquals("/elsewhere", client.stream("DELETE", HttpRequest("http://$host/x")) { it.header("location") })
            }
        }
    }
}
