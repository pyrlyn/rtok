// The HTTP edges of src/proxy/mod.rs and cli.rs: hop counting (T442), hop-by-hop headers, the Batch/Files path
// split (T385.12.1), the loopback check for `/live` and the error bodies the proxy answers with itself.
package rtok.proxy

import kotlinx.serialization.json.JsonPrimitive
import rtok.http.server.StreamingResponse
import rtok.store.SerdeJson

object ProxyHttp {
    /** How many rtok proxies a request has passed through (T442). */
    const val HOP_HEADER = "x-rtok-proxied"

    /** The lane marker: read by rtok, never forwarded. */
    const val LANE_HEADER = "x-rtok-lane"

    /** A chain this long is a loop, not a deployment: answer 508 instead of forwarding. */
    const val MAX_HOPS = 8

    /** Request bodies and the recorded prefix of a response are both capped here (memory, not policy). */
    const val MAX_BODY_BYTES = 256L * 1024 * 1024

    const val BAD_REQUEST = 400
    const val FORBIDDEN = 403
    const val METHOD_NOT_ALLOWED = 405
    const val TOO_MANY_REQUESTS = 429
    const val INTERNAL = 500
    const val BAD_GATEWAY = 502
    const val LOOP_DETECTED = 508
    const val OK = 200
    private const val OK_END = 299

    fun isSuccess(status: Int) = status in OK..OK_END

    private val HOP_BY_HOP =
        setOf("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "content-length", "host")

    /** Headers that must not be forwarded: HTTP/1.1 hop-by-hop plus framing (the proxy re-chunks). */
    fun hopByHop(name: String) = name.lowercase() in HOP_BY_HOP

    /** The incoming hop count; anything but a number still counts as one hop. */
    fun hopsOf(headers: List<Pair<String, String>>): Int =
        headers.firstOrNull { it.first.equals(HOP_HEADER, ignoreCase = true) }?.let {
            it.second
                .trim()
                .toIntOrNull()
                ?.coerceAtLeast(0) ?: 1
        } ?: 0

    /** OpenAI's `/v1/batches` and `/v1/files`; Anthropic's Files API shares the path but carries `anthropic-version`. */
    fun isOpenaiBatchPath(
        path: String,
        headers: List<Pair<String, String>>,
    ): Boolean {
        fun under(root: String) = path.startsWith(root) && path.substring(root.length).let { it.isEmpty() || it.startsWith('/') }
        return (under("/v1/batches") || under("/v1/files")) && headers.none { it.first.equals("anthropic-version", ignoreCase = true) }
    }

    /** A loopback peer, or none known. Accepts `127.x`, `::1` and IPv4-mapped loopback, bracketed or not. */
    fun peerIsLocal(peer: String?): Boolean {
        val p =
            peer
                ?.trim()
                ?.removePrefix("[")
                ?.substringBefore(']')
                ?.lowercase() ?: return true
        val v4 = p.removePrefix("::ffff:").removePrefix("0:0:0:0:0:ffff:")
        return v4.startsWith("127.") || p == "::1" || p == "0:0:0:0:0:0:0:1" || p == "localhost"
    }

    /** The provider-shaped error body the proxy answers with when it cannot forward. */
    fun error(
        status: Int,
        message: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): StreamingResponse {
        val type =
            when (status) {
                BAD_GATEWAY -> "upstream_error"
                TOO_MANY_REQUESTS -> "rate_limit_error"
                else -> "invalid_request_error"
            }
        val body = """{"type":"error","error":{"type":"$type","message":${SerdeJson.encode(JsonPrimitive(message))}}}"""
        return bytes(status, "application/json", body.encodeToByteArray(), extra)
    }

    /** A lane at its in-flight cap with a full queue (T385.7): `429` + `Retry-After`, which SDKs back off on. */
    fun queueFull(message: String) = error(TOO_MANY_REQUESTS, message, listOf("retry-after" to Gate.RETRY_AFTER_S.toString()))

    fun bytes(
        status: Int,
        contentType: String?,
        body: ByteArray,
        extra: List<Pair<String, String>> = emptyList(),
    ) = StreamingResponse(status, listOfNotNull(contentType?.let { "content-type" to it }) + extra) { it.write(body) }
}
