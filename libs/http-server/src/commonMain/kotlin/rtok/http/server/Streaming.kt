// Engine-neutral concurrent, streaming HTTP server. Additive: `HttpServer` and `RequestHandler` are unchanged.
// Unlike `RequestHandler`, a `StreamingHandler` is NOT serialized: calls overlap, so it owns its own locking.
package rtok.http.server

/** Where a streaming response body goes; each [write] is flushed to the client. */
fun interface BodySink {
    suspend fun write(bytes: ByteArray)
}

class StreamingResponse(
    val status: Int,
    val headers: List<Pair<String, String>> = emptyList(),
    /** Writes the body; runs after the status and headers are sent. */
    val body: suspend (BodySink) -> Unit = {},
)

/** A request and the remote peer (`host` of the connection, without port), when the engine knows it. */
fun interface StreamingHandler {
    suspend fun handle(
        req: ServerRequest,
        peer: String?,
    ): StreamingResponse
}

interface StreamingHttpServer {
    /**
     * Like [HttpServer.serveBlocking] without WebSockets, but calls run concurrently and the body streams.
     * Requests with a body over [maxBody] bytes get 413 without reaching [handler].
     */
    fun serveStreaming(
        host: String,
        port: Int,
        handler: StreamingHandler,
        maxBody: Long = HttpServer.DEFAULT_MAX_BODY,
        stop: () -> Boolean = { false },
        onBound: (String) -> Unit = {},
    )
}
