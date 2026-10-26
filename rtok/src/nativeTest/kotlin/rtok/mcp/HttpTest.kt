package rtok.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.http.HttpRequest
import rtok.http.HttpSettings
import rtok.http.ktor.KtorHttp
import rtok.http.server.ServerRequest
import rtok.store.putArchive
import rtok.support.tmpDir
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpTest {
    private val token = "0123456789abcdef"

    private fun cfg(tag: String) = Config().rebasePaths(tmpDir(tag))

    private fun expand(
        server: Server,
        id: String,
    ) = server.callTool("expand", buildJsonObject { put("id", id) })

    private fun isError(r: kotlinx.serialization.json.JsonObject) = (r["isError"] as? JsonPrimitive)?.content == "true"

    @Test fun httpExpandRefusesForeignArchivesButKeepsItsOwnLossless() {
        val cfg = cfg("http-archive-scope")
        val body = "private output from another session".encodeToByteArray()
        Http.httpServer(cfg).use { server ->
            val id = server.cx.store.putArchive("other-session", body, cfg.core.archiveDir)
            for (supplied in listOf(id.take(8), id)) {
                val r = expand(server, supplied)
                assertTrue(isError(r), r.toString())
                assertFalse("private output" in r.toString(), r.toString())
            }
            // The store keeps the first writer of identical bytes; a session filter would reject a real pointer.
            assertEquals(id, server.cx.putArchive(body))
            for (supplied in listOf(id.take(8), id)) {
                val r = expand(server, supplied)
                assertFalse(isError(r), r.toString())
                assertTrue("private output" in r.toString())
            }
            Http.httpServer(cfg).use { restarted -> assertTrue(isError(expand(restarted, id))) }
            Server.open(cfg, "stdio").use { stdio -> assertFalse(isError(expand(stdio, id))) }
        }
    }

    @Test fun httpExpandResolvesCollisionsOnlyAmongGrantedIds() {
        val cfg = cfg("http-archive-collision")
        Http.httpServer(cfg).use { server ->
            // Fixed SHA-256 fixtures collide in the eight hex characters printed in trailers.
            val first = "rtok-t960-collision-22965".encodeToByteArray()
            val second = "rtok-t960-collision-44844".encodeToByteArray()
            val own = server.cx.putArchiveFor(first, "context")
            val foreign = server.cx.store.putArchive("other", second, cfg.core.archiveDir)
            assertEquals(own.take(8), foreign.take(8))
            val prefix = own.take(8)
            val expanded = expand(server, prefix)
            assertFalse(isError(expanded), expanded.toString())
            assertTrue("collision-22965" in expanded.toString())
            assertEquals(foreign, server.cx.putArchive(second))
            val ambiguous = expand(server, prefix)
            assertTrue(isError(ambiguous))
            val message = ambiguous.toString()
            assertTrue("ambiguous" in message, message)
            for (id in listOf(own, foreign)) {
                assertFalse(id.take(12) in message, message)
                assertFalse(isError(expand(server, id.take(12))))
            }
            for (invalid in listOf("", own.take(7), "../../archive", "${own}0")) assertTrue(isError(expand(server, invalid)), invalid)
        }
    }

    @Test fun bearerNeedsTheExactToken() {
        assertTrue(Http.bearerMatches("Bearer $token", token))
        assertTrue(Http.bearerMatches("bearer $token", token))
        assertFalse(Http.bearerMatches("Bearer 0123456789abcde", token))
        assertFalse(Http.bearerMatches("Bearer ${token}g", token))
        assertFalse(Http.bearerMatches("Basic $token", token))
        assertFalse(Http.bearerMatches(token, token))
        assertFalse(Http.bearerMatches(null, token))
    }

    @Test fun exposureGuardRefusesHomeRootAndPlainRemoteBinds() {
        val home = tmpDir("http-home")
        val project = "$home/src/app".also { rtok.sys.createDirAll(it) }
        val lo = Http.Addr.parse("127.0.0.1:8791")
        val wide = Http.Addr.parse("0.0.0.0:8791")
        assertFailsWith<Http.HttpSetupException> { Http.exposureGuard(home, home, lo, null) }
        assertFailsWith<Http.HttpSetupException> { Http.exposureGuard("/", home, lo, null) }
        Http.exposureGuard(project, home, lo, null)
        assertFailsWith<Http.HttpSetupException> { Http.exposureGuard(project, home, wide, null) }
        assertFailsWith<Http.HttpSetupException> { Http.exposureGuard(project, home, wide, Http.PublicUrl.parse("http://example.com/mcp")) }
        Http.exposureGuard(project, home, wide, Http.PublicUrl.parse("https://example.com/mcp"))
    }

    @Test fun shortOrBlankTokensRefuseToStart() {
        for (bad in listOf("", "short", "has a space in it ok")) assertFailsWith<Http.HttpSetupException> { Http.bearerToken(bad) }
        assertEquals(token, Http.bearerToken(token))
    }

    @Test fun publicUrlAddsOnlyItsOwnHostAndOrigin() {
        val public = Http.PublicUrl.parse("https://abc.trycloudflare.com/mcp")
        val c = Http.transport(Http.Addr.parse("127.0.0.1:8791"), public)
        assertTrue("abc.trycloudflare.com" in c.allowedHosts)
        assertEquals(listOf("https://abc.trycloudflare.com"), c.allowedOrigins)
        val wide = Http.transport(Http.Addr.parse("0.0.0.0:8791"), null)
        assertEquals(Http.LOOPBACK_HOSTS, wide.allowedHosts)
        assertTrue(wide.allowedOrigins.isEmpty())
        assertFailsWith<Http.HttpSetupException> { Http.PublicUrl.parse("ftp://x") }
        assertNull(Http.PublicUrl.parse(""))
        assertEquals("https://h.example:8443", Http.PublicUrl.parse("https://H.example:8443/x")?.origin)
    }

    @Test fun addrsParseLikeASocketAddr() {
        assertEquals(Http.Addr("::1", 9, true), Http.Addr.parse("[::1]:9"))
        assertTrue(Http.Addr.parse("[::1]:9").isLoopback)
        for (bad in listOf("localhost:80", "127.0.0.1", "127.0.0.1:x", "1.2.3:4", "01.2.3.4:5", "[::1]")) {
            assertFailsWith<Http.HttpSetupException>(bad) { Http.Addr.parse(bad) }
        }
    }

    private fun post(
        body: String,
        vararg headers: Pair<String, String>,
    ): ServerRequest {
        val base =
            mapOf(
                "authorization" to "Bearer $token",
                "host" to "127.0.0.1:8791",
                "accept" to "application/json, text/event-stream",
                "content-type" to "application/json",
            ) + headers.toMap()
        return ServerRequest("POST", Http.PATH, base.filterValues { it.isNotEmpty() }.mapValues { listOf(it.value) }, body.encodeToByteArray())
    }

    @Test fun theEndpointGatesTokenHostOriginMethodAndHeaders() {
        val cfg = cfg("http-endpoint")
        Http.httpServer(cfg).use { server ->
            val ep = Http.Endpoint(server, token, Http.transport(Http.Addr.parse("127.0.0.1:8791"), null))
            val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
            assertEquals(401, ep.handle(post(ping, "authorization" to "")).status)
            assertEquals(401, ep.handle(post(ping, "authorization" to "Bearer nope")).status)
            assertEquals(403, ep.handle(post(ping, "host" to "evil.example")).status)
            assertEquals(403, ep.handle(post(ping, "origin" to "https://evil.example")).status)
            assertEquals(405, ep.handle(ServerRequest("GET", Http.PATH, post(ping).headers, ByteArray(0))).status)
            assertEquals(404, ep.handle(ServerRequest("POST", "/other", post(ping).headers, ByteArray(0))).status)
            assertEquals(406, ep.handle(post(ping, "accept" to "application/json")).status)
            assertEquals(415, ep.handle(post(ping, "content-type" to "text/plain")).status)
            assertEquals(400, ep.handle(post(ping, "mcp-protocol-version" to "1999-01-01")).status)
            assertEquals(202, ep.handle(post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")).status)
            val ok = ep.handle(post(ping, "mcp-protocol-version" to "2025-06-18"))
            assertEquals(200, ok.status)
            assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", ok.body.decodeToString())
            val list = ep.handle(post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")).body.decodeToString()
            for (bound in Http.AGENT_BOUND) assertFalse("\"name\":\"$bound\"" in list, bound)
            assertTrue("\"name\":\"expand\"" in list, list)
        }
    }

    @OptIn(ObsoleteWorkersApi::class)
    @Test
    fun servesInitializeAndToolsOverARealSocket() {
        val dir = tmpDir("http-live")
        val cfg = Config().rebasePaths(dir).let { it.copy(mcp = it.mcp.copy(token = token)) }
        val bound = AtomicReference<String?>(null)
        val stop = AtomicInt(0)
        val worker = Worker.start(name = "mcp-http-test")
        val done =
            worker.execute(TransferMode.SAFE, { Triple(cfg, bound, stop) }) { (c, b, s) ->
                kotlin.runCatching { Http.serveBlocking(c, "127.0.0.1:0", stop = { s.value != 0 }) { b.value = it } }.exceptionOrNull()?.message
            }
        while (bound.value == null && done.state == FutureState.SCHEDULED) platform.posix.usleep(10_000u)
        try {
            KtorHttp(HttpSettings()).use { client ->
                runBlocking {
                    val headers = listOf("Authorization" to "Bearer $token", "Accept" to "application/json, text/event-stream", "Content-Type" to "application/json")
                    val init = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
                    val r = client.post(HttpRequest("http://${bound.value}${Http.PATH}", headers, init.encodeToByteArray()))
                    assertEquals(200, r.status, r.text())
                    assertTrue("\"protocolVersion\":\"2025-06-18\"" in r.text(), r.text())
                    val denied = client.post(HttpRequest("http://${bound.value}${Http.PATH}", headers.drop(1), init.encodeToByteArray()))
                    assertEquals(401, denied.status)
                }
            }
        } finally {
            stop.value = 1
            assertNull(done.result)
            worker.requestTermination().result
            rtok.plugins.read.Roots
                .reset()
        }
    }
}
