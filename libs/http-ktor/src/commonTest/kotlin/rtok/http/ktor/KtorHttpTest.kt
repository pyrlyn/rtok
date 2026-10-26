package rtok.http.ktor

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import rtok.http.HttpException
import rtok.http.HttpRequest
import rtok.http.HttpSettings
import kotlin.test.*

private fun client(
    settings: HttpSettings = HttpSettings(),
    handler: MockRequestHandler,
) = KtorHttp(settings, MockEngine(handler))

class KtorHttpTest {
    @Test fun headersIncludingAuthAndTheBodyReachTheServerUnchanged() =
        runBlocking {
            var seen: io.ktor.client.request.HttpRequestData? = null
            var body = ""
            val json = """{"model":"m","messages":[{"role":"user","content":"hi"}]}"""
            client { req ->
                seen = req
                body = (req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                respond("""{"content":[{"type":"text","text":"ok"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }.use { c ->
                val r = c.post(HttpRequest("http://127.0.0.1:1/v1/messages", listOf("content-type" to "application/json", "x-api-key" to "k-test", "authorization" to "Bearer t", "x-rtok-lane" to "internal"), json.encodeToByteArray()))
                assertTrue(r.isSuccess)
                assertEquals(
                    "ok",
                    Json
                        .parseToJsonElement(r.text())
                        .jsonObject["content"]!!
                        .jsonArray[0]
                        .jsonObject["text"]!!
                        .jsonPrimitive.content,
                )
            }
            val h = seen!!.headers
            assertEquals(HttpMethod.Post, seen!!.method)
            assertEquals("/v1/messages", seen!!.url.encodedPath)
            assertEquals("k-test", h["x-api-key"])
            assertEquals("Bearer t", h["Authorization"])
            assertEquals("internal", h["x-rtok-lane"])
            assertEquals(json, body)
        }

    @Test fun aNonSuccessStatusIsAResponseNotAnException() =
        runBlocking {
            client { respond("overloaded", HttpStatusCode.ServiceUnavailable) }.use { c ->
                val r = c.post(HttpRequest("http://x/v1/messages"))
                assertEquals(503, r.status)
                assertFalse(r.isSuccess)
                assertEquals("overloaded", r.text())
            }
        }

    @Test fun aSlowServerMapsToRequestTimeout() =
        runBlocking {
            client(HttpSettings(requestTimeoutMs = 50)) {
                delay(2_000)
                respond("late")
            }.use { c ->
                val e = assertFailsWith<HttpException> { c.post(HttpRequest("http://x/")) }
                assertEquals(HttpException.Kind.RequestTimeout, e.kind)
            }
        }

    @Test fun anEngineFailureMapsToIo() =
        runBlocking {
            client { throw IllegalStateException("connection refused") }.use { c ->
                val e = assertFailsWith<HttpException> { c.post(HttpRequest("http://x/")) }
                assertEquals(HttpException.Kind.Io, e.kind)
                assertTrue("connection refused" in e.message!!)
            }
        }
}
