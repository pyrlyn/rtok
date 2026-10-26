// Engine-neutral HTTP client interface. One implementation (`rtok.http.ktor.KtorHttp`) wraps Ktor;
// callers depend only on this file.
package rtok.http

/** Timeouts of one client (Rust: `tls::client` connect 10 s, read `timeout_s`). */
data class HttpSettings(
    val connectTimeoutMs: Long = 10_000,
    val requestTimeoutMs: Long = 60_000,
    val socketTimeoutMs: Long = 60_000,
)

data class HttpRequest(
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = ByteArray(0),
)

data class HttpResponse(
    val status: Int,
    val body: ByteArray,
) {
    val isSuccess get() = status in 200..299

    fun text() = body.decodeToString()
}

/** A request that got no response. */
class HttpException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { ConnectTimeout, RequestTimeout, SocketTimeout, Connect, Io }
}

interface HttpClient : AutoCloseable {
    /** POST [req]; a non-2xx status is a response, not an exception. Throws [HttpException] otherwise. */
    suspend fun post(req: HttpRequest): HttpResponse
}

/** Builds clients; the composition root picks the engine adapter. */
fun interface HttpClientFactory {
    fun create(settings: HttpSettings): HttpClient
}
