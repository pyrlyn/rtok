@file:Suppress("MagicNumber", "EmptyFunctionBlock")

// A scripted rtok.http.HttpClient for the remote task adapters: routes by method and path (query optional),
// records every request. The stand-in for the Rust tests' httpmock server.

package rtok.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import rtok.http.HttpClient
import rtok.http.HttpRequest
import rtok.http.HttpResponse

class FakeHttp : HttpClient {
    class Call(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>>,
        val body: String,
    ) {
        val path get() = url.substringAfter("://").substringAfter('/', "").let { "/" + it.substringBefore('?') }
        val query get() = url.substringAfter('?', "")

        fun json(): JsonElement = Json.parseToJsonElement(body)
    }

    private class Route(
        val method: String,
        val path: String,
        val query: String?,
        val bodyHas: String?,
        val reply: (Call) -> HttpResponse,
        val test: (Call) -> Boolean = { true },
    )

    private val routes = mutableListOf<Route>()
    val calls = mutableListOf<Call>()

    /** Answer [method] [path] (when given, only a query containing [query] and a body containing [bodyHas]); later routes win. */
    fun on(
        method: String,
        path: String,
        query: String? = null,
        bodyHas: String? = null,
        reply: (Call) -> HttpResponse,
    ) = apply { routes.add(0, Route(method, path, query, bodyHas, reply)) }

    /** Answer [method] [path] only for a call [test] accepts (httpmock's `is_true`); later routes win. */
    fun onIf(
        method: String,
        path: String,
        test: (Call) -> Boolean,
        reply: (Call) -> HttpResponse,
    ) = apply { routes.add(0, Route(method, path, null, null, reply, test)) }

    /** Calls to [method] [path] whose body contains [bodyHas]. */
    fun hits(
        method: String,
        path: String,
        bodyHas: String,
    ) = calls.count { it.method == method && it.path == path && bodyHas in it.body }

    fun hits(
        method: String,
        path: String,
    ) = calls.count { it.method == method && it.path == path }

    override suspend fun post(req: HttpRequest) = request("POST", req)

    override suspend fun get(req: HttpRequest) = request("GET", req)

    override suspend fun request(
        method: String,
        req: HttpRequest,
    ): HttpResponse {
        val call = Call(method.uppercase(), req.url, req.headers, req.body.decodeToString())
        calls += call
        val r = routes.firstOrNull { it.method == call.method && it.path == call.path && (it.query == null || it.query in call.query) && (it.bodyHas == null || it.bodyHas in call.body) && it.test(call) }
        return r?.reply?.invoke(call) ?: HttpResponse(404, """{"message":"no mock for ${call.method} ${call.path}"}""".encodeToByteArray())
    }

    override fun close() {}
}

fun ok(
    json: String,
    vararg headers: Pair<String, String>,
) = HttpResponse(200, json.encodeToByteArray(), headers.toList())

fun status(
    code: Int,
    json: String = "{}",
    vararg headers: Pair<String, String>,
) = HttpResponse(code, json.encodeToByteArray(), headers.toList())
