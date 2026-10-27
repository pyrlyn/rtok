// Engine-neutral streaming HTTP client: the response body is read chunk by chunk while the call is open, so a
// proxy can forward SSE as it arrives. Additive: `HttpClient` is unchanged.
package rtok.http

/** A response whose body is still arriving. Valid only inside [StreamingHttpClient.stream]'s block. */
class StreamedResponse(
    val status: Int,
    /** Response headers, names lower-cased. */
    val headers: List<Pair<String, String>>,
    private val next: suspend () -> ByteArray?,
) {
    /** The next body chunk, or null at the end of the body. Errors as for [HttpClient.post]. */
    suspend fun read(): ByteArray? = next()

    /** The first value of header [name] (any case), or null. */
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

interface StreamingHttpClient : HttpClient {
    /**
     * Sends [method] [req] (any method; the body is sent when non-empty, redirects are never followed) and runs
     * [use] while the response streams. A non-2xx status is a response; a failure is an [HttpException].
     */
    suspend fun <T> stream(
        method: String,
        req: HttpRequest,
        use: suspend (StreamedResponse) -> T,
    ): T
}
