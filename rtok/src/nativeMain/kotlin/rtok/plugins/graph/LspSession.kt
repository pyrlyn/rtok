// Port of `Session` in src/plugins/graph/lsp.rs: one language server over stdio, requests with a deadline
// (T620), server-to-client replies, document sync (`did_open`/`open`/`close`/`refresh_open`), and the one cached
// session per workspace root (`with_session`, `shutdown`).
package rtok.plugins.graph

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import rtok.sys.PipedChild
import rtok.sys.extension
import rtok.sys.readToString
import rtok.sys.removeFile
import kotlin.concurrent.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** The text a server holds for one open document. */
class OpenDoc(
    val version: Long,
    val text: String,
)

class LspSession internal constructor(
    internal val child: PipedChild,
    val root: String,
    private val errPath: String,
) : AutoCloseable {
    /** Writes one frame to the server; false once its input is closed (test seam for a dead queue). */
    internal var sink: (ByteArray) -> Boolean = child::send
    private val parser = LspWire.Parser()
    private var nextId = 1L
    internal val opened = LinkedHashMap<String, OpenDoc>()

    /** `textDocumentSync.change` from `initialize`: 0 means the server takes no `didChange`. */
    internal var syncChange = 0

    private fun send(msg: JsonElement) {
        if (!sink(LspWire.frame(msg))) error("lsp: server input closed")
    }

    fun notify(
        method: String,
        params: JsonElement,
    ) = send(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        },
    )

    fun request(
        method: String,
        params: JsonElement,
    ): JsonElement = requestWithin(method, params, Lsp.timeoutMs())

    fun requestWithin(
        method: String,
        params: JsonElement,
        budgetMs: Long,
    ): JsonElement {
        val id = JsonPrimitive(nextId++)
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            },
        )
        val deadline = TimeSource.Monotonic.markNow() + budgetMs.milliseconds
        while (true) {
            val msg =
                runCatching { parser.next() }.getOrElse { throw dead(it.message ?: "lsp: bad frame") }
                    ?: when (val b = child.read(deadline)) {
                        null -> {
                            throw expire(method)
                        }

                        else -> {
                            if (b.isEmpty()) throw dead("lsp: eof")
                            parser.feed(b)
                            continue
                        }
                    }
            if (msg["method"] != null && msg["id"].let { it != null && it !is JsonNull }) {
                replyServer(msg)
                continue
            }
            if (msg["id"] == id) {
                msg["error"]?.let { throw Refused("lsp $method: $it") }
                return msg["result"] ?: JsonNull
            }
        }
    }

    /** The budget ran out: the server is killed and reaped so no late reply is taken for a later request. */
    private fun expire(method: String): Exception {
        child.kill()
        return IllegalStateException("lsp: timeout waiting for $method")
    }

    internal fun dead(e: String): Exception {
        val st = child.tryWait()
        val err = runCatching { readToString(errPath) }.getOrDefault("").trim()
        val msg =
            when {
                st != null && err.isNotEmpty() -> "$e; exited $st; stderr: $err"
                st != null -> "$e; exited $st"
                err.isNotEmpty() -> "$e; stderr: $err"
                else -> e
            }
        return IllegalStateException(msg)
    }

    private fun replyServer(msg: JsonElement) {
        val result: JsonElement =
            when (msg["method"].str) {
                "workspace/configuration" -> {
                    val n = (msg["params"]["items"] as? JsonArray)?.size ?: 1
                    JsonArray(List(n) { JsonObject(emptyMap()) })
                }

                "workspace/workspaceFolders" -> {
                    Lsp.folders(root)
                }

                else -> {
                    JsonNull
                }
            }
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", msg["id"] ?: JsonNull)
                put("result", result)
            },
        )
    }

    /** Re-sync every open document with its file, so answers never come from an earlier query's snapshot. */
    fun refreshOpen() {
        for (uri in opened.keys.toList()) didOpen(uri)
    }

    /** Makes the server's copy of [uri] the file's current text; nothing is recorded until it was sent. */
    fun didOpen(uri: String) {
        val text = runCatching { readToString(LspWire.pathFromFileUri(uri)) }.getOrNull() ?: return close(uri)
        val doc = opened[uri] ?: return open(uri, text, 1)
        if (doc.text == text) return
        val version = doc.version + 1
        if (syncChange == 0) {
            close(uri)
            return open(uri, text, version)
        }
        notify(
            "textDocument/didChange",
            buildJsonObject {
                putJsonObject("textDocument") {
                    put("uri", uri)
                    put("version", version)
                }
                putJsonArray("contentChanges") { add(buildJsonObject { put("text", text) }) }
            },
        )
        opened[uri] = OpenDoc(version, text)
    }

    private fun open(
        uri: String,
        text: String,
        version: Long,
    ) {
        val languageId =
            when (extension(LspWire.pathFromFileUri(uri))) {
                "rs" -> "rust"
                "dart" -> "dart"
                else -> "plaintext"
            }
        notify(
            "textDocument/didOpen",
            buildJsonObject {
                putJsonObject("textDocument") {
                    put("uri", uri)
                    put("languageId", languageId)
                    put("version", version)
                    put("text", text)
                }
            },
        )
        opened[uri] = OpenDoc(version, text)
    }

    private fun close(uri: String) {
        if (uri !in opened) return
        notify("textDocument/didClose", buildJsonObject { putJsonObject("textDocument") { put("uri", uri) } })
        opened.remove(uri)
    }

    /** `exit`, kill, reap, and the stderr file removed (Rust `Drop`); never waits for a peer that stopped reading. */
    override fun close() {
        runCatching {
            sink(
                LspWire.frame(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", "exit")
                    },
                ),
            )
        }
        child.kill()
        if (errPath.isNotEmpty()) runCatching { removeFile(errPath) }
    }

    companion object {
        private val lock = rtok.sys.Mutex()
        private var cached: Pair<String, LspSession>? = null

        /** Runs [f] on the cached server for [root], (re)starting it when the root changed or it exited. */
        fun <T> with(
            root: String,
            f: (LspSession) -> T,
        ): T =
            lock.hold {
                val key = Index.canon(root)
                val cur = cached
                if (cur == null || cur.first != key || cur.second.child.tryWait() != null) {
                    // Reap the old server first: both share one stderr path.
                    cur?.second?.close()
                    cached = null
                    cached = key to Lsp.spawn(root)
                }
                val s = cached!!.second
                s.refreshOpen()
                f(s)
            }

        /** Installs [s] as the cached session (tests). */
        internal fun install(
            key: String,
            s: LspSession,
        ) = lock.hold { cached = key to s }

        /** Stops the cached language server and removes its stderr file (lsp.rs `shutdown`). */
        fun shutdown() =
            lock.hold {
                cached?.second?.close()
                cached = null
            }
    }
}

/** Process-wide wait cap for one server step (`[plugins.graph] lsp_timeout_ms`). */
internal object LspTimeout {
    const val DEFAULT_MS = 40_000L
    val ms = AtomicLong(DEFAULT_MS)
}
