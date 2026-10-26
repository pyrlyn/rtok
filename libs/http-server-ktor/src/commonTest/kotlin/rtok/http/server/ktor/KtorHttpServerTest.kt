package rtok.http.server.ktor

import kotlinx.coroutines.runBlocking
import rtok.http.HttpRequest
import rtok.http.HttpSettings
import rtok.http.ktor.KtorHttp
import rtok.http.server.ServerResponse
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
}
