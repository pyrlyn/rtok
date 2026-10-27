// Port of src/plugins/archive/mod.rs — replace old, large tool-result blocks in the live zone with
// pointers. Decisions persist per tool_use_id, so every later request rewrites the block identically
// (the cached prefix stays byte-stable) and an expanded id is never rewritten again.
package rtok.plugins.archive

import kotlinx.serialization.json.*
import rtok.json.StrictJson
import rtok.plugin.sdk.*
import rtok.plugins.TOON_PREFIX
import rtok.plugins.isForeignToArchive
import rtok.shortId
import rtok.store.hexSha256
import rtok.sys.rustLines
import rtok.sys.takeScalars
import rtok.config.Archive as ArchiveCfg
import rtok.config.Proxy as ProxyCfg

private const val ID = "archive"

object ArchivePlugin : Plugin {
    override fun manifest() = Manifest(ID, listOf(Surface.Proxy, Surface.Mcp, Surface.Cli), true)

    override fun dashboardPage() = DashboardPage("Archive", "Shrink old tool results in the live zone; pointers expand on demand.", true)

    override fun proxyFilter(
        req: WireRequest,
        cx: Ctx,
    ): List<Measurement> {
        if (cx.config("proxy", ProxyCfg.serializer(), ProxyCfg()).mode != "compress") return emptyList()
        return rewrite(req.toolResults(), cx) + rewriteBlobs(req.liveBlobs(), cx) + rewriteSkills(req.skills(), cx)
    }
}

internal fun archiveCfg(cx: Ctx) = cx.pluginConfig(ID, ArchiveCfg.serializer(), ArchiveCfg())

/** Results a proxy filter may rewrite: never the last `keep_turns` turns (shared with `toon`). */
fun outsideLiveZone(
    results: List<ToolResultRef>,
    cx: Ctx,
): List<ToolResultRef> {
    val keep = archiveCfg(cx).keepTurns.toInt()
    return results.filter { it.turn >= keep }
}

/** Rewrite every eligible result; one measurement per block, all under one `plugin_run` call. */
fun rewrite(
    results: List<ToolResultRef>,
    cx: Ctx,
): List<Measurement> = recordRun(outsideLiveZone(results, cx).mapNotNull { rewriteBlock(it, cx) }, cx)

fun rewriteSkills(
    skills: List<SkillRef>,
    cx: Ctx,
): List<Measurement> {
    val a = archiveCfg(cx)
    if (!a.skills) return emptyList()
    return recordRun(skills.filter { it.turn >= a.keepTurns.toInt() }.mapNotNull { rewriteSkill(it, cx) }, cx)
}

/** T51.1: shrink large JSON dumps / `data:` / base64 payloads from turn 2 up; off by default (`live_blobs`). */
fun rewriteBlobs(
    blobs: List<BlobRef>,
    cx: Ctx,
): List<Measurement> {
    if (!archiveCfg(cx).liveBlobs) return emptyList()
    return recordRun(blobs.filter { it.turn >= 2 }.mapNotNull { rewriteBlob(it, cx) }, cx)
}

private fun Ctx.err(
    what: String,
    e: Throwable,
) = log("error", "plugin", ID, "$what: ${e.message}")

/** Ground truth for `stats`: estimated tokens before/after, nested under the API request. */
private fun recordRun(
    out: List<Measurement>,
    cx: Ctx,
): List<Measurement> {
    if (out.isEmpty()) return out
    val id =
        runCatching { cx.recordPluginRun("proxy", ID) }.getOrElse {
            cx.err("plugin_run", it)
            return out
        }
    runCatching { cx.recordTokens(id, ID, "before", "estimate", out.sumOf { it.estBefore.toLong() }) }
    runCatching { cx.recordTokens(id, ID, "after", "estimate", out.sumOf { it.estAfter.toLong() }) }
    return out.map { it.copy(callId = id) }
}

