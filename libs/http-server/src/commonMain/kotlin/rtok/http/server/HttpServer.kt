// Engine-neutral HTTP server interface. One implementation (`rtok.http.server.ktor.KtorHttpServer`) wraps Ktor;
// callers depend only on this file.
package rtok.http.server

/** One request: [headers] keys are lower-case, values in arrival order. */
class ServerRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    /** The first value of header [name] (case-insensitive), or null. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

class ServerResponse(
    val status: Int,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = ByteArray(0),
)

/** Answers one request. Implementations may assume calls never overlap: the server serializes them. */
fun interface RequestHandler {
    fun handle(req: ServerRequest): ServerResponse
}

/** Raised when the listener cannot bind. */
class BindException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

interface HttpServer {
    /**
     * Binds [host]:[port] (0 picks a free port), reports the bound `host:port` to [onBound], then serves
     * until [stop] returns true (checked between requests and on a short tick) or the process ends.
     * Requests with a body over [maxBody] bytes get 413 without reaching [handler].
     */
    fun serveBlocking(
        host: String,
        port: Int,
        handler: RequestHandler,
        maxBody: Long = DEFAULT_MAX_BODY,
        stop: () -> Boolean = { false },
        onBound: (String) -> Unit = {},
    )

    companion object {
        const val DEFAULT_MAX_BODY: Long = 8L shl 20
    }
}
