// Port of src/tasks/remote.rs: what the GitHub and GitLab adapters share — the `rtok:<id>` label and title naming,
// the token lookup, and one HTTP client that paces writes and waits out rate limits.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("MagicNumber", "TooManyFunctions", "ReturnCount")

package rtok.tasks

import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.http.HttpClient
import rtok.http.HttpException
import rtok.http.HttpRequest
import rtok.http.HttpResponse
import rtok.store.TaskId
import rtok.sys.ProgressRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Every rtok issue carries this label, so `list` asks the server for rtok's issues only. */
const val LABEL = "rtok"

/** GitHub's floor between content-creating requests, also a safe pace for GitLab. */
val WRITE_GAP: Duration = 1.seconds

/** Tries per request when the server says to wait; a limit that outlasts them is an error. */
internal const val ATTEMPTS = 3

/** A CLI call or an MCP tool waits at most this long for a limit to reset. */
internal val MAX_WAIT: Duration = 60.seconds

/** The label naming one task: `rtok:R12`. */
fun idLabel(id: TaskId) = "$LABEL:$id"

/** The task a label names; null for any other label, `rtok:in-progress` included. */
fun labelId(name: String): TaskId? {
    if (!name.startsWith("$LABEL:")) return null
    return runCatching { TaskId.parse(name.substring(LABEL.length + 1)) }.getOrNull()
}

/** The issue title: `R12. Ship it`. */
fun issueTitle(
    id: TaskId,
    title: String,
) = "$id. ${title.trim()}"

/** The id an issue title still starts with, for an issue whose label is gone. */
fun titleId(title: String): TaskId? {
    val at = title.indexOf(". ")
    if (at < 0) return null
    return runCatching { TaskId.parse(title.substring(0, at).trim()) }.getOrNull()
}

/** The task title back from an issue title; one edited by hand keeps whatever it says. */
fun taskTitle(
    id: TaskId,
    title: String,
): String {
    val lead = "$id. "
    return if (title.length >= lead.length && title.substring(0, lead.length).equals(lead, ignoreCase = true)) {
        title.substring(lead.length).trim()
    } else {
        title.trim()
    }
}

/** Unix seconds of an API timestamp (RFC 3339); 0 when it does not parse. */
@OptIn(kotlin.time.ExperimentalTime::class)
fun secs(ts: String): Long =
    runCatching {
        kotlin.time.Instant
            .parse(ts)
            .epochSeconds
    }.getOrDefault(0)

/** The highest of [ids] with [prefix], any case. */
fun maxWithPrefix(
    ids: Iterable<TaskId>,
    prefix: String,
): TaskId? {
    val p = prefix.uppercase()
    return ids.filter { it.prefix == p }.maxOrNull()
}

/** The first non-empty variable of [vars], else the trimmed output of the [cli] command (`gh auth token`). */
fun token(
    adapter: String,
    vars: List<String>,
    cli: List<String>,
): String =
    try {
        tokenWith({ platform.posix.getenv(it)?.toKString() }, vars, cli)
    } catch (e: TaskException) {
        val how = if (cli.isEmpty()) "set ${vars.joinToString(" or ")}" else "set ${vars.joinToString(" or ")} or run `${cli.joinToString(" ")}`"
        bail("$adapter: no token; $how (${e.message})")
    }

internal fun tokenWith(
    env: (String) -> String?,
    vars: List<String>,
    cli: List<String>,
): String {
    vars
        .mapNotNull(env)
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.let { return it }
    if (cli.isEmpty()) bail("no token in the environment")
    val out =
        try {
            ProgressRunner("", cli[0], cli.drop(1)).quiet().runCapped(64 * 1024).first
        } catch (e: rtok.sys.IoException) {
            bail("`${cli.joinToString(" ")}`: ${e.message}")
        }
    val t = out.stdout.decodeToString().trim()
    if (out.exitCode != 0 || t.isEmpty()) bail("`${cli.joinToString(" ")}` printed no token")
    return t
}

/** The real client of the github and gitlab adapters: Ktor, 30 s per request (Rust `timeout(30 s)`). */
internal fun remoteClient(): HttpClient = rtok.http.ktor.KtorHttp(rtok.http.HttpSettings(requestTimeoutMs = 30_000))

/**
 * A blocking JSON client for one API base. The adapter interface is synchronous (CLI, stdio MCP), so each call
 * runs the suspend client to completion. [headers] carry the auth and API version every request sends; [gap] is the
 * pause between writes ([WRITE_GAP], zero in tests).
 */
