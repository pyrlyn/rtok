// Port of the wire half of src/plugins/graph/lsp.rs: `Content-Length` framing (`write_msg`/`read_msg`), file
// URIs (`file_uri`, `path_from_file_uri`, percent coding, `rel`), `sync_kind` and `kind_name`, plus the small
// JSON accessors the session and the tools read server answers with.
package rtok.plugins.graph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import rtok.json.StrictJson
import rtok.project.Refs

internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.getOrNull(i)

internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal val JsonElement?.list: List<JsonElement> get() = (this as? JsonArray).orEmpty()

object LspWire {
    private const val HEX = 16
    private const val NIBBLE = 4
    private const val MAX_SYNC = 2L

    /** One JSON-RPC frame: `Content-Length: n\r\n\r\n` and the body. */
    fun frame(v: JsonElement): ByteArray {
        val body = Json.encodeToString(JsonElement.serializer(), v).encodeToByteArray()
        return "Content-Length: ${body.size}\r\n\r\n".encodeToByteArray() + body
    }

    /** Splits a byte stream into frames; [next] throws on a malformed header or body. */
    class Parser {
        private var buf = ByteArray(0)

        fun feed(b: ByteArray) {
            buf += b
        }

        /** The next whole frame, or null until more bytes arrive. */
        fun next(): JsonElement? {
            var pos = 0
            var len: Int? = null
            while (true) {
                val nl = (pos until buf.size).firstOrNull { buf[it] == '\n'.code.toByte() } ?: return null
                val line = buf.copyOfRange(pos, nl + 1).decodeToString()
                pos = nl + 1
                if (line == "\r\n" || line == "\n") break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).equals("content-length", ignoreCase = true)) {
                    len = line.substring(colon + 1).trim().toIntOrNull() ?: error("Content-Length")
                }
            }
            val n = len ?: error("lsp: no Content-Length")
            if (buf.size - pos < n) return null
            val body = buf.copyOfRange(pos, pos + n).decodeToString()
            buf = buf.copyOfRange(pos + n, buf.size)
            return runCatching { StrictJson.parse(body) }.getOrElse { error("lsp json: ${it.message}") }
        }
    }

    /** Every whole frame in [bytes] (the test peers' logs). */
    fun frames(bytes: ByteArray): List<JsonElement> {
        val p = Parser().apply { feed(bytes) }
        return generateSequence { runCatching { p.next() }.getOrNull() }.toList()
    }

    /** RFC 8089 path: every octet outside the unreserved set is `%XX`; `/` and a `C:` drive stay. */
    fun percentEncodePath(s: String): String =
        s.split('/').joinToString("/") { seg ->
            if (seg.length == 2 && seg[1] == ':') {
                seg
            } else {
                buildString {
                    for (b in seg.encodeToByteArray()) {
                        val c = b.toInt().toChar()
                        if (b >= 0 && (c.isLetterOrDigit() || c in "-._~")) append(c) else append("%" + (b.toInt() and 0xff).toString(HEX).uppercase().padStart(2, '0'))
                    }
                }
            }
        }

    fun percentDecodePath(s: String): String {
        val bytes = s.encodeToByteArray()
        val out = ArrayList<Byte>(bytes.size)
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) {
                val h = bytes[i + 1].toInt().toChar().digitToIntOrNull(HEX)
                val l = bytes[i + 2].toInt().toChar().digitToIntOrNull(HEX)
                if (h != null && l != null) {
                    out += ((h shl NIBBLE) or l).toByte()
                    i += 3
                    continue
                }
            }
            out += bytes[i++]
        }
        return out.toByteArray().decodeToString()
    }

    fun fileUri(p: String): String {
        val s = percentEncodePath((rtok.sys.canonicalize(p) ?: p).replace('\\', '/'))
        return if (s.startsWith('/')) "file://$s" else "file:///$s"
    }

    fun pathFromFileUri(uri: String): String = percentDecodePath(uri.removePrefix("file://"))

    /** [uri]'s path relative to [root] (`pathdiff::diff_paths`), or the path itself. */
    fun rel(
        root: String,
        uri: String,
    ): String {
        val p = pathFromFileUri(uri)
        return if (p.startsWith('/') && root.startsWith('/')) Refs.diff(p, root).let { if (it == ".") "" else it } else p
    }

    /** `textDocumentSync` as a kind number or an options object's `change`; 0 (close and reopen) otherwise. */
    fun syncKind(init: JsonElement?): Int {
        val sync = init["capabilities"]["textDocumentSync"]
        return ((sync.long ?: sync["change"].long) ?: 0L).coerceIn(0L, MAX_SYNC).toInt()
    }

    /** LSP 3.17 `SymbolKind` names rtok prints. */
    @Suppress("MagicNumber")
    fun kindName(k: Long): String =
        when (k) {
            5L -> "class"
            6L -> "method"
            10L -> "enum"
            11L -> "interface"
            12L -> "function"
            13L -> "variable"
            23L -> "struct"
            else -> "symbol"
        }
}
