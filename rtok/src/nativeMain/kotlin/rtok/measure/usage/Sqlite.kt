// Port of src/measure/usage/sqlite.rs: OpenCode and Kilo keep each message's token counts in the `data` JSON of their
// own SQLite database (`<host>.db`, or a channel's `<host>-<channel>.db`), opened read-only and never written.
package rtok.measure.usage

import kotlinx.serialization.json.JsonElement
import rtok.measure.HostSlices
import rtok.measure.Legs
import rtok.measure.UsageLogs
import rtok.measure.count
import rtok.measure.field
import rtok.measure.int
import rtok.measure.parseJson
import rtok.measure.pointer
import rtok.measure.str
import rtok.store.UsageSlice
import rtok.store.readHostMessages
import rtok.sys.canon
import rtok.sys.fileName
import rtok.sys.isFile
import rtok.sys.join
import rtok.sys.listDir

internal object Sqlite {
    private const val MS = 1000L

    fun slices(
        host: String,
        dirs: List<String>,
        since: Long,
    ): HostSlices {
        val out = ArrayList<UsageSlice>()
        var unreadable: String? = null
        for (path in databases(host, dirs)) {
            runCatching { read(host, path, since) }.onSuccess { out += it }.onFailure { unreadable = unreadable ?: path }
        }
        return out to unreadable
    }

    private fun databases(
        host: String,
        dirs: List<String>,
    ): List<String> = dirs.flatMap { d -> runCatching { listDir(d) }.getOrDefault(emptyList()).map { join(d, it) } }.filter { isDatabase(host, it) }.sorted()

    private fun isDatabase(
        host: String,
        path: String,
    ): Boolean {
        val name = fileName(path) ?: ""
        val stem = name.removeSuffix(".db").takeIf { name.endsWith(".db") && it.startsWith(host) }
        val rest = stem?.removePrefix(host)
        return rest != null && (rest.isEmpty() || rest.startsWith('-')) && isFile(path)
    }

    /** A `file:` URI with `mode=ro`, the path percent-escaped so `?`, `#` and `%` in it stay part of the path. */
    fun readOnlyUri(path: String): String = "file://" + canon(path).encodeToByteArray().joinToString("") { b -> escape(b.toInt() and BYTE) } + "?mode=ro"

    private const val BYTE = 0xff

    private fun escape(b: Int): String = b.toChar().takeIf { it.isLetterOrDigit() && b < ASCII || it in "/-._~" }?.toString() ?: "%" + b.toString(HEX).uppercase().padStart(2, '0')

    private const val ASCII = 0x80
    private const val HEX = 16

    private fun read(
        host: String,
        path: String,
        since: Long,
    ): List<UsageSlice> = readHostMessages(readOnlyUri(path), since * MS).mapNotNull { (session, created, data) -> request(host, session, created, data, since) }

    private fun request(
        host: String,
        session: String,
        createdMs: Long,
        data: String,
        since: Long,
    ): UsageSlice? {
        val v: JsonElement = parseJson(data)?.takeIf { it.field("role").str() == "assistant" && it.field("tokens") != null } ?: return null
        val t = v.field("tokens")
        val cache = t.field("cache")
        val ms = v.pointer("/time/created").int() ?: createdMs
        val legs = Legs(t.count("input"), cache.count("write"), cache.count("read"), t.count("output") + t.count("reasoning"))
        return UsageLogs.slice(host, v.field("providerID").str() ?: host, session, v.field("modelID").str(), ms / MS, legs, since)
    }
}
