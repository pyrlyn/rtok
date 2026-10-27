// Port of `flex::send` (src/proxy/flex.rs) and the tee in `handle` (src/proxy/mod.rs): send one request upstream,
// retrying a Flex `429` per policy, then forward each body chunk to the client *while* buffering a capped copy
// for the ledger. The copy never delays the client: chunks go out as they arrive.
package rtok.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import rtok.http.HttpRequest
import rtok.http.StreamedResponse
import rtok.http.StreamingHttpClient

/** What the tee kept of one response body. */
class Tee(
    val buffer: ByteArray,
    val totalBytes: Long,
    /** False once the stream was cut short: client gone or upstream error. */
    val complete: Boolean,
)

/** One send: the tee of the response that was forwarded, or the transport error; the body last sent; retries. */
class Sent(
    val tee: Tee?,
    val error: Throwable?,
    val body: ByteArray,
    val retries: UInt,
)

object Forward {
    private sealed interface Attempt {
        class Again(
            val step: Pair<kotlin.time.Duration, ByteArray?>,
        ) : Attempt

        class Done(
            val tee: Tee,
        ) : Attempt
    }

    /** Send until a response other than a retried Flex `429` arrives; [onResponse] owns that response. */
    suspend fun send(
        client: StreamingHttpClient,
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        body: ByteArray,
        retry: Flex.Retry?,
        onResponse: suspend (StreamedResponse) -> Tee,
    ): Sent {
        var sent = body
        var n = 0u
        while (true) {
            val outcome =
                runCatching {
                    client.stream(method, HttpRequest(url, headers, sent)) { r ->
                        val next = if (r.status == ProxyHttp.TOO_MANY_REQUESTS) retry?.next(n, Flex.retryAfter(r.header("retry-after"))) else null
                        if (next != null) Attempt.Again(next) else Attempt.Done(onResponse(r))
                    }
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    return Sent(null, e, sent, n)
                }
            when (outcome) {
                is Attempt.Done -> {
                    return Sent(outcome.tee, null, sent, n)
                }

                is Attempt.Again -> {
                    delay(outcome.step.first)
                    outcome.step.second?.let { sent = it }
                    n++
                }
            }
        }
    }

    /** Copy [r]'s body into [out] chunk by chunk, keeping at most [cap] bytes of it; closes [out]. */
    suspend fun tee(
        r: StreamedResponse,
        out: SendChannel<ByteArray>,
        cap: Long,
    ): Tee {
        val kept = mutableListOf<ByteArray>()
        var size = 0L
        var total = 0L
        val failure =
            runCatching {
                while (true) {
                    val chunk = r.read() ?: break
                    total += chunk.size
                    if (size < cap) {
                        val room = minOf(cap - size, chunk.size.toLong()).toInt()
                        kept += if (room == chunk.size) chunk else chunk.copyOf(room)
                        size += room
                    }
                    out.send(chunk)
                }
            }.exceptionOrNull()
        // A cancelled or closed channel is the client going away: record what we have. Anything else is an
        // upstream read error the client sees as a cut stream.
        val clientGone = failure is CancellationException || failure is ClosedSendChannelException
        out.close(failure.takeUnless { clientGone })
        val complete = failure == null
        val buf = ByteArray(size.toInt())
        var at = 0
        for (k in kept) {
            k.copyInto(buf, at)
            at += k.size
        }
        // A body past the cap is incomplete as recorded too.
        return Tee(buf, total, complete && total == size)
    }
}
