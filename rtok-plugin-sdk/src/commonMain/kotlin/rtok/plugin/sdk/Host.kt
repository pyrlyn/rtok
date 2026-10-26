// Port of crates/rtok-plugin-sdk/src/host.rs — what a plugin may ask the host to do.
// Errors are exceptions (Rust anyhow::Result); callers fail open.
package rtok.plugin.sdk

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.*

enum class Class { Code, Prose, Json, Cjk }

data class NoteHit(val id: Int, val title: String, val snippet: String)

class NewObservation(
    val sessionId: String, val project: String?, val obsType: String, val title: String,
    val narrative: String, val dedup: String, val files: List<String>,
)

data class ObsHit(val id: Int, val title: String, val sessionId: String, val snippet: String)
data class ArchiveDecision(val archiveId: String, val pointer: String, val expanded: Boolean)
data class ArchiveHit(val id: String, val turns: ULong)

interface Host {
    fun session(): String
    fun cwd(): String? = null
    fun estimate(text: String, cls: Class): UInt
    fun record(m: Measurement)
    fun pluginStateSet(plugin: String, key: String, value: String) {}
    fun pluginStateGet(plugin: String, key: String): String? = null
    fun recordCall(surface: String, kind: String, name: String?): Int
    fun recordPluginRun(surface: String, plugin: String): Int
    fun recordTokens(callId: Int, plugin: String?, phase: String, source: String, tokens: Long)
    fun log(level: String, source: String, name: String, message: String)
    /** One configuration section as JSON by dotted path, or JsonNull. */
    fun configJson(path: String): JsonElement
    fun callId(): Int? = null
    fun graphWatchPending(root: String): List<String> = emptyList()
    fun publishGraphWatchPending(root: String, paths: List<String>) {}
}

interface Archive {
    fun putArchive(body: ByteArray): String
    fun putArchiveFor(body: ByteArray, context: String?): String = putArchive(body)
    fun getArchive(id: String): ByteArray?
    fun archiveSize(id: String): ULong? = null
    fun archiveDecision(toolUseId: String): ArchiveDecision?
    fun putArchiveDecision(toolUseId: String, archiveId: String, pointer: String)
    fun markExpanded(archiveId: String): Int
    fun archiveInSession(sha256: String, context: String?): ArchiveHit? = null
    fun archiveSummary(archiveId: String, version: String): String? = null
    fun sessionLiveArchives(session: String): List<Triple<String, String, Long>> = emptyList()
}

interface Notes {
    fun upsertNote(project: String?, kind: String, title: String, body: String): Int
    fun insertNote(project: String?, kind: String, title: String, body: String): Int
    fun latestNote(kind: String): String?
    fun latestNoteForProject(project: String?, kindPrefix: String): String?
    fun latestSessionNote(project: String?): String? = null
    fun listNoteTitles(project: String?, limit: UInt): List<Pair<Int, String>>
    fun getNoteBody(id: Int): String?
    fun searchNotes(query: String, limit: UInt): List<NoteHit>
    fun searchNotesHybridStored(query: String, limit: UInt): List<NoteHit> = searchNotes(query, limit)
    fun setNoteFiles(id: Int, paths: List<String>) {}
    fun notesForFiles(project: String?, paths: List<String>, limit: UInt): List<NoteHit> = emptyList()
    fun touchNote(id: Int) {}
    fun insertObservation(obs: NewObservation): Int? = null
    fun searchObservations(project: String?, query: String, limit: UInt): List<ObsHit> = emptyList()
    fun observationsForFiles(project: String?, paths: List<String>, limit: UInt): List<ObsHit> = emptyList()
    fun recentObservations(sessionId: String, limit: UInt): List<ObsHit> = emptyList()
    fun observationNarrative(id: Int): String? = null
}

interface ReadCache {
    fun putReadCache(path: String, sha256: String, archiveId: String?)
    fun getReadCache(path: String): Pair<String?, Long>?
    fun clearReadCache(path: String)
    fun readCacheKeys(): List<String> = emptyList()
    fun forgetSeen(context: String?) {}
    fun sharedSession(): Boolean = false
}

interface Ledger {
    fun recentHookInputs(limit: Long): List<String>
    fun recentHookInputsForEvent(event: String, limit: Long): List<String> = recentHookInputs(limit).filter { body ->
        runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("hook_event_name")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } }.getOrNull() == event
    }
    fun callsSince(ts: Long): Long
    fun lastMeasurementRef(plugin: String, kind: String): String?
}

/** One indexed symbol; the first six fields are the graph edge. */
data class SymbolRow(
    val name: String, val kind: String, val line: Int, val isDef: Boolean, val endLine: Int, val scope: String,
    val startByte: Long = 0, val endByte: Long = 0, val contentHash: String = "", val signature: String = "", val doc: String = "",
)

data class SymbolSpan(val startByte: ULong, val endByte: ULong, val contentHash: String, val fileSha: String)

