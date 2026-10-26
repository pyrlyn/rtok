// Port of src/plugins/memory/mod.rs — notes recall without an LLM extraction step: the plugin's hook
// surfaces (title index on SessionStart, prompt recall, observations, spawn brief) and the MCP/CLI calls.
package rtok.plugins.memory

import kotlinx.serialization.json.*
import rtok.plugin.Runtime
import rtok.plugin.embedSettings
import rtok.plugin.sdk.*
import rtok.projectName
import rtok.store.*
import rtok.sys.cwd

/** One line in the hook index: titles only; names the MCP tools that fetch bodies (T293). */
internal const val INDEX_GUIDE = "titles only; mem_search this turn; mem_get matching id for body"

private fun schema(
    required: List<String>,
    vararg props: Pair<String, String>,
) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { props.forEach { (k, t) -> putJsonObject(k) { put("type", t) } } }
    putJsonArray("required") { required.forEach { add(it) } }
}

object MemoryPlugin : Plugin {
    override fun manifest() = Manifest("memory", listOf(Surface.Mcp, Surface.Hook), true)

    override fun dashboardPage() = DashboardPage("Memory", "Recall notes and titles without an LLM extraction step.", true)

    override fun mcpTools() =
        listOf(
            // `kind` is not required: the handler defaults it to "note"; title/body are (T213).
            ToolDef("mem_save", "Save a note; same project+kind+title updates it.", schema(listOf("title", "body"), "kind" to "string", "title" to "string", "body" to "string", "project" to "string")),
            ToolDef("mem_search", "Search notes by FTS5; ids, titles, snippets.", schema(listOf("query"), "query" to "string", "limit" to "integer")),
            ToolDef("mem_pack", "Ranked notes in a token budget.", schema(listOf("query"), "query" to "string", "limit" to "integer", "max_tokens" to "integer")),
            ToolDef("mem_get", "Body by note id; see hook index.", schema(emptyList(), "id" to "integer", "obs" to "integer")),
            ToolDef("mem_update", "Retire or pin a note by id.", schema(listOf("id"), "id" to "integer", "retire" to "boolean", "superseded_by" to "integer", "pinned" to "boolean")),
            handoffTool(),
        )

    override fun sessionStart(
        ev: SessionStart,
        cx: Ctx,
    ) = recall(cx)

    override fun promptSubmit(
        ev: PromptSubmit,
        cx: Ctx,
    ) = rememberSave(ev, cx) ?: promptRecall(ev, cx)

    override fun postTool(
        ev: PostToolUse,
        cx: Ctx,
    ): String? {
        runCatching { capture(cx, ev) }
        return null
    }

    override fun subagentStart(
        ev: SubagentStart,
        cx: Ctx,
    ): Injection? {
        val cfg = memoryConfig(cx)
        if (!cfg.spawnBrief) return null
        return buildBrief(cx, cfg.spawnBriefTokens, ev.taskDescription)?.let { Injection("memory", it, 9u) }
    }
}

internal fun resolvedProject(cx: Ctx): String? = cx.cwd()?.let(::projectName) ?: runCatching { projectName(cwd()) }.getOrNull()

private fun emptyProjectLine(project: String?) = "memory project ${project ?: "none"}: no notes"

/** `(id, title, body token estimate)` of one index line. */
internal class Entry(
    val id: Int,
    var title: String,
    val tokens: UInt,
)

/** Capped index text, dropping the newest-last entry; a lone overflowing entry has its title halved, then is dropped. */
internal fun renderTitleIndex(
    cx: Ctx,
    entries: MutableList<Entry>,
    cap: UInt,
): String {
    while (true) {
        val text = (listOf(INDEX_GUIDE) + entries.map { "${it.id} ${it.title} (${it.tokens}t body)" }).joinToString("\n")
        if (cx.estimate(text, Class.Prose) <= cap) return text
        val first = entries.firstOrNull()
        when {
            entries.size > 1 -> {
                entries.removeLast()
            }

            first != null && first.title.isNotEmpty() -> {
                first.title =
                    first.title
                        .scalars()
                        .let { it.take(it.size / 2) }
                        .joinToString("")
            }

            else -> {
                entries.clear()
                return INDEX_GUIDE
            }
        }
    }
}

