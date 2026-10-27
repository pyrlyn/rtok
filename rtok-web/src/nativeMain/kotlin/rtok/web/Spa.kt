// Port of src/web/spa.rs: the SPA's static files with SPA routing, precompressed variants, caching and
// security headers. The source is an [Assets]: a built directory (`RTOK_WEB_DIST`) or the embedded UI.
package rtok.web

import rtok.http.server.ServerRequest
import rtok.http.server.ServerResponse
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.readBytes

const val DIST_ENV = "RTOK_WEB_DIST"

// 'wasm-unsafe-eval' lets the Compose UI compile its .wasm modules; scripts still load only from 'self'.
private const val CSP =
    "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; " +
        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
private const val IMMUTABLE = "public, max-age=31536000, immutable"
private const val REVALIDATE = "no-cache"
private val RESERVED = setOf("ws", "health", "auth", "api", "assets")

/** One file of the UI; [etag] is set for embedded files (their content hash). */
class AssetFile(
    val bytes: ByteArray,
    val etag: String? = null,
)

/** Where the UI's files come from. */
fun interface Assets {
    fun read(name: String): AssetFile?

    /** True when this source has no real UI (a build without the frontend). */
    fun placeholder(): Boolean = false
}

/** A built UI directory on disk. Only plain relative names are read: no `..`, no absolute paths. */
class DirAssets(
    private val root: String,
) : Assets {
    override fun read(name: String): AssetFile? {
        val plain = name.split('/').none { it.isEmpty() || it == "." || it == ".." }
        val path = join(root, name)
        return if (plain && isFile(path)) runCatching { AssetFile(readBytes(path)) }.getOrNull() else null
    }
}

/** No UI files at all; every page route answers 503 with how to fix it. */
object NoAssets : Assets {
    override fun read(name: String): AssetFile? = null

    override fun placeholder() = true
}

/**
 * The source `rtok web` serves: [dist] when it holds an `index.html`, else [embedded]. The notice, when not
 * null, goes to stderr and the log.
 */
fun resolveAssets(
    dist: String?,
    embedded: Assets,
): Pair<Assets, String?> =
    when {
        dist != null && isFile(join(dist, "index.html")) -> {
            DirAssets(dist) to null
        }

        dist != null -> {
            embedded to "rtok web: $DIST_ENV=$dist has no index.html; serving the embedded UI instead"
        }

        embedded.placeholder() -> {
            embedded to
                "rtok web: this binary was built without the SPA (web/dist was missing), so the UI cannot load " +
                "(the API and /ws are up). Point $DIST_ENV at a built dist/ directory."
        }

        else -> {
            embedded to null
        }
    }

/** Percent-decodes each segment; a malformed escape or a decoded `/`, `\` or NUL is a 400. */
internal fun decodePath(path: String): String? =
    path
        .split('/')
        .map { seg -> decodeSegment(seg) ?: return null }
        .joinToString("/")

private const val HEX = 16
private const val ESCAPE_LEN = 3

private fun decodeSegment(seg: String): String? {
    val out = ArrayList<Byte>(seg.length)
    var i = 0
    var ok = true
    while (ok && i < seg.length) {
        if (seg[i] == '%') {
            val b = seg.takeIf { i + ESCAPE_LEN <= it.length }?.substring(i + 1, i + ESCAPE_LEN)?.toIntOrNull(HEX)
            ok = b != null
            b?.let { out.add(it.toByte()) }
            i += ESCAPE_LEN
        } else {
            out.addAll(seg[i].toString().encodeToByteArray().toList())
            i++
        }
    }
    val bytes = out.toByteArray()
    val decoded = bytes.decodeToString()
    val valid = ok && decoded.encodeToByteArray().contentEquals(bytes) && decoded.none { it == '/' || it == '\\' || it == '\u0000' }
    return decoded.takeIf { valid }
}

/** `Accept-Encoding` lists [coding] with a non-zero q. */
internal fun accepts(
    values: List<String>,
    coding: String,
): Boolean =
    values.flatMap { it.split(',') }.any { item ->
        val parts = item.split(';')
        val name = parts[0].trim()
        val q = parts.drop(1).firstNotNullOfOrNull { it.trim().removePrefix("q=").takeIf { r -> r.length != it.trim().length } }
        val qv = q?.trim()?.toFloatOrNull() ?: 1.0f
        name.equals(coding, ignoreCase = true) && qv > 0f
    }

