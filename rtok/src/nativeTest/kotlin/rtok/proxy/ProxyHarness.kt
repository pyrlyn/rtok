// Port of tests/common/proxy.rs: a real proxy on a fresh store in a throwaway dir, in front of a fake upstream on
// a loopback port. No real provider is ever contacted.
@file:OptIn(ObsoleteWorkersApi::class)

package rtok.proxy

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import rtok.config.Config
import rtok.http.server.ServerRequest
import rtok.http.server.StreamingHandler
import rtok.http.server.StreamingResponse
import rtok.http.server.ktor.KtorHttpServer
import rtok.support.tmpDir
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private fun launch(
    worker: Worker,
    handler: StreamingHandler,
    bound: AtomicReference<String?>,
    stop: AtomicInt,
) = worker.execute(TransferMode.SAFE, { Triple(handler, bound, stop) }) { (h, b, s) ->
    runCatching { KtorHttpServer.serveStreaming("127.0.0.1", 0, h, ProxyHttp.MAX_BODY_BYTES, { s.value != 0 }) { b.value = it } }
}

/** One server on `127.0.0.1:<free>` in its own worker until [close]. */
open class Served(
    handler: StreamingHandler,
    name: String,
) : AutoCloseable {
    private val bound = AtomicReference<String?>(null)
    private val stop = AtomicInt(0)
    private val worker = Worker.start(name = name)
    private val done = launch(worker, handler, bound, stop)

    init {
        while (bound.value == null && done.state == FutureState.SCHEDULED) platform.posix.usleep(SPIN_US)
    }

    /** `host:port`. */
    val addr: String get() = bound.value ?: error("server did not bind")
    val url: String get() = "http://$addr"

    override fun close() {
        stop.value = 1
        done.result
        worker.requestTermination().result
    }

    private companion object {
        const val SPIN_US = 5_000u
    }
}

/** One request as the fake upstream saw it. */
class Seen(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String) = headers[name.lowercase()]?.firstOrNull()
}

/** A canned answer for the requests [matches] accepts; [hits] counts them. */
class Mock(
    private val matches: (Seen) -> Boolean,
    val status: Int = ProxyHttp.OK,
    val headers: List<Pair<String, String>> = emptyList(),
    val chunks: List<String> = listOf(""),
    /** Raw chunks instead of [chunks] (bytes that are not valid UTF-8). */
    val bytes: List<ByteArray>? = null,
    /** How long to wait before answering. */
    val delayMs: Long = 0,
) {
    private val count = AtomicInt(0)
    val hits: Int get() = count.value

    fun accept(s: Seen) = matches(s).also { if (it) count.incrementAndGet() }
}

/** httpmock's role: first matching [Mock] answers, anything else is a 404; every request is kept. */
class FakeUpstream private constructor(
    private val answer: Answer,
) : Served(answer, "fake-upstream") {
    constructor(vararg mocks: Mock) : this(Answer(mocks.toList()))

    val seen: List<Seen> get() = answer.seen.value

    private class Answer(
        private val mocks: List<Mock>,
    ) : StreamingHandler {
        val seen = AtomicReference<List<Seen>>(emptyList())

        override suspend fun handle(
            req: ServerRequest,
            peer: String?,
        ): StreamingResponse {
            val s = Seen(req.method, req.path, req.query, req.headers, req.body.decodeToString())
            while (true) {
                val cur = seen.value
                if (seen.compareAndSet(cur, cur + s)) break
            }
            val m = mocks.firstOrNull { it.accept(s) } ?: return StreamingResponse(NOT_FOUND)
            if (m.delayMs > 0) delay(m.delayMs)
            val out = m.bytes ?: m.chunks.map { it.encodeToByteArray() }
            return StreamingResponse(m.status, m.headers) { sink -> out.forEach { sink.write(it) } }
        }
    }

    companion object {
        private const val NOT_FOUND = 404
    }
}

/** The proxy under test: [state] is its live state (store included). */
class ProxyUnderTest private constructor(
    val state: ProxyState,
) : Served(ProxyHandler(state), "proxy-under-test") {
    override fun close() {
        super.close()
        state.close()
    }

    companion object {
        /** Fresh dir and store; `compress` is off (its path has its own tests), [tune] sets what the case needs. */
        fun start(
            tag: String,
            tune: (Config) -> Config = { it },
        ): ProxyUnderTest {
            val base = Config().rebasePaths(tmpDir("proxy-$tag"))
            val cfg = tune(base.copy(plugins = base.plugins.copy(compress = base.plugins.compress.copy(enabled = false))))
            return ProxyUnderTest(ProxyState.open(cfg))
        }
    }
}

fun Config.proxy(edit: rtok.config.Proxy.() -> rtok.config.Proxy) = copy(proxy = proxy.edit())

private const val DEADLINE_S = 10
private const val POLL_MS = 5L

/** Wait (bounded) for bookkeeping that runs after the response: the rows land off the response path. */
fun eventually(
    what: String,
    check: () -> Boolean,
) = runBlocking {
    val deadline = TimeSource.Monotonic.markNow() + DEADLINE_S.seconds
    while (!check()) {
        assertTrue(deadline.hasNotPassedNow(), "timed out waiting for $what")
        delay(POLL_MS)
    }
}

/** One request to [url] through a fresh client; any method, the body sent when non-empty. */
fun call(
    method: String,
    url: String,
    body: String = "",
    vararg headers: Pair<String, String>,
): rtok.http.HttpResponse =
    rtok.http.ktor.KtorHttp(rtok.http.HttpSettings()).use { c ->
        runBlocking { c.request(method, rtok.http.HttpRequest(url, headers.toList(), body.encodeToByteArray())) }
    }
