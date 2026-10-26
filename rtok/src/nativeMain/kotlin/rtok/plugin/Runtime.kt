// Port of src/plugin.rs — the host side of the plugin contract: [Runtime] owns config + store
// and implements every capability interface of rtok-plugin-sdk by delegating to the one store.
package rtok.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import rtok.config.Config
import rtok.config.MemoryEmbed
import rtok.config.leaf
import rtok.plugin.sdk.*
import rtok.store.*
import rtok.sys.createDirAll
import rtok.sys.pid
import rtok.sys.removeDirAll
import rtok.sys.scalarList
import rtok.sys.tempDir
import kotlin.concurrent.AtomicReference
import kotlin.time.TimeSource

/** The longest prefix of [text] that estimates to at most [budget] tokens (one estimate, then a short trim). */
fun fitBudget(
    cx: rtok.plugin.sdk.Host,
    text: String,
    cls: Class,
    budget: UInt,
): String {
    val est = cx.estimate(text, cls)
    if (est <= budget) return text
    val cps = text.scalarList()
    val keep = (cps.size.toULong() * budget.toULong() / est.toULong()).toInt()
    var n = keep
    var out = cps.join(n)
    while (n > 0 && cx.estimate(out, cls) > budget) out = cps.join(--n)
    return out
}

private fun List<String>.join(n: Int) = subList(0, n).joinToString("")

/**
 * T65.1: a same-session content-hash hit becomes a pointer. Empty or shorter-than-the-pointer bodies
 * stay as they are (fail open, no inflation). [context] scopes the hit to the caller's window (T127).
 */
fun identicalResult(
    host: Capabilities,
    plugin: String,
    body: ByteArray,
    context: String?,
): String? {
    if (body.isEmpty()) return null
    val hit = runCatching { host.archiveInSession(hexSha256(body), context) }.getOrNull() ?: return null
    val n = if (hit.turns < 1u) 1u else hit.turns
    val short = rtok.shortId(hit.id)
    val msg = "[rtok $short · identical to a result $n turns ago · expand: rtok expand $short]"
    if (body.size <= msg.encodeToByteArray().size) return null
    val text = runCatching { body.decodeToString(throwOnInvalidSequence = true) }.getOrDefault("")
    runCatching {
        host.record(
            Measurement(
                plugin,
                "dedup",
                body.size.toULong(),
                msg.encodeToByteArray().size.toULong(),
                host.estimate(text, Class.Code),
                host.estimate(msg, Class.Code),
                hit.id,
                host.callId(),
            ),
        )
    }
    return msg
}

/** The `kv` key behind [Host.pluginStateSet] (T419): plugin id first, so one prefix read gets its state. */
fun pluginStateKey(
    plugin: String,
    key: String,
) = "plugin:$plugin:$key"

/** `[plugins.memory.embed]` as store settings. `provider = "http"` needs an HTTP client (not yet approved), so it stays offline. */
fun embedSettings(e: MemoryEmbed) = EmbedSettings(e.enabled, e.provider, e.model, e.dimensions, e.hybrid, remote = null)

