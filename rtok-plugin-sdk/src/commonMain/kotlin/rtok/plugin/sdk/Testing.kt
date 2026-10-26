// Port of crates/rtok-plugin-sdk/src/testing.rs — an in-memory host for plugin tests.
package rtok.plugin.sdk

import kotlinx.serialization.json.*
import kotlin.math.ceil

open class MemoryHost(private val config: JsonElement = JsonObject(emptyMap())) : Capabilities {
    private val recordedList = ArrayList<Measurement>()
    private val loggedList = ArrayList<String>()
    private val blobs = HashMap<String, ByteArray>()
    private val decisions = LinkedHashMap<String, ArchiveDecision>()

    fun recorded(): List<Measurement> = recordedList.toList()
    fun logged(): List<String> = loggedList.toList()

    override fun session() = "memory"
    override fun estimate(text: String, cls: Class): UInt {
        val per = when (cls) { Class.Code -> 3.5f; Class.Prose -> 4.0f; Class.Json -> 2.8f; Class.Cjk -> 1.0f }
        return ceil(text.codePointCount() / per).toUInt()
    }
    override fun record(m: Measurement) { recordedList += m }
    override fun recordCall(surface: String, kind: String, name: String?) = 0
    override fun recordPluginRun(surface: String, plugin: String) = 0
    override fun recordTokens(callId: Int, plugin: String?, phase: String, source: String, tokens: Long) {}
    override fun log(level: String, source: String, name: String, message: String) { loggedList += "$level $source/$name: $message" }
    override fun configJson(path: String): JsonElement {
        var node: JsonElement = config
        for (k in path.split('.')) node = (node as? JsonObject)?.get(k) ?: return JsonNull
        return node
    }

    override fun putArchive(body: ByteArray): String {
        // Content-addressed (FNV-1a 64) so distinct bodies never share an id.
        val h = body.fold(0xcbf29ce484222325uL) { a, b -> (a xor (b.toULong() and 0xffu)) * 0x100000001b3uL }
        val id = "mem" + h.toString(16).padStart(16, '0'); blobs[id] = body.copyOf(); return id
    }
    override fun getArchive(id: String) = blobs[id]?.copyOf()
    override fun archiveSize(id: String) = blobs[id]?.size?.toULong()
    override fun archiveDecision(toolUseId: String) = decisions[toolUseId]
    override fun putArchiveDecision(toolUseId: String, archiveId: String, pointer: String) { decisions.getOrPut(toolUseId) { ArchiveDecision(archiveId, pointer, false) } }
    override fun markExpanded(archiveId: String): Int {
        var n = 0
        for ((k, d) in decisions.entries.toList()) if (d.archiveId == archiveId) { decisions[k] = d.copy(expanded = true); n++ }
        return n
    }

    override fun upsertNote(project: String?, kind: String, title: String, body: String) = 0
    override fun insertNote(project: String?, kind: String, title: String, body: String) = upsertNote(project, kind, title, body)
    override fun latestNote(kind: String): String? = null
    override fun latestNoteForProject(project: String?, kindPrefix: String): String? = null
    override fun listNoteTitles(project: String?, limit: UInt): List<Pair<Int, String>> = emptyList()
    override fun getNoteBody(id: Int): String? = null
    override fun searchNotes(query: String, limit: UInt): List<NoteHit> = emptyList()

    override fun putReadCache(path: String, sha256: String, archiveId: String?) {}
    override fun getReadCache(path: String): Pair<String?, Long>? = null
    override fun clearReadCache(path: String) {}

    override fun lastMeasurementRef(plugin: String, kind: String): String? = null
    override fun recentHookInputs(limit: Long): List<String> = emptyList()
    override fun callsSince(ts: Long) = 0L

    override fun symbolCount(root: String) = 0L
    override fun symbolStat(root: String, path: String): Triple<String, Long, Long>? = null
    override fun symbolStats(root: String): Map<String, Triple<String, Long, Long>> = emptyMap()
    override fun touchSymbols(root: String, path: String, mtime: Long, size: Long) {}
    override fun replaceSymbols(root: String, path: String, fileSha: String, stat: Pair<Long, Long>, rows: List<SymbolRow>) = 0
    override fun replaceSymbolFiles(root: String, files: List<SymbolFile>) = 0
    override fun deleteSymbolsMissing(root: String, keep: Set<String>) = 0
    override fun markSymbolsStale(absPath: String) {}
    override fun symbolDefs(root: String, name: String): List<SymDef> = emptyList()
    override fun symbolRefGroups(root: String, name: String): List<RefGroupRow> = emptyList()
    override fun symbolImpact(root: String, name: String, depth: UInt): List<Triple<UInt, String, String>> = emptyList()
}

/** Unicode scalar count, like Rust `str::chars().count()`. */
fun String.codePointCount(): Int { var n = 0; var i = 0; while (i < length) { if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) i++; i++; n++ }; return n }