/** A client-side route of the SPA: not an API path and not a file name. */
internal fun isPageRoute(path: String): Boolean {
    val first = path.substringBefore('/')
    val last = path.substringAfterLast('/')
    return first !in RESERVED && !last.contains('.')
}

private class Served(
    val file: AssetFile,
    val encoding: String?,
)

private fun lookup(
    assets: Assets,
    name: String,
    acceptEncoding: List<String>,
): Served? =
    if (name.endsWith(".br") || name.endsWith(".gz")) {
        null
    } else {
        listOf("br" to ".br", "gzip" to ".gz")
            .filter { accepts(acceptEncoding, it.first) }
            .firstNotNullOfOrNull { (coding, ext) -> assets.read(name + ext)?.let { Served(it, coding) } }
            ?: assets.read(name)?.let { Served(it, null) }
    }

private const val OK = 200
private const val NOT_MODIFIED = 304
private const val BAD_REQUEST = 400
private const val NOT_FOUND = 404
private const val METHOD_NOT_ALLOWED = 405
private const val UNAVAILABLE = 503

/** Answers one non-API request from [assets]. */
fun serveSpa(
    assets: Assets,
    req: ServerRequest,
): ServerResponse {
    val path = decodePath(req.path)?.trimStart('/')
    return secured(
        when {
            req.method != "GET" && req.method != "HEAD" -> ServerResponse(METHOD_NOT_ALLOWED)
            path == null -> ServerResponse(BAD_REQUEST)
            else -> serveFile(assets, req, path)
        },
    )
}

private fun serveFile(
    assets: Assets,
    req: ServerRequest,
    path: String,
): ServerResponse {
    val accept = req.headers["accept-encoding"].orEmpty()
    var name = path.ifEmpty { "index.html" }
    var served = lookup(assets, name, accept)
    if (served == null && isPageRoute(path)) {
        name = "index.html"
        served = lookup(assets, name, accept)
    }
    return when {
        served != null -> {
            respond(name, served, req)
        }

        isPageRoute(path) -> {
            ServerResponse(
                UNAVAILABLE,
                listOf("content-type" to "text/plain; charset=utf-8"),
                "rtok web: no index.html in the SPA source; build it or fix $DIST_ENV".encodeToByteArray(),
            )
        }

        else -> {
            ServerResponse(NOT_FOUND)
        }
    }
}

private fun respond(
    name: String,
    served: Served,
    req: ServerRequest,
): ServerResponse {
    val headers = mutableListOf<Pair<String, String>>()
    val etag = served.file.etag
    val matched =
        etag != null &&
            req.headers["if-none-match"].orEmpty().flatMap { it.split(',') }.any {
                it.trim() == "*" || it.trim().removePrefix("W/") == etag
            }
    val status: Int
    var body = ByteArray(0)
    if (matched) {
        status = NOT_MODIFIED
    } else {
        status = OK
        body = if (req.method == "HEAD") ByteArray(0) else served.file.bytes
        headers += "content-type" to mimeOf(name)
        served.encoding?.let { headers += "content-encoding" to it }
    }
    headers += "cache-control" to if (name.startsWith("assets/")) IMMUTABLE else REVALIDATE
    headers += "vary" to "Accept-Encoding"
    etag?.let { headers += "etag" to it }
    return ServerResponse(status, headers, body)
}

/** CSP, nosniff and no-referrer on every SPA response (spa.rs `secured`). */
fun secured(res: ServerResponse): ServerResponse =
    ServerResponse(
        res.status,
        res.headers +
            listOf(
                "content-security-policy" to CSP,
                "x-content-type-options" to "nosniff",
                "referrer-policy" to "no-referrer",
            ),
        res.body,
    )

private val MIME =
    mapOf(
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "json" to "application/json",
        "map" to "application/json",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "txt" to "text/plain; charset=utf-8",
        "wasm" to "application/wasm",
        "webmanifest" to "application/manifest+json",
    )

/** The content type for [name] by extension (mime_guess for the types a Vite build emits). */
internal fun mimeOf(name: String): String {
    val last = name.substringAfterLast('/')
    if (!last.contains('.')) return "application/octet-stream"
    return MIME[last.substringAfterLast('.').lowercase()] ?: "application/octet-stream"
}