class RemoteHttp(
    private val adapter: String,
    base: String,
    private val client: HttpClient,
    private val headers: List<Pair<String, String>>,
    private val gap: Duration,
    private val now: () -> Long = { platform.posix.time(null) },
) : AutoCloseable {
    val base = base.trimEnd('/')
    private var lastWrite: TimeMark? = null

    /** Every item of a paged list, following `Link: rel="next"` while it stays on the API host. */
    fun getAll(
        path: String,
        query: List<Pair<String, String>>,
    ): List<JsonElement> {
        var url = base + path + queryString(query)
        val out = mutableListOf<JsonElement>()
        while (true) {
            val res = exec("GET", url, null)
            val page = parse("GET", url, res) as? JsonArray ?: bail("$adapter: GET $url: unexpected JSON")
            out.addAll(page)
            val next = nextLink(res.header("link"))
            // The token goes with every request, so a link off the API host is not followed.
            if (next != null && next.startsWith("$base/")) url = next else return out
        }
    }

    /** One write; the answer as JSON. */
    fun send(
        method: String,
        path: String,
        body: JsonElement,
    ): JsonElement {
        val url = base + path
        return parse(method, url, exec(method, url, body))
    }

    private fun parse(
        method: String,
        url: String,
        res: HttpResponse,
    ): JsonElement = runCatching { Json.parseToJsonElement(res.text()) }.getOrElse { bail("$adapter: $method $url: unexpected JSON") }

    private fun exec(
        method: String,
        url: String,
        body: JsonElement?,
    ): HttpResponse {
        for (attempt in 1..ATTEMPTS) {
            if (body != null) pace()
            val hs = headers + listOf("user-agent" to "rtok/${rtok.VERSION}") + if (body != null) listOf("content-type" to "application/json") else emptyList()
            val req = HttpRequest(url, hs, body?.toString()?.encodeToByteArray() ?: ByteArray(0))
            val res =
                try {
                    runBlocking { client.request(method, req) }
                } catch (e: HttpException) {
                    bail("$adapter: $method $url: ${e.message}")
                }
            if (res.isSuccess) return res
            val wait = rateWait(res.status, res::header, now())
            if (wait != null) {
                if (wait > MAX_WAIT || attempt == ATTEMPTS) bail("$adapter: $method $url: rate limited; try again in ${wait.inWholeSeconds} s")
                sleep(wait)
                continue
            }
            bail("$adapter: $method $url: ${statusLine(res.status)}: ${errorMessage(res.text())}")
        }
        error("the last attempt returns or bails")
    }

    /** Hold the next write until [gap] has passed since the last one. */
    private fun pace() {
        lastWrite?.elapsedNow()?.let { since -> if (since < gap) sleep(gap - since) }
        lastWrite = TimeSource.Monotonic.markNow()
    }

    override fun close() = client.close()
}

private fun sleep(d: Duration) {
    if (d.isPositive()) platform.posix.usleep(d.inWholeMicroseconds.coerceAtMost(UInt.MAX_VALUE.toLong()).toUInt())
}

/** `%`-encode a query value (RFC 3986 unreserved kept). */
internal fun queryString(query: List<Pair<String, String>>): String =
    if (query.isEmpty()) {
        ""
    } else {
        query.joinToString("&", prefix = "?") { (k, v) -> "${pct(k)}=${pct(v)}" }
    }

private fun pct(s: String) =
    buildString {
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xff
            if (c.toChar().let { it.isLetterOrDigit() && c < 0x80 || it in "-_.~" }) {
                append(c.toChar())
            } else {
                append('%').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

/** reqwest's `StatusCode` display: `404 Not Found`. */
internal fun statusLine(status: Int): String {
    val reason =
        mapOf(
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            409 to "Conflict",
            410 to "Gone",
            422 to "Unprocessable Entity",
            429 to "Too Many Requests",
            500 to "Internal Server Error",
            502 to "Bad Gateway",
            503 to "Service Unavailable",
        )[status]
    return if (reason == null) "$status" else "$status $reason"
}

/** `message`, else GraphQL's `errors[0].message` (Linear, even for a 400), else the first 300 characters. */
internal fun errorMessage(text: String): String {
    val v = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    val msg =
        (v?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: (((v?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return msg ?: text.take(300)
}

/**
 * How long a refused request should wait: `retry-after`, else the primary limit's reset time, else a minute for a
 * bare 429. Null when the refusal is not a rate limit (a 403 for a missing permission).
 */
internal fun rateWait(
    status: Int,
    header: (String) -> String?,
    now: Long,
): Duration? {
    if (status != 403 && status != 429) return null

    fun num(name: String) = header(name)?.trim()?.toULongOrNull()?.toLong()
    num("retry-after")?.let { return it.seconds }
    val reset = num("x-ratelimit-reset")
    if (num("x-ratelimit-remaining") == 0L && reset != null) return (reset - now).coerceAtLeast(0).seconds
    return if (status == 429) MAX_WAIT else null
}

/** The `rel="next"` URL of a `Link` header. */
internal fun nextLink(link: String?): String? =
    link?.split(',')?.firstNotNullOfOrNull { part ->
        val at = part.indexOf(';')
        if (at < 0 || "rel=\"next\"" !in part.substring(at)) {
            null
        } else {
            part
                .substring(0, at)
                .trim()
                .removePrefix("<")
                .removeSuffix(">")
        }
    }