/** Byte length and token estimate of each note body, one read per note; a vanished body is absent. */
private fun bodySizes(
    cx: Ctx,
    ids: List<Int>,
): Map<Int, Pair<ULong, UInt>> =
    ids
        .mapNotNull { id ->
            runCatching { cx.getNoteBody(id) }.getOrNull()?.let { id to (it.encodeToByteArray().size.toULong() to cx.estimate(it, Class.Prose)) }
        }.toMap()

private fun satAdd(
    a: UInt,
    b: UInt,
) = if (UInt.MAX_VALUE - a < b) UInt.MAX_VALUE else a + b

/** The `(before_bytes, est_before)` a recall replaced: the bodies of the notes it kept. */
private fun bodiesBefore(
    sizes: Map<Int, Pair<ULong, UInt>>,
    entries: List<Entry>,
): Pair<ULong, UInt> = entries.mapNotNull { sizes[it.id] }.fold(0uL to 0u) { (b, e), (bb, t) -> (b + bb) to satAdd(e, t) }

private fun entries(
    cx: Ctx,
    rows: List<Pair<Int, String>>,
): Pair<Map<Int, Pair<ULong, UInt>>, MutableList<Entry>> {
    val sizes = bodySizes(cx, rows.map { it.first })
    return sizes to rows.map { (id, t) -> Entry(id, t, sizes[id]?.second ?: 0u) }.toMutableList()
}

private fun measure(
    cx: Ctx,
    kind: String,
    before: Pair<ULong, UInt>,
    text: String,
    ref: String?,
    callId: Int? = null,
) = runCatching {
    cx.record(Measurement("memory", kind, before.first, text.encodeToByteArray().size.toULong(), before.second, cx.estimate(text, Class.Prose), ref, callId))
}

private fun rememberSave(
    ev: PromptSubmit,
    cx: Ctx,
): Injection? {
    val first =
        ev.prompt
            .lines()
            .firstOrNull()
            ?.trim() ?: return null
    if (!first.startsWith("remember:")) return null
    val rest = stripPrivate(first.removePrefix("remember:").trim())
    if (rest.isEmpty()) return null
    val title = rest.scalars().take(80).joinToString("")
    val id = runCatching { cx.upsertNote(cx.cwd()?.let(::projectName), "user", title, rest) }.getOrNull() ?: return null
    linkNote(cx, id, rest)
    return Injection("memory", "saved note $id", 12u)
}

private fun satMul2(n: UInt) = if (n > UInt.MAX_VALUE / 2u) UInt.MAX_VALUE else n * 2u

/** Text hits for prompt recall: FTS, or the stored hybrid leg when embeddings are on (never `embed_stale`: 10 ms hook). */
private fun recallQueryHits(
    cx: Ctx,
    query: String,
    n: UInt,
): List<NoteHit> {
    val cfg = memoryConfig(cx)
    return if (cfg.embed.enabled && cfg.embed.hybrid) cx.searchNotesHybridStored(query, satMul2(n)) else cx.searchNotes(query, satMul2(n))
}

