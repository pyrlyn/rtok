// The Ktor implementation of rtok.http.HttpClient.
package rtok.http.ktor

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import rtok.http.HttpClient
import rtok.http.HttpClientFactory
import rtok.http.HttpException
import rtok.http.HttpRequest
import rtok.http.HttpResponse
import rtok.http.HttpSettings
import io.ktor.client.HttpClient as Ktor

/** The platform engine (Darwin / Curl). */
internal expect fun platformEngine(): HttpClientEngine

class KtorHttp(
    settings: HttpSettings,
    engine: HttpClientEngine = platformEngine(),
) : HttpClient {
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

    /** One request with every transport failure mapped to an [HttpException] kind. */
    private suspend fun send(
        req: HttpRequest,
        call: suspend () -> io.ktor.client.statement.HttpResponse,
    ): HttpResponse =
        try {
            val resp = call()
            val headers = resp.headers.entries().flatMap { (k, vs) -> vs.map { k.lowercase() to it } }
            HttpResponse(resp.status.value, resp.bodyAsBytes(), headers)
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

    override fun close() = client.close()

    companion object {
        val FACTORY = HttpClientFactory { KtorHttp(it) }
    }
}
