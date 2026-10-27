// Port of src/plugins/checkpoint.rs (store side) — save the checkpoint note, offer it back on restore.
package rtok.plugins.checkpoint

import kotlinx.serialization.json.*
import rtok.config.Memory
import rtok.config.ProjectRoot
import rtok.json.StrictJson
import rtok.plugin.fitBudget
import rtok.plugin.sdk.*
import rtok.plugins.memory.redactPrivate
import rtok.projectName
import rtok.sys.rustLines

private fun kind(cx: Ctx) = "checkpoint:${cx.session()}"

/** Paths of this session's last checkpoint relative to [root], deleted ones left out. */
fun lastPaths(
    cx: Ctx,
    root: String,
): List<String> {
    val body = runCatching { cx.latestNote(kind(cx)) }.getOrNull() ?: return emptyList()
    val prefix = root.trimEnd('/') + "/"
    return body
        .rustLines()
        .filter { it.startsWith("path ") }
        .map { PathEntry.parse(it.removePrefix("path ")) }
        .filter { it.action != Action.Deleted }
        .map { it.path.removePrefix(prefix) }
}

/** PreCompact: the `checkpoint:<session>` note of project `rtok`. */
fun save(
    transcriptPath: String,
    cx: Ctx,
): Checkpoint = write(transcriptPath, cx, kind(cx), "rtok")

/** SessionEnd: the `session:<session>` note of the cwd's project (handoff). */
fun saveSession(
    transcriptPath: String,
    cx: Ctx,
): Checkpoint = write(transcriptPath, cx, "session:${cx.session()}", projectOf(cx))

private fun write(
    transcriptPath: String,
    cx: Ctx,
    kind: String,
    project: String?,
): Checkpoint {
    val cp = attachIds(extractPath(transcriptPath), cx)
    cx.upsertNote(project, kind, "compact", cp.render())
    // serde_json compact form of {"typed":…,"skipped":…}
    runCatching { cx.pluginStateSet("memory", kind, """{"typed":${cp.typed},"skipped":${cp.skipped}}""") }
    return cp
}

/** Typed and skipped prompt totals over plugin-state rows: each session's largest row, saturating. */
fun promptCounts(rows: List<Pair<String, String>>): Pair<ULong, ULong> {
    val best = HashMap<String, Pair<ULong, ULong>>()
    for ((key, value) in rows) {
        val session = listOf(":checkpoint:", ":session:").firstNotNullOfOrNull { m -> key.indexOf(m).takeIf { it >= 0 }?.let { key.substring(it + m.length) } } ?: continue
        val v = runCatching { StrictJson.parse(value) }.getOrNull() as? JsonObject ?: continue

        fun n(k: String) =
            (v[k] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.content
                ?.takeIf { s -> s.all { it in '0'..'9' } }
                ?.toULongOrNull() ?: 0u
        val row = n("typed") to n("skipped")
        val cur = best.getOrPut(session) { 0uL to 0uL }
        if (sat(row.first, row.second) > sat(cur.first, cur.second)) best[session] = row
    }
    return best.values.fold(0uL to 0uL) { (t, s), (rt, rs) -> sat(t, rt) to sat(s, rs) }
}

internal fun projectOf(cx: Ctx): String? = cx.cwd()?.let { projectName(it) }

/** The pre-T660 per-checkout name a handoff was stored under. */
internal fun legacyProjectOf(cx: Ctx): String? =
    cx
        .cwd()
        ?.let { ProjectRoot.find(it) }
        ?.checkout
        ?.trimEnd('/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotEmpty() }

private fun cap(cx: Ctx) = cx.pluginConfig("memory", Memory.serializer(), Memory()).checkpointTokens.coerceAtLeast(1u)

/** Live-window archive ids, newest first, while the render fits `checkpoint_tokens`. */
private fun attachIds(
    cp: Checkpoint,
    cx: Ctx,
): Checkpoint {
    val rows = runCatching { cx.sessionLiveArchives(cx.session()) }.getOrNull() ?: return cp
    var out = cp
    val cap = cap(cx)
    for ((id, tool, bytes) in rows) {
        val next = out.copy(ids = out.ids + id, idMeta = out.idMeta + (tool to (if (bytes < 0) ULong.MAX_VALUE else bytes.toULong())))
        if (cx.estimate(next.render(), Class.Prose) > cap) break
        out = next
    }
    return out
}

/** Compact restore: this session's checkpoint. */
fun offer(cx: Ctx): Injection? = runCatching { cx.latestNote(kind(cx)) }.getOrNull()?.let { renderOffer(cx, it) }

/** Startup recall: the project's latest handoff (falling back to the legacy per-checkout name), measured. */
fun offerSession(cx: Ctx): Injection? {
    val project = projectOf(cx)

    fun latest(p: String?) = runCatching { cx.latestSessionNote(p) }.getOrNull()
    val raw = latest(project) ?: legacyProjectOf(cx)?.takeIf { it != project }?.let(::latest) ?: return null
    val inj = renderOffer(cx, raw) ?: return null
    runCatching { cx.record(Measurement("memory", "handoff", raw.utf8(), inj.text.utf8(), cx.estimate(raw, Class.Prose), cx.estimate(inj.text, Class.Prose))) }
    return inj
}

private fun String.utf8() = encodeToByteArray().size.toULong()

private fun renderOffer(
    cx: Ctx,
    text: String,
): Injection? = fitBudget(cx, redactPrivate(text), Class.Prose, cap(cx)).takeIf { it.isNotEmpty() }?.let { Injection("inject", it, 9u) }