private fun measurement(
    kind: String,
    text: String,
    live: String,
    archiveId: String,
    cls: Class,
    cx: Ctx,
    estBefore: UInt = cx.estimate(text, cls),
) = Measurement(ID, kind, text.utf8Len(), live.utf8Len(), estBefore, cx.estimate(live, cls), archiveId)

internal fun String.utf8Len() = encodeToByteArray().size.toULong()

/** A persisted decision, or null on a store error (logged; the block stays as it is). */
private sealed interface Decision {
    data class Found(
        val d: ArchiveDecision,
    ) : Decision

    data object None : Decision

    data object Failed : Decision
}

private fun decision(
    key: String,
    cx: Ctx,
): Decision =
    runCatching { cx.archiveDecision(key) }
        .fold({ if (it == null) Decision.None else Decision.Found(it) }, {
            cx.err("decision", it)
            Decision.Failed
        })

/** Archive [text] and persist [live] for [key]; null (logged) on any store error. */
private fun archiveNew(
    key: String,
    text: String,
    cx: Ctx,
    live: (String) -> String,
): Pair<String, String>? {
    val id =
        runCatching { cx.putArchive(text.encodeToByteArray()) }.getOrElse {
            cx.err("put", it)
            return null
        }
    val l = live(id)
    runCatching { cx.putArchiveDecision(key, id, l) }.getOrElse {
        cx.err("decision", it)
        return null
    }
    return id to l
}

private fun rewriteBlock(
    r: ToolResultRef,
    cx: Ctx,
): Measurement? {
    val text = blockText(r.content) ?: return null
    val a = archiveCfg(cx)
    val (id, live, kind) =
        when (val d = decision(r.id, cx)) {
            Decision.Failed -> {
                return null
            }

            is Decision.Found -> {
                if (d.d.expanded || isForeignToArchive(d.d.pointer)) return null
                Triple(d.d.archiveId, d.d.pointer, if (a.tiers) tierKind(d.d.pointer) else "pointer")
            }

            Decision.None -> {
                val est = cx.estimate(text, Class.Code)
                if (est < a.minTokens) return null
                var kind = "pointer"
                val (id, live) =
                    archiveNew(r.id, text, cx) { id ->
                        if (a.tiers) {
                            tierLive(text, id, est, r.turn, a).also { kind = it.second }.first
                        } else {
                            pointer(text, id, est, a.headLines.toInt(), a.tailLines.toInt())
                        }
                    } ?: return null
                Triple(id, live, kind)
            }
        }
    r.content = JsonPrimitive(live)
    return measurement(kind, text, live, id, Class.Code, cx)
}

private fun rewriteSkill(
    s: SkillRef,
    cx: Ctx,
): Measurement? {
    val text = (s.content as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val key = "skill:${s.id}"
    val (id, live) =
        when (val d = decision(key, cx)) {
            Decision.Failed -> {
                return null
            }

            is Decision.Found -> {
                if (d.d.expanded) return null else d.d.archiveId to d.d.pointer
            }

            Decision.None -> {
                archiveNew(key, text, cx) { id ->
                    "[archived ${s.id.takeScalars(12)}: skill ${s.name} · ${text.rustLines().size} lines · expand(${shortId(id)})]"
                } ?: return null
            }
        }
    s.content = JsonPrimitive(live)
    return measurement("skill", text, live, id, Class.Prose, cx)
}

private fun rewriteBlob(
    b: BlobRef,
    cx: Ctx,
): Measurement? {
    val text = (b.content as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!isBlobCandidate(text)) return null
    val a = archiveCfg(cx)
    val est = cx.estimate(text, Class.Json)
    if (est < a.minTokens) return null
    val key = "blob:${hexSha256(text.encodeToByteArray())}"
    val (id, live) =
        when (val d = decision(key, cx)) {
            Decision.Failed -> return null
            is Decision.Found -> if (d.d.expanded || d.d.pointer.startsWith(TOON_PREFIX)) return null else d.d.archiveId to d.d.pointer
            Decision.None -> archiveNew(key, text, cx) { id -> pointer(text, id, est, a.headLines.toInt(), a.tailLines.toInt()) } ?: return null
        }
    b.content = JsonPrimitive(live)
    return measurement("live_blob", text, live, id, Class.Json, cx, est)
}

/** A `data:` URI, a bare base64 run, or text that parses as JSON; prose and code stay live. */
private fun isBlobCandidate(text: String): Boolean {
    val t = text.trimStart()
    if (t.startsWith("data:")) return true
    return when (t.firstOrNull()) {
        '{', '[' -> runCatching { StrictJson.parse(t) }.isSuccess
        else -> t.length > 4096 && t.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }
    }
}

