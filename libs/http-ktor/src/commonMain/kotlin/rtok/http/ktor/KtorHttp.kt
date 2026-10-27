// The Ktor implementation of rtok.http.HttpClient.
package rtok.http.ktor

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rtok.http.HttpClient
import rtok.http.HttpClientFactory
import rtok.http.HttpException
import rtok.http.HttpRequest
import rtok.http.HttpResponse
import rtok.http.HttpSettings
import rtok.http.StreamedResponse
import rtok.http.StreamingHttpClient
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import io.ktor.client.HttpClient as Ktor

/** The platform engine (Darwin / Curl). */
internal expect fun platformEngine(): HttpClientEngine

/** Whether the platform engine applies [HttpSettings.socketTimeoutMs] on its own. */
internal expect val engineEnforcesSocketTimeout: Boolean

class KtorHttp(
    settings: HttpSettings,
    engine: HttpClientEngine = platformEngine(),
    /** Bound each wait for a streamed response (headers, then every chunk) by the socket timeout here. */
    idleWatchdog: Boolean = !engineEnforcesSocketTimeout,
) : StreamingHttpClient {
    private val idleLimitMs = settings.socketTimeoutMs.takeIf { idleWatchdog && it in 1 until Long.MAX_VALUE }

    private val client =
        Ktor(engine) {
            expectSuccess = false
            followRedirects = true
            install(HttpTimeout) {
                connectTimeoutMillis = settings.connectTimeoutMs
                requestTimeoutMillis = settings.requestTimeoutMs
                socketTimeoutMillis = settings.socketTimeoutMs
            }
        }

    override suspend fun post(req: HttpRequest): HttpResponse =
        send(req) {
            client.post(req.url) {
                // Raw headers: Ktor must not merge or re-case what the caller set (auth included).
                req.headers.forEach { (k, v) -> headers.append(k, v) }
                setBody(req.body)
            }
        }

    override suspend fun get(req: HttpRequest): HttpResponse = send(req) { client.get(req.url) { req.headers.forEach { (k, v) -> headers.append(k, v) } } }

    override suspend fun request(
        method: String,
        req: HttpRequest,
    ): HttpResponse =
        send(req) {
            client.request(req.url) {
                this.method = HttpMethod.parse(method.uppercase())
                req.headers.forEach { (k, v) -> headers.append(k, v) }
                if (req.body.isNotEmpty()) setBody(req.body)
            }
        }

    /** A second client that never follows redirects: a proxy hands a 3xx to its caller as it came. */
    private var rawInit = false
    private val raw by lazy { client.config { followRedirects = false }.also { rawInit = true } }

    override suspend fun <T> stream(
        method: String,
        req: HttpRequest,
        use: suspend (StreamedResponse) -> T,
    ): T =
        idleBounded(req) { waiting ->
            raw
                .prepareRequest(req.url) {
                    this.method = HttpMethod.parse(method.uppercase())
                    req.headers.forEach { (k, v) -> headers.append(k, v) }
                    if (req.body.isNotEmpty()) setBody(req.body)
                }.execute { resp ->
                    waiting(false)
                    val channel = resp.bodyAsChannel()
                    val buf = ByteArray(CHUNK)
                    val want = declaredLength(method, resp)
                    var got = 0L
                    val next: suspend () -> ByteArray? = {
                        guard(req) {
                            waiting(true)
                            val n = channel.readAvailable(buf, 0, buf.size)
                            if (n < 0) {
                                waiting(false)
                                checkComplete(req, want, got)
                                null
                            } else {
                                waiting(false)
                                got += n
                                buf.copyOf(n)
                            }
                        }
                    }
                    use(StreamedResponse(resp.status.value, headersOf(resp), next))
                }
        }

    /**
     * The `content-length` the body must reach, or null when there is none to check. The Curl engine (Linux) ends the
     * body quietly when the peer hangs up early; Darwin throws. Both must report the cut so a short body is never
     * mistaken for a whole one (and, in the proxy, cached).
     */
    private fun declaredLength(
        method: String,
        resp: io.ktor.client.statement.HttpResponse,
    ): Long? {
        val bodiless = method.equals("HEAD", ignoreCase = true) || resp.status.value in NO_BODY
        val encoded = resp.headers[HttpHeaders.ContentEncoding] != null
        return if (bodiless || encoded) null else resp.headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull()
    }

    private fun checkComplete(
        req: HttpRequest,
        want: Long?,
        got: Long,
    ) {
        if (want != null && got < want) throw HttpException(HttpException.Kind.Io, "${req.url}: body truncated at $got of $want bytes")
    }

    /**
     * Runs [block] under [guard], and, when [idleLimitMs] is set, fails it with a socket timeout once a single wait for
     * the upstream (marked by `waiting(true)` … `waiting(false)`) outlasts the limit. Time the caller spends between
     * reads does not count, as with a socket read timeout.
     */
    private suspend fun <T> idleBounded(
        req: HttpRequest,
        block: suspend (waiting: (Boolean) -> Unit) -> T,
    ): T {
        val limit = idleLimitMs ?: return guard(req) { block {} }
        val clock = TimeSource.Monotonic.markNow()
        val since = IdleMark(0)
        return coroutineScope {
            val work = async { guard(req) { block { on -> since.ms = if (on) clock.elapsedNow().inWholeMilliseconds else -1 } } }
            var timedOut = false
            val dog =
                launch {
                    while (work.isActive) {
                        val start = since.ms
                        val idle = if (start < 0) 0 else clock.elapsedNow().inWholeMilliseconds - start
                        if (idle >= limit) {
                            timedOut = true
                            work.cancel()
                            break
                        }
                        delay((limit - idle).coerceIn(1, limit))
                    }
                }
            try {
                work.await()
            } catch (e: CancellationException) {
                if (timedOut) throw HttpException(HttpException.Kind.SocketTimeout, "socket timeout: ${req.url}", e)
                throw e
            } finally {
                dog.cancel()
            }
        }
    }

    /** When the current wait for the upstream began (ms on the call's clock), or -1 while not waiting. */
    private class IdleMark(
        @Volatile var ms: Long,
    )

    private fun headersOf(resp: io.ktor.client.statement.HttpResponse) = resp.headers.entries().flatMap { (k, vs) -> vs.map { k.lowercase() to it } }

    /** One request with every transport failure mapped to an [HttpException] kind. */
    private suspend fun send(
        req: HttpRequest,
        call: suspend () -> io.ktor.client.statement.HttpResponse,
    ): HttpResponse =
        guard(req) {
            val resp = call()
            val body = resp.bodyAsBytes()
            checkComplete(req, declaredLength(resp.request.method.value, resp), body.size.toLong())
            HttpResponse(resp.status.value, body, headersOf(resp))
        }

    private suspend fun <T> guard(
        req: HttpRequest,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: HttpRequestTimeoutException) {
            throw HttpException(HttpException.Kind.RequestTimeout, "request timeout: ${req.url}", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectTimeoutException) {
            throw HttpException(HttpException.Kind.ConnectTimeout, "connect timeout: ${req.url}", e)
        } catch (e: SocketTimeoutException) {
            throw HttpException(HttpException.Kind.SocketTimeout, "socket timeout: ${req.url}", e)
        } catch (e: HttpException) {
            throw e
        } catch (e: Exception) {
            throw HttpException(HttpException.Kind.Io, "${req.url}: ${e.message}", e)
        }

    override fun close() {
        if (rawInit) raw.close()
        client.close()
    }

    companion object {
        val FACTORY = HttpClientFactory { KtorHttp(it) }
        private const val CHUNK = 16 * 1024

        /** 1xx, 204 and 304 carry no body whatever their `content-length` says. */
        private val NO_BODY = setOf(100, 101, 102, 103, 204, 304)
    }
}