private fun promptRecall(
    ev: PromptSubmit,
    cx: Ctx,
): Injection? {
    val cfg = memoryConfig(cx)
    val n = cfg.promptRecall
    val cap = maxOf(cfg.recallTokens, 1u)
    if (n == 0u) return null
    val query =
        ev.prompt
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(24)
            .joinToString(" ")
    if (query.isEmpty()) return null
    // T374: twice as many text hits as shown, so a file link can lift one just below the cut.
    val textHits = runCatching { recallQueryHits(cx, query, n) }.getOrNull() ?: return null
    val hits = recallHits(cx, ev.prompt, textHits, n.toInt())
    val obs = obsForPrompt(cx, query, ev.prompt)
    if (hits.isEmpty() && obs.isEmpty()) return null
    val (sizes, list) = entries(cx, hits.map { it.id to it.title })
    var text = if (list.isEmpty()) "" else renderTitleIndex(cx, list, cap)
    text = appendObs(cx, text, obs, cap)
    if (text.isEmpty()) return null
    val sha = hexSha256(text.encodeToByteArray())
    val last = runCatching { cx.lastMeasurementRef("memory", "prompt_recall") }.getOrElse { return null }
    if (last == sha) return null
    list.forEach { runCatching { cx.touchNote(it.id) } }
    measure(cx, "prompt_recall", bodiesBefore(sizes, list), text, sha)
    return Injection("memory", text, 11u)
}

private fun obsForPrompt(
    cx: Ctx,
    query: String,
    prompt: String,
): List<ObsHit> {
    val project = resolvedProject(cx)
    val fts = runCatching { cx.searchObservations(project, query, 10u) }.getOrDefault(emptyList())
    val linked = runCatching { cx.observationsForFiles(project, promptPaths(cx, prompt), 10u) }.getOrDefault(emptyList())
    return diversifyBySession(rrfObs(listOf(fts, linked), 10), 10, MAX_PER_SESSION)
}

/** Append at most two `obs` lines while [cap] still holds; notes already in [text] win. */
private fun appendObs(
    cx: Ctx,
    text: String,
    hits: List<ObsHit>,
    cap: UInt,
): String {
    var out = text.trimEnd()
    for (line in indexLines(hits, 2)) {
        val next = if (out.isEmpty()) line else "$out\n$line"
        if (cx.estimate(next, Class.Prose) > cap) break
        out = next
    }
    return out
}

/** Title index plus up to two of this session's observations, for PreCompact; null when both are empty. */
fun compactContext(cx: Ctx): Injection? {
    val cfg = memoryConfig(cx)
    val cap = maxOf(cfg.recallTokens, 1u)
    val rows = runCatching { cx.listNoteTitles(resolvedProject(cx), maxOf(cfg.recallTitles, 1u)) }.getOrDefault(emptyList())
    val (sizes, kept) = entries(cx, rows)
    val notes = if (kept.isEmpty()) "" else renderTitleIndex(cx, kept, cap)
    val recent = diversifyBySession(runCatching { cx.recentObservations(cx.session(), 8u) }.getOrDefault(emptyList()), 8, MAX_PER_SESSION)
    val text = appendObs(cx, notes, recent, cap)
    if (text.isEmpty()) return null
    measure(cx, "pre_compact", bodiesBefore(sizes, kept), text, cx.session())
    return Injection("memory", text, 10u)
}

internal fun recall(cx: Ctx): Injection? {
    val cfg = memoryConfig(cx)
    val cap = maxOf(cfg.recallTokens, 1u)
    val project = resolvedProject(cx)
    val rows = runCatching { cx.listNoteTitles(project, maxOf(cfg.recallTitles, 1u)) }.getOrNull() ?: return null
    var sizes = emptyMap<Int, Pair<ULong, UInt>>()
    var kept = mutableListOf<Entry>()
    val text =
        if (rows.isEmpty()) {
            emptyProjectLine(project).takeIf { cx.estimate(it, Class.Prose) <= cap } ?: return null
        } else {
            entries(cx, rows).let { (s, k) ->
                sizes = s
                kept = k
                renderTitleIndex(cx, kept, cap)
            }
        }
    measure(cx, "recall", bodiesBefore(sizes, kept), text, cx.session())
    return Injection("memory", text, 10u)
}

private fun saveScope(project: String?): String? = project ?: runCatching { projectName(cwd()) }.getOrNull()