/** The text of a tool-result content: a string, or text blocks joined by `\n`; null when any part is not text. */
fun blockText(content: JsonElement): String? =
    when (content) {
        is JsonPrimitive -> {
            content.takeIf { it.isString }?.content
        }

        is JsonArray -> {
            content
                .map { p ->
                    val o = p as? JsonObject ?: return null
                    if ((o["type"] as? JsonPrimitive)?.content != "text" || o["type"]?.jsonPrimitive?.isString != true) return null
                    (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                }.joinToString("\n")
        }

        else -> {
            null
        }
    }

private fun pointerLine(
    text: String,
    id: String,
    est: UInt,
): String {
    val short = shortId(id)
    return "[archived $short: ${text.rustLines().size} lines · $est tokens · expand($short)]"
}

/** Widest line a pointer or L1 extract shows, in chars; the full line stays in the archive. */
private const val LINE_CHARS = 200

fun clip(line: String): String {
    val cut = line.takeScalars(LINE_CHARS)
    return if (cut.length < line.length) "$cut…" else line
}

internal fun pointer(
    text: String,
    id: String,
    est: UInt,
    head: Int,
    tail: Int,
): String {
    val lines = text.rustLines()
    val n = lines.size
    val sb = StringBuilder(pointerLine(text, id, est))

    fun add(ls: List<String>) = ls.forEach { sb.append('\n').append(clip(it)) }
    if (n <= head + tail) {
        add(lines)
    } else {
        add(lines.subList(0, head))
        sb.append("\n… ${n - head - tail} lines …")
        add(lines.subList(n - tail, n))
    }
    return sb.toString()
}

/** P33 L1: deterministic lossless extract (numbered head/tail). */
internal fun l1Extract(
    text: String,
    head: Int,
    tail: Int,
): String {
    val lines = text.rustLines()
    val n = lines.size
    val sb = StringBuilder("[tier L1: $n lines · lossless extract]\n")
    lines.take(head).forEachIndexed { i, l -> sb.append("L${i + 1}: ${clip(l)}\n") }
    if (n > head + tail) sb.append("… ${n - head - tail} omitted lines …\n")
    for (i in maxOf(n - tail, 0).coerceAtLeast(head) until n) sb.append("L${i + 1}: ${clip(lines[i])}\n")
    return sb.toString().trimEnd()
}

/** L0 = pointer line only; the hot boundary (`turn == keep_turns`) is promoted to L1. */
private fun tierLive(
    text: String,
    id: String,
    est: UInt,
    turn: Int,
    a: ArchiveCfg,
): Pair<String, String> {
    val head = a.headLines.toInt()
    val tail = a.tailLines.toInt()
    return if (turn == a.keepTurns.toInt()) {
        "${pointer(text, id, est, head, tail)}\n${l1Extract(text, head * 3, tail * 2)}" to "tier_l1"
    } else {
        pointerLine(text, id, est) to "tier_l0"
    }
}

private fun tierKind(live: String) = if ("[tier L1:" in live) "tier_l1" else "tier_l0"
