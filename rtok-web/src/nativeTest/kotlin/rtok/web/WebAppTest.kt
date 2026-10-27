// Port of src/web/mod.rs and src/web/spa.rs tests: origin guard, /health, /auth, SPA routing and headers.
package rtok.web

import rtok.http.server.ServerRequest
import rtok.http.server.ServerResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MapAssets(
    val files: Map<String, String>,
) : Assets {
    override fun read(name: String) = files[name]?.let { AssetFile(it.encodeToByteArray(), "\"h-$name\"") }
}

private fun get(
    path: String,
    headers: Map<String, String> = emptyMap(),
    query: String? = null,
    method: String = "GET",
) = ServerRequest(method, path, headers.mapKeys { it.key.lowercase() }.mapValues { listOf(it.value) }, ByteArray(0), query)

private fun ServerResponse.header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

private const val OK = 200
private const val NO_CONTENT = 204
private const val NOT_MODIFIED = 304
private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val NOT_ALLOWED = 405
private const val UNAVAILABLE = 503
private const val PORT = 4321

private val ASSETS =
    MapAssets(
        mapOf(
            "index.html" to "<html>ui</html>",
            "assets/app.js" to "js",
            "assets/app.js.br" to "br-js",
            "favicon.svg" to "<svg/>",
        ),
    )

class WebAppTest {
    private val app = WebApp(BoundAddr("127.0.0.1", PORT), ASSETS, "secret")

    @Test
    fun originGuardMatchesRust() {
        assertTrue(originAllowed(null, "127.0.0.1:3333"))
        assertTrue(originAllowed("http://127.0.0.1:3333", "127.0.0.1:3333"))
        assertTrue(originAllowed("http://localhost:3333", "localhost:3333"))
        assertTrue(originAllowed("http://[::1]:3333", "[::1]:3333"))
        assertTrue(originAllowed("http://localhost", "localhost:80"))
        assertFalse(originAllowed("http://localhost:4000", "localhost:3333"))
        assertFalse(originAllowed("http://evil.example:3333", "evil.example:3333"))
        assertFalse(originAllowed("http://evil.example", "127.0.0.1"))
        assertFalse(originAllowed("http://127.0.0.1:3333", null))
        assertFalse(originAllowed("http://127.0.0.1:3333", "127.0.0.1:3333, evil"))
        assertFalse(originAllowed("https://localhost", "localhost:80"))
        assertTrue(originAllowed("http://app.localhost:3333", "app.localhost:3333"))
    }

    @Test
    fun healthReportsTheBoundAddress() {
        val res = app.handle(get("/health"))
        assertEquals(OK, res.status)
        assertEquals("""{"host":"127.0.0.1","ok":true,"port":4321}""", res.body.decodeToString())
    }

    @Test
    fun authProbeNeedsTheTokenThenTheOrigin() {
        assertEquals(UNAUTHORIZED, app.handle(get("/auth")).status)
        assertEquals(UNAUTHORIZED, app.handle(get("/auth", query = "token=nope")).status)
        assertEquals(NO_CONTENT, app.handle(get("/auth", query = "token=secret")).status)
        assertEquals(NO_CONTENT, app.handle(get("/auth", mapOf("Authorization" to "Bearer secret"))).status)
        val cross = mapOf("Authorization" to "Bearer secret", "Origin" to "http://evil.example", "Host" to "127.0.0.1:4321")
        assertEquals(FORBIDDEN, app.handle(get("/auth", cross)).status)
    }

    @Test
    fun spaRoutesFallBackToIndexButFilesAndApiDoNot() {
        val page = app.handle(get("/sessions/today"))
        assertEquals(OK, page.status)
        assertEquals("<html>ui</html>", page.body.decodeToString())
        assertEquals("no-cache", page.header("cache-control"))
        assertEquals("text/html; charset=utf-8", page.header("content-type"))
        assertEquals(NOT_FOUND, app.handle(get("/missing.js")).status)
        assertEquals(NOT_FOUND, app.handle(get("/api/x")).status)
        assertEquals(NOT_ALLOWED, app.handle(get("/", method = "POST")).status)
        assertEquals(BAD_REQUEST, app.handle(get("/%zz")).status)
        assertEquals(BAD_REQUEST, app.handle(get("/a%2Fb")).status)
    }

    @Test
    fun assetsArePrecompressedImmutableAndSecured() {
        val js = app.handle(get("/assets/app.js", mapOf("Accept-Encoding" to "gzip, br")))
        assertEquals("br-js", js.body.decodeToString())
        assertEquals("br", js.header("content-encoding"))
        assertEquals("public, max-age=31536000, immutable", js.header("cache-control"))
        assertEquals("nosniff", js.header("x-content-type-options"))
        assertEquals("no-referrer", js.header("referrer-policy"))
        assertTrue(js.header("content-security-policy")!!.startsWith("default-src 'self'"))
        val plain = app.handle(get("/assets/app.js", mapOf("Accept-Encoding" to "br;q=0")))
        assertEquals("js", plain.body.decodeToString())
        assertNull(plain.header("content-encoding"))
        assertEquals(NOT_FOUND, app.handle(get("/assets/app.js.br")).status)
    }

    @Test
    fun etagRevalidation() {
        val res = app.handle(get("/favicon.svg", mapOf("If-None-Match" to "W/\"h-favicon.svg\"")))
        assertEquals(NOT_MODIFIED, res.status)
        assertEquals(0, res.body.size)
    }

    @Test
    fun noUiAnswers503OnPageRoutes() {
        val bare = WebApp(BoundAddr("127.0.0.1", 1), NoAssets, null)
        assertEquals(UNAVAILABLE, bare.handle(get("/")).status)
        assertEquals(NOT_FOUND, bare.handle(get("/x.css")).status)
        assertTrue(resolveAssets(null, NoAssets).second!!.contains(DIST_ENV))
    }

    @Test
    fun helpers() {
        assertTrue(accepts(listOf("GZIP;q=0.5"), "gzip"))
        assertFalse(accepts(emptyList(), "gzip"))
        assertTrue(isPageRoute(""))
        assertFalse(isPageRoute("ws"))
        assertEquals("image/svg+xml", mimeOf("a/b.svg"))
        assertEquals("application/octet-stream", mimeOf("LICENSE"))
    }
}