/** MCP `mem_save` without the admission gate: `(id, updated)`. */
fun memSave(
    rt: Runtime,
    kind: String,
    title: String,
    body: String,
    project: String?,
): Pair<Int, Boolean> = saveScoped(rt, kind, title, body, saveScope(project))

/** What `mem_save` did: stored, or refused at the admission gate (T671). */
sealed interface Saved {
    data class Stored(
        val id: Int,
        val updated: Boolean,
    ) : Saved

    /** [existing] names the stored note a duplicate repeats. */
    data class Refused(
        val reason: String,
        val existing: Int?,
    ) : Saved
}

/** [memSave] behind the admission gate (`plugins.memory.admit`); a revision of an existing key always passes the duplicate check. */
fun memSaveGated(
    rt: Runtime,
    kind: String,
    title: String,
    body: String,
    project: String?,
): Saved {
    val cfg = rt.config.plugins.memory
    val scope = saveScope(project)
    val cleanTitle = stripPrivate(title)
    val cleanBody = stripPrivate(body)
    if (cfg.admit && cleanTitle.isNotEmpty() && cleanBody.isNotEmpty()) {
        val stored = rt.store.admitCandidates(scope, kind, cleanTitle, Admit.WINDOW).map { Admit.Row(it.first, it.second, it.third) }
        when (val v = Admit.judge(cfg.admitMinChars, cfg.admitSimilarity, cleanBody, stored)) {
            Admit.Verdict.Admit -> {}

            Admit.Verdict.TooShort -> {
                return Saved.Refused("too_short", null)
            }

            is Admit.Verdict.Duplicate -> {
                return Saved.Refused("duplicate", v.id)
            }
        }
    }
    val (id, updated) = saveScoped(rt, kind, title, body, scope)
    return Saved.Stored(id, updated)
}

/** `mem_save` with the scope decided: null is a global note. The title is the topic key (T66.1). */
private fun saveScoped(
    rt: Runtime,
    kind: String,
    title: String,
    body: String,
    proj: String?,
): Pair<Int, Boolean> {
    val t = stripPrivate(title)
    val b = stripPrivate(body)
    require(t.isNotEmpty()) { "missing `title`" }
    require(b.isNotEmpty()) { "missing `body`" }
    val (id, updated) = rt.store.upsertNote(proj, kind, t, b)
    linkNote(Ctx(rt), id, b)
    rt.store.upsertNoteEmbedding(id, t, b, embedSettings(rt.config.plugins.memory.embed))
    return id to updated
}

/** `rtok memory forget` (T669): retire notes nobody read for [days]; a dry run unless [apply]. */
fun memForget(
    rt: Runtime,
    project: String?,
    days: UInt?,
    apply: Boolean,
): String {
    val idle = days ?: rt.config.plugins.memory.forgetAfterDays
    if (idle == 0u) return "forget is off: forget_after_days is 0; pass --days N"
    val rows = rt.store.forgetStaleNotes(project, idle, apply)
    val out = mutableListOf("${if (apply) "retired" else "would retire"} ${rows.size} notes unread for $idle+ days")
    rows.take(20).forEach { (id, t) -> out += "$id $t" }
    if (rows.size > 20) out += "… ${rows.size - 20} more"
    return out.joinToString("\n")
}

fun memPack(
    rt: Runtime,
    query: String,
    limit: UInt,
    maxTokens: UInt,
): String {
    val hits = memSearch(rt, query, limit.coerceIn(1u, 20u))
    val body = { id: Int -> runCatching { rt.store.getNoteBody(id) }.getOrNull() }
    val pack = packNotes(hits.map { PackHit(it.id, it.title, it.snippet) }, body, maxTokens.coerceIn(1u, 2000u)) { rt.estimate(it, Class.Prose) }
    val text = render(pack)
    var bytes = 0uL
    var est = 0u
    pack.entries.forEach { e ->
        body(e.id)?.let {
            bytes += it.encodeToByteArray().size.toULong()
            est = satAdd(est, rt.estimate(it, Class.Prose))
        }
    }
    runCatching { rt.record(Measurement("memory", "mem_pack", bytes, text.encodeToByteArray().size.toULong(), est, rt.estimate(text, Class.Prose), null, rt.callId)) }
    return text
}

