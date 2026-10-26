// mcp.rs `apply_roots_response`: the first `file://` directory root becomes the cwd, and every
// directory root is an allowed root of the read path guard.
package rtok.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

object Roots {
    fun apply(result: JsonObject) {
        val paths =
            (result["roots"] as? JsonArray).orEmpty().mapNotNull { r ->
                (r as? JsonObject)?.let { Protocol.str(it, "uri") }?.let(::filePath)
            }
        paths.firstOrNull()?.takeIf { rtok.sys.isDir(it) }?.let { runCatching { rtok.sys.setCwd(it) } }
        rtok.plugins.read.Roots
            .setClientRoots(paths.filter { rtok.sys.isDir(it) })
    }

    /** The path of a `file://` URI (local host only), percent-decoded; null for anything else. */
    internal fun filePath(uri: String): String? {
        val rest =
            when {
                uri.startsWith("file:///") -> uri.removePrefix("file://")
                uri.startsWith("file://localhost/") -> uri.removePrefix("file://localhost")
                else -> return null
            }
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < rest.length) {
            val c = rest[i]
            if (c == '%' && i + 2 <= rest.lastIndex) {
                val b = rest.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes += b.toByte()
                i += 3
            } else {
                c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
