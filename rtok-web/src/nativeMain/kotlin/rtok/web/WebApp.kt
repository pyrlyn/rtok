// Port of src/web/mod.rs routing: `/health`, the `/auth` probe and the SPA fallback. `/ws` comes next (docs/web.md step 3).
package rtok.web

import rtok.http.server.RequestHandler
import rtok.http.server.ServerRequest
import rtok.http.server.ServerResponse

private const val NO_CONTENT = 204
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val OK = 200

/** The bound address `/health` reports (with port 0, the one the kernel picked). */
class BoundAddr(
    val host: String,
    var port: Int,
)

/**
 * One `rtok web` app. [token] is the S2 token `/ws` and `/auth` require; null only for in-process tests
 * that skip auth.
 */
class WebApp(
    val bound: BoundAddr,
    private val assets: Assets,
    private val token: String?,
) : RequestHandler {
    override fun handle(req: ServerRequest): ServerResponse =
        when (req.path) {
            "/health" -> health()
            "/auth" -> ServerResponse(admitted(req) ?: NO_CONTENT)
            else -> serveSpa(assets, req)
        }

    private fun health(): ServerResponse {
        val host = bound.host.replace("\\", "\\\\").replace("\"", "\\\"")
        val json = """{"host":"$host","ok":true,"port":${bound.port}}"""
        return ServerResponse(OK, listOf("content-type" to "application/json"), json.encodeToByteArray())
    }

    /** The `/ws` gate: the S2 token, then the origin. A status to refuse with, or null when admitted. */
    fun admitted(req: ServerRequest): Int? {
        val got = WebToken.presented(req.header("authorization"), req.query)
        return when {
            token != null && (got == null || !WebToken.matches(token, got)) -> UNAUTHORIZED
            !originAllowed(req.header("origin"), req.header("host")) -> FORBIDDEN
            else -> null
        }
    }
}