fun memSearch(
    rt: Runtime,
    query: String,
    limit: UInt,
): List<NoteHit> = memSearchIn(rt, query, limit, saveScope(null))

/** [memSearch] for an explicit project (T667): its notes win a near tie. Vector/hybrid paths take no boost. */
fun memSearchIn(
    rt: Runtime,
    query: String,
    limit: UInt,
    project: String?,
): List<NoteHit> {
    val lim = maxOf(limit, 1u)
    val cfg = rt.config.plugins.memory.embed
    if (!cfg.enabled) return rt.store.searchNotesIn(query, lim, project)
    val e = embedSettings(cfg)
    return if (cfg.hybrid) rt.store.searchNotesHybrid(query, lim, e) else rt.store.searchNotesEmbed(query, lim, e)
}

/** Narrative of one observation; records `obs_get`. */
fun obsGet(
    rt: Runtime,
    id: Int,
): String? {
    val body = rt.store.observationNarrative(id) ?: return null
    runCatching { rt.record(Measurement("memory", "obs_get", 0u, body.encodeToByteArray().size.toULong(), 0u, rt.estimate(body, Class.Prose), id.toString(), rt.callId)) }
    return body
}

fun memGet(
    rt: Runtime,
    id: Int,
): String? {
    val row = rt.store.noteRow(id) ?: return null
    if (row.retired == null) runCatching { rt.store.touchNote(id) }
    // Nothing is lost (D4): a retired note still reads whole, one line saying why.
    val body = row.retired?.let { ts -> "retired $ts" + (row.supersededBy?.let { ", superseded by $it" } ?: "") + "\n" + row.body } ?: row.body
    runCatching { rt.record(Measurement("memory", "mem_get", 0u, body.encodeToByteArray().size.toULong(), 0u, rt.estimate(body, Class.Prose), id.toString(), rt.callId)) }
    return body
}

/** T69.1 lifecycle for MCP `mem_update` and `rtok memory retire|pin|unpin`. */
fun memUpdate(
    rt: Runtime,
    id: Int,
    retire: Boolean,
    supersededBy: Int?,
    pinned: Boolean?,
): String {
    require(supersededBy == null || retire) { "superseded_by needs retire: true (it names what the retired note replaces)" }
    val parts = mutableListOf<String>()
    if (retire) {
        require(rt.store.retireNote(id, supersededBy)) { "unknown note id: $id" }
        parts += "retired note $id" + (supersededBy?.let { ", superseded by $it" } ?: "")
    }
    pinned?.let { p ->
        require(rt.store.setNotePinned(id, p)) { "unknown note id: $id" }
        parts += "${if (p) "pinned" else "unpinned"} note $id"
    }
    require(parts.isNotEmpty()) { "nothing to do: pass retire or pinned" }
    return parts.joinToString("; ")
}

/** T69.1 revise: save the replacement in place, then retire the old id naming it. `(new id, retired old id)`. */
fun memRevise(
    rt: Runtime,
    id: Int,
    title: String,
    body: String,
): Pair<Int, Int?> {
    val old = rt.store.noteRow(id) ?: throw IllegalArgumentException("unknown note id: $id")
    val (new, _) = saveScoped(rt, old.kind, title, body, old.project)
    if (new == id) return new to null
    rt.store.retireNote(id, new)
    return new to id
}

/** T472: earlier title and body for [id], oldest first. */
fun memHistory(
    rt: Runtime,
    id: Int,
): String {
    requireNotNull(rt.store.noteRow(id)) { "unknown note id: $id" }
    val rows = rt.store.noteVersions(id)
    if (rows.isEmpty()) return "no earlier versions of note $id"
    return rows.joinToString("\n\n") { (v, t, b) -> "v$v $t\n$b" }
}