/** Everything a plugin may touch: config, the store, the session. Close it to drop an in-memory scratch dir. */
class Runtime private constructor(
    var config: Config,
    val store: Store,
    val session: String,
) : Capabilities,
    AutoCloseable {
    /** The `calls` row this dispatch runs under; `recordCall`/`recordPluginRun` nest under it. */
    var callId: Int? = null

    /** `<event>:<tool_use_id>` when the surface knows it: a repeated delivery records once (T245). */
    var once: String? = null

    /** Host process cwd; `project` is derived from it at write time (T25.0). */
    var cwd: String? = null

    /** Resolved once from `[hook] host`; unknown slugs fall back to `other` (6). */
    val hostId: Int? = runCatching { store.hostId(config.hook.host) }.getOrNull() ?: OTHER_HOST
    private val watchPending = AtomicReference<Map<String, Set<String>>>(emptyMap())
    private val deferred = AtomicReference<List<Measurement>?>(null)
    private var archiveScope: AtomicReference<Set<String>>? = null
    private var scratch: String? = null

    init {
        store.setStoreRaw(config.core.storeRaw)
    }

    companion object {
        private const val OTHER_HOST = 6
        private val clock = TimeSource.Monotonic.markNow()
        private var seq = 0

        fun open(
            config: Config,
            session: String,
            wait: LockWait = LockWait.STEADY,
        ) = Runtime(config, Store.openWith(config.core.dbPath, wait), session)

        /** Default config with every path under a fresh temp dir + an in-memory store (tests, examples). */
        fun inMemory(session: String): Runtime {
            val dir = "${tempDir()}/rtok-mem-${pid()}-${clock.elapsedNow().inWholeNanoseconds}-${seq++}"
            createDirAll(dir)
            val base = Config().rebasePaths(dir)
            val cfg = base.copy(log = base.log.copy(path = "$dir/rtok.log"))
            return Runtime(cfg, Store.openInMemory(), session).also { it.scratch = dir }
        }
    }

    override fun close() {
        runCatching { store.close() }
        scratch?.let(::removeDirAll)
        scratch = null
    }

    /** Remote surfaces start with no archive grants; only bytes this runtime archived may expand. */
    fun restrictArchives() {
        archiveScope = AtomicReference(emptySet())
    }

    /** At most two granted ids starting with [prefix], or null when archives are not restricted. */
    fun scopedArchiveIds(prefix: String): List<String>? =
        archiveScope
            ?.value
            ?.filter { it.startsWith(prefix) }
            ?.sorted()
            ?.take(2)

    // ---- Host
    override fun session() = session

    override fun cwd() = cwd

    override fun estimate(
        text: String,
        cls: Class,
    ): UInt = rtok.estimate(text, cls, config.estimator).toUInt()

    override fun callId() = callId

    override fun record(m: Measurement) {
        while (true) {
            val q = deferred.value ?: break
            if (deferred.compareAndSet(q, q + m)) return
        }
        store.insertMeasurementOnce(session, m, once)
    }

    /** Queue later [record]s until [flushMeasurements] writes them in one transaction (hook budget, D1). */
    fun deferMeasurements() {
        deferred.value = emptyList()
    }

    /** Write the queue in order and go back to writing at once; never fails the caller. */
    fun flushMeasurements() {
        val q = deferred.getAndSet(null).orEmpty()
        if (q.isNotEmpty()) runCatching { store.insertMeasurementsOnce(session, q, once) }
    }

    override fun pluginStateSet(
        plugin: String,
        key: String,
        value: String,
    ) = store.kvSet(pluginStateKey(plugin, key), value)

    override fun pluginStateGet(
        plugin: String,
        key: String,
    ) = store.kvGet(pluginStateKey(plugin, key))

    override fun recordCall(
        surface: String,
        kind: String,
        name: String?,
    ) = insertCall(surface, kind, null, name)

    override fun recordPluginRun(
        surface: String,
        plugin: String,
    ) = insertCall(surface, "plugin_run", plugin, null)

    private fun insertCall(
        surface: String,
        kind: String,
        plugin: String?,
        name: String?,
    ): Int {
        store.upsertSession(session, hostId, cwd?.let { rtok.projectName(it) }, cwd, null)
        val id = store.insertCall(session, surface, kind, hostId, null, null, plugin, name)
        callId?.let { store.setCallParent(id, it) }
        return id
    }

    override fun recordTokens(
        callId: Int,
        plugin: String?,
        phase: String,
        source: String,
        tokens: Long,
    ) = store.insertTokens(callId, plugin, phase, source, tokens)

    /** Never throws to a plugin (fail open): the file line and the `logs` row each swallow their own errors. */
    override fun log(
        level: String,
        source: String,
        name: String,
        message: String,
    ) = rtok.recordLog(config, store, session, null, level, source, name, message)

    /** One config section by dotted path; unknown paths are null (the plugin's defaults). */
    override fun configJson(path: String): JsonElement = leaf(config.toJson(), path) ?: JsonNull

    override fun graphWatchPending(root: String) = watchPending.value[root].orEmpty().sorted()

    override fun publishGraphWatchPending(
        root: String,
        paths: List<String>,
    ) {
        while (true) {
            val cur = watchPending.value
            val next = if (paths.isEmpty()) cur - root else cur + (root to paths.toSet())
            if (watchPending.compareAndSet(cur, next)) return
        }
    }

    // ---- Archive
    private val archiveDir get() = config.core.archiveDir

    override fun putArchive(body: ByteArray) = putArchiveFor(body, null)

    override fun putArchiveFor(
        body: ByteArray,
        context: String?,
    ): String {
        val id = store.putArchiveFor(session, body, archiveDir, context)
        archiveScope?.let { s ->
            while (true) {
                val cur = s.value
                if (s.compareAndSet(cur, cur + id)) break
            }
        }
        return id
    }

    override fun getArchive(id: String) = store.getArchive(id, archiveDir)

    override fun archiveSize(id: String) = store.archiveSize(id, archiveDir)

    override fun archiveDecision(toolUseId: String) = store.archiveDecision(session, toolUseId)

    override fun putArchiveDecision(
        toolUseId: String,
        archiveId: String,
        pointer: String,
    ) = store.putArchiveDecision(toolUseId, archiveId, session, pointer)

    override fun markExpanded(archiveId: String) = store.markExpanded(archiveId)

    /** Errors fail open: the caller prints the body instead of a pointer. */
    override fun archiveInSession(
        sha256: String,
        context: String?,
    ) = runCatching { store.archiveInSession(session, sha256, context) }.getOrNull()?.let { (id, turns) -> ArchiveHit(id, turns) }

    override fun archiveSummary(
        archiveId: String,
        version: String,
    ) = store.archiveSummary(archiveId, version)

    override fun sessionLiveArchives(session: String) = store.sessionLiveArchives(session)

    // ---- Notes
    private val embed get() = embedSettings(config.plugins.memory.embed)

    override fun upsertNote(
        project: String?,
        kind: String,
        title: String,
        body: String,
    ): Int {
        val (id, _) = store.upsertNote(project, kind, title, body)
        store.upsertNoteEmbedding(id, title, body, embed)
        return id
    }

    /** T209: one row per (project, kind, title), so a repeat key is an upsert, never an error. */
    override fun insertNote(
        project: String?,
        kind: String,
        title: String,
        body: String,
    ) = upsertNote(project, kind, title, body)

    override fun latestNote(kind: String) = store.latestNote(kind)

    override fun latestNoteForProject(
        project: String?,
        kindPrefix: String,
    ) = store.latestNoteForProject(project, kindPrefix)

    override fun latestSessionNote(project: String?) = store.latestSessionNote(project)

    override fun listNoteTitles(
        project: String?,
        limit: UInt,
    ) = store.listNoteTitles(project, limit)

    override fun getNoteBody(id: Int) = store.getNoteBody(id)

    override fun searchNotes(
        query: String,
        limit: UInt,
    ) = store.searchNotes(query, limit)

    override fun searchNotesHybridStored(
        query: String,
        limit: UInt,
    ) = store.searchNotesHybridStored(query, limit, embed)

    override fun setNoteFiles(
        id: Int,
        paths: List<String>,
    ) = store.setNoteFiles(id, paths)

    override fun notesForFiles(
        project: String?,
        paths: List<String>,
        limit: UInt,
    ) = store.notesForFiles(project, paths, limit)

    override fun touchNote(id: Int) = store.touchNote(id)

    override fun insertObservation(obs: NewObservation) = store.insertObservationOnce(obs, once)

    override fun searchObservations(
        project: String?,
        query: String,
        limit: UInt,
    ) = store.searchObservations(project, query, limit)

    override fun observationsForFiles(
        project: String?,
        paths: List<String>,
        limit: UInt,
    ) = store.observationsForFiles(project, paths, limit)

    override fun recentObservations(
        sessionId: String,
        limit: UInt,
    ) = store.recentObservations(sessionId, limit)

    override fun observationNarrative(id: Int) = store.observationNarrative(id)

    // ---- ReadCache
    override fun putReadCache(
        path: String,
        sha256: String,
        archiveId: String?,
    ) = store.putReadCache(session, path, sha256, archiveId)

    override fun getReadCache(path: String) = store.getReadCache(session, path)

    override fun clearReadCache(path: String) = store.clearReadCache(session, path)

    override fun readCacheKeys() = store.readCacheKeys(session)

    override fun forgetSeen(context: String?) = store.forgetSeen(session, context)

    /** Fails closed: an unreadable store counts as shared, so no pointer is guessed. */
    override fun sharedSession() = runCatching { store.sessionHasSubagent(session) }.getOrDefault(true)

    // ---- Ledger
    override fun lastMeasurementRef(
        plugin: String,
        kind: String,
    ) = store.lastMeasurementRef(session, plugin, kind)

    override fun recentHookInputs(limit: Long) = store.recentHookInputs(session, limit)

    override fun recentHookInputsForEvent(
        event: String,
        limit: Long,
    ) = store.recentHookInputsForEvent(session, event, limit)

    override fun callsSince(ts: Long) = store.callsSince(session, ts)

    // ---- Symbols
    override fun symbolCount(root: String) = store.symbolCount(root)

    override fun symbolStat(
        root: String,
        path: String,
    ) = store.symbolStat(root, path)

    override fun symbolStats(root: String) = store.symbolStats(root)

    override fun touchSymbols(
        root: String,
        path: String,
        mtime: Long,
        size: Long,
    ) = store.touchSymbols(root, path, mtime, size)

    override fun replaceSymbols(
        root: String,
        path: String,
        fileSha: String,
        stat: Pair<Long, Long>,
        rows: List<SymbolRow>,
    ) = store.replaceSymbols(root, path, fileSha, stat, rows)

    override fun replaceSymbolFiles(
        root: String,
        files: List<SymbolFile>,
    ) = store.replaceSymbolFiles(root, files)

    override fun deleteSymbolsMissing(
        root: String,
        keep: Set<String>,
    ) = store.deleteSymbolsMissing(root, keep)

    override fun markSymbolsStale(absPath: String) = store.markSymbolsStale(absPath)

    override fun symbolFileCount(root: String) = store.symbolFileCount(root)

    override fun symbolPending(
        root: String,
        rootPath: String,
    ) = store.symbolPending(root, rootPath)

    override fun symbolIndexedAt(root: String) = store.symbolIndexedAt(root)

    override fun touchSymbolIndexedAt(
        root: String,
        ts: Long,
    ) = store.touchSymbolIndexedAt(root, ts)

    override fun extractorFingerprint(root: String) = store.extractorFingerprint(root)

    override fun setExtractorFingerprint(
        root: String,
        fp: String,
    ) = store.setExtractorFingerprint(root, fp)

    override fun symbolDefs(
        root: String,
        name: String,
    ) = store.symbolDefs(root, name).map(::symDef)

    override fun symbolFileDefs(
        root: String,
        path: String,
    ) = store.symbolFileDefs(root, path).map(::symDef)

    override fun symbolRefGroups(
        root: String,
        name: String,
    ) = store.symbolRefGroups(root, name).map { RefGroupRow(it.a, it.b, it.c, it.d) }

    override fun symbolCallees(
        root: String,
        name: String,
    ) = store.symbolCallees(root, name).map { Callee(it.a, it.b, it.c, it.d) }

    override fun symbolImpact(
        root: String,
        name: String,
        depth: UInt,
    ) = store.symbolImpact(root, name, depth)

    override fun symbolDeadCandidates(root: String) = store.symbolDeadCandidates(root).map { DeadCandidate(it.a, it.b, it.c, it.d) }

    override fun symbolReferencedNames(
        root: String,
        names: List<String>,
    ) = store.symbolReferencedNames(root, names)

    override fun symbolNamePrefix(
        root: String,
        prefix: String,
        limit: Long,
    ) = store.symbolNamePrefix(root, prefix, limit)

    override fun symbolPaths(
        root: String,
        from: String,
        to: String,
        depth: UInt,
    ) = store.symbolPaths(root, from, to, depth)

    override fun symbolImports(
        root: String,
        path: String,
    ) = store.symbolImports(root, path)

    override fun symbolImportedDefs(
        root: String,
        name: String,
    ) = store.symbolImportedDefs(root, name)

    override fun symbolNameFreq(
        root: String,
        name: String,
    ) = store.symbolNameFreq(root, name)

    override fun rebuildSymbolIdf(root: String) = store.rebuildSymbolIdf(root)

    override fun symbolImporters(
        root: String,
        module: String,
    ) = store.symbolImporters(root, module)

    override fun symbolImportFollow(
        root: String,
        name: String,
    ) = store.symbolImportFollow(root, name)

    override fun symbolTopRefs(
        root: String,
        limit: Long,
    ) = store.symbolTopRefs(root, limit).map { TopRef(it.a, it.b, it.c, it.d) }

    override fun symbolFileScan(root: String) = store.symbolFileScan(root).map { FileScanRow(it.a, it.b, it.c, it.d) }

    override fun fileRankGet(root: String) = store.fileRankGet(root)

    override fun fileRankPut(
        root: String,
        graph: String,
    ) = store.fileRankPut(root, graph)

    override fun symbolSpan(
        root: String,
        path: String,
        name: String,
        kind: String,
        line: Int,
    ) = store.symbolSpan(root, path, name, kind, line)

    override fun symbolFts(
        root: String,
        query: String,
        limit: Long,
    ) = store.symbolFts(root, query, limit).map { FtsHit(it.a, it.b, it.c, it.d) }
}

private fun symDef(q: Quad<String, String, Int, Int>) = SymDef(q.a, q.b, q.c, q.d)
