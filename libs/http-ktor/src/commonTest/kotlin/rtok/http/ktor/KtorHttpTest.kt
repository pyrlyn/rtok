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

    @Test fun aGetSendsItsHeadersAndReturnsTheBytes() =
        runBlocking {
            var seen: io.ktor.client.request.HttpRequestData? = null
            client { req ->
                seen = req
                respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
            }.use { c ->
                val r = c.get(HttpRequest("http://x/crate/serde/1.0.0/json.zst", listOf("user-agent" to "rtok/t")))
                assertEquals(200, r.status)
                assertContentEquals(byteArrayOf(1, 2, 3), r.body)
            }
            assertEquals(HttpMethod.Get, seen!!.method)
            assertEquals("rtok/t", seen!!.headers["User-Agent"])
        }

    @Test fun anyMethodSendsItsBodyAndReturnsTheResponseHeaders() =
        runBlocking {
            var seen: io.ktor.client.request.HttpRequestData? = null
            client { req ->
                seen = req
                respond("{}", HttpStatusCode.OK, headersOf("Link" to listOf("<http://x/l?page=2>; rel=\"next\""), "X-RateLimit-Remaining" to listOf("9")))
            }.use { c ->
                val r = c.request("patch", HttpRequest("http://x/issues/1", listOf("content-type" to "application/json"), "{\"a\":1}".encodeToByteArray()))
                assertEquals("<http://x/l?page=2>; rel=\"next\"", r.header("link"))
                assertEquals("9", r.header("x-ratelimit-remaining"))
            }
            assertEquals(HttpMethod.Patch, seen!!.method)
            assertEquals("{\"a\":1}", (seen!!.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString())
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