/** `(path, sha, (mtime, size), rows)` per file. */
data class SymbolFile(val path: String, val sha: String, val stat: Pair<Long, Long>, val rows: List<SymbolRow>)

interface Symbols {
    fun symbolCount(root: String): Long
    fun symbolStat(root: String, path: String): Triple<String, Long, Long>?
    fun symbolStats(root: String): Map<String, Triple<String, Long, Long>>
    fun touchSymbols(root: String, path: String, mtime: Long, size: Long)
    fun replaceSymbols(root: String, path: String, fileSha: String, stat: Pair<Long, Long>, rows: List<SymbolRow>): Int
    fun replaceSymbolFiles(root: String, files: List<SymbolFile>): Int
    fun deleteSymbolsMissing(root: String, keep: Set<String>): Int
    fun markSymbolsStale(absPath: String)
    fun symbolFileCount(root: String): Long = 0
    fun symbolPending(root: String, rootPath: String): List<String> = emptyList()
    fun symbolIndexedAt(root: String): Long? = null
    fun touchSymbolIndexedAt(root: String, ts: Long) {}
    fun extractorFingerprint(root: String): String? = null
    fun setExtractorFingerprint(root: String, fp: String) {}
    fun symbolDefs(root: String, name: String): List<SymDef>
    fun symbolRefGroups(root: String, name: String): List<RefGroupRow>
    fun symbolCallees(root: String, name: String): List<Callee> = emptyList()
    fun symbolImpact(root: String, name: String, depth: UInt): List<Triple<UInt, String, String>>
    fun symbolDeadCandidates(root: String): List<DeadCandidate> = emptyList()
    fun symbolReferencedNames(root: String, names: List<String>): Set<String> = emptySet()
    fun symbolNamePrefix(root: String, prefix: String, limit: Long): List<String> = emptyList()
    fun symbolPaths(root: String, from: String, to: String, depth: UInt): List<String> = emptyList()
    fun symbolFileDefs(root: String, path: String): List<SymDef> = emptyList()
    fun symbolImports(root: String, path: String): List<Pair<String, Int>> = emptyList()
    fun symbolImportedDefs(root: String, name: String): List<Pair<String, String>> = emptyList()
    fun symbolNameFreq(root: String, name: String): Pair<Long, Long> = 0L to 0L
    fun rebuildSymbolIdf(root: String) {}
    fun symbolImporters(root: String, module: String): List<Pair<String, Int>> = emptyList()
    fun symbolImportFollow(root: String, name: String): List<Pair<String, String>> = emptyList()
    fun symbolTopRefs(root: String, limit: Long): List<TopRef> = emptyList()
    fun symbolFileScan(root: String): List<FileScanRow> = emptyList()
    fun fileRankGet(root: String): String? = null
    fun fileRankPut(root: String, graph: String) {}
    fun symbolSpan(root: String, path: String, name: String, kind: String, line: Int): SymbolSpan? = null
    fun symbolFts(root: String, query: String, limit: Long): List<FtsHit> = emptyList()
}

/** `(path|name, kind, line, end_line)` — a definition. */
data class SymDef(val a: String, val kind: String, val line: Int, val endLine: Int)
/** `(path, kind, count, line)`. */
data class RefGroupRow(val path: String, val kind: String, val count: Long, val line: Int)
/** `(def_path, def_line, callee, first_line)`. */
data class Callee(val defPath: String, val defLine: Int, val callee: String, val firstLine: Int)
/** `(path, name, kind, line)`. */
data class DeadCandidate(val path: String, val name: String, val kind: String, val line: Int)
/** `(name, refs, path, line)`. */
data class TopRef(val name: String, val refs: Long, val path: String, val line: Int)
/** `(name, path, is_def, rows)`. */
data class FileScanRow(val name: String, val path: String, val isDef: Boolean, val rows: Long)
/** `(path, name, kind, line)`. */
data class FtsHit(val path: String, val name: String, val kind: String, val line: Int)

/** Everything a plugin may touch. */
interface Capabilities : Host, Archive, Notes, ReadCache, Ledger, Symbols

/** What a plugin is handed on every event: the host (delegated), plus the sub-agent window. */
class Ctx(val host: Capabilities, private val agent: String? = null) : Capabilities by host {
    fun agentId(): String? = agent

    /** This plugin's `[plugins.<id>]` section, or `default` when missing or unparsable. */
    fun <T> pluginConfig(id: String, de: DeserializationStrategy<T>, default: T): T = config("plugins.$id", de, default)

    /** A host-wide section by dotted path, or `default`. */
    fun <T> config(path: String, de: DeserializationStrategy<T>, default: T): T {
        val v = host.configJson(path); if (v is JsonNull) return default
        return runCatching { SECTION_JSON.decodeFromJsonElement(de, v) }.getOrDefault(default)
    }

    companion object { val SECTION_JSON = Json { ignoreUnknownKeys = true; isLenient = false; coerceInputValues = false } }
}
