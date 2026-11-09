// Engine-neutral HTTP server interface. One implementation (`rtok.http.server.ktor.KtorHttpServer`) wraps Ktor;
// callers depend only on this file.
package rtok.http.server

/** One request: [headers] keys are lower-case, values in arrival order; [query] is the raw query string, if any. */
class ServerRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val query: String? = null,
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

/** One open WebSocket, text frames only. */
interface WsSocket {
    /** Sends one text frame; false once the peer is gone. */
    suspend fun send(text: String): Boolean

    /** The next text frame, or null when the socket closed. Binary and control frames are skipped. */
    suspend fun receive(): String?
}

/**
 * A WebSocket route. [admit] runs before the upgrade, serialized with [RequestHandler] calls: a status
 * refuses the upgrade with that status, null accepts it. [run] then owns the socket until it returns and is
 * NOT serialized with anything: it runs concurrently with requests and other sockets.
 */
interface WsHandler {
    fun admit(req: ServerRequest): Int?

    suspend fun run(socket: WsSocket)
}

/** Raised when the listener cannot bind. */
class BindException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

interface HttpServer {
    /**
     * Binds [host]:[port] (0 picks a free port), reports the bound `host:port` to [onBound], then serves
     * ([websockets] by exact path) until [stop] returns true (checked between requests and on a short tick), SIGINT or
     * SIGTERM arrives, or the process ends.
     * Requests with a body over [maxBody] bytes get 413 without reaching [handler].
     */
    fun serveBlocking(
        host: String,
        port: Int,
        handler: RequestHandler,
        maxBody: Long = DEFAULT_MAX_BODY,
        stop: () -> Boolean = { false },
        websockets: Map<String, WsHandler> = emptyMap(),
        onBound: (String) -> Unit = {},
    )

    companion object {
        const val DEFAULT_MAX_BODY: Long = 8L shl 20
    }
}
