// Port of src/plugins/read/cache.rs: re-read dedup (T4.4) — same session/path/mode/range + sha256 → a short hit,
// a changed re-read → a unified diff when it is below `read.delta_max_ratio` of the file (T58.1).
package rtok.plugins.read

import rtok.Expand
import rtok.plugin.sdk.*
import rtok.store.hexSha256
import rtok.sys.ChangePreview
import rtok.sys.scalarCount

object ReadCache {
    /** Also matched by `measure::stats` to recognise a result that carries rtok's own trace. */
    const val DEDUP_PREFIX = "unchanged id "

    fun key(
        path: String,
        mode: String,
        range: String?,
    ) = "$path\t$mode\t${range.orEmpty()}"

    fun hit(
        cx: Ctx,
        key: String,
        path: String,
        body: ByteArray,
        forceDelta: Boolean,
    ): String? {
        val id = runCatching { cx.getReadCache(key) }.getOrNull()?.first ?: return null
        val prev = runCatching { cx.getArchive(id) }.getOrNull() ?: return null
        if (prev.contentEquals(body)) return dedupLine(cx, id, body)
        val cfg = readCfg(cx)
        if (!cfg.delta && !forceDelta) return null
        val before = prev.utf8OrNull() ?: return null
        val after = body.utf8OrNull() ?: return null
        val diff = ChangePreview.unifiedDiff(path, before, after)
        if (diff.isEmpty() || body.isEmpty()) return null
        if (diff.encodeToByteArray().size.toFloat() / body.size >= cfg.deltaMaxRatio) return null
        val newId = runCatching { remember(cx, key, body) }.getOrNull() ?: return null
        val newShown = rtok.shortId(newId)
        var msg = "${diff}previous ${rtok.shortId(id)}\nexpand $newShown"
        val max = cfg.maxChars.toInt()
        if (msg.scalarCount() > max) msg = Expand.cut(msg, "\n… archived $newShown …\n", max)
        runCatching {
            cx.record(
                Measurement(
                    "read",
                    "delta",
                    body.size.toULong(),
                    msg.encodeToByteArray().size.toULong(),
                    cx.estimate(after, Class.Code),
                    cx.estimate(msg, Class.Code),
                    newId,
                ),
            )
        }
        return msg
    }

    private fun ByteArray.utf8OrNull() = runCatching { decodeToString(throwOnInvalidSequence = true) }.getOrNull()

    /** The 8-character prefix every other pointer prints (T672, T906); the row keeps the full id. */
    private fun dedupLine(
        cx: Ctx,
        id: String,
        body: ByteArray,
    ): String {
        val msg = DEDUP_PREFIX + rtok.shortId(id)
        runCatching {
            cx.record(
                Measurement(
                    "read",
                    "dedup",
                    body.size.toULong(),
                    msg.encodeToByteArray().size.toULong(),
                    cx.estimate(body.utf8OrNull().orEmpty(), Class.Code),
                    cx.estimate(msg, Class.Code),
                    id,
                ),
            )
        }
        return msg
    }

    fun remember(
        cx: Ctx,
        key: String,
        body: ByteArray,
    ): String {
        val id = cx.putArchive(body)
        cx.putReadCache(key, hexSha256(body), id)
        return id
    }

    /** With `delta` off, an Edit/Write of a file drops its cached reads so the next read is fresh. */
    fun invalidate(
        ev: PostToolUse,
        cx: Ctx,
    ) {
        if (readCfg(cx).delta) return
        if (ev.toolName != "Edit" && ev.toolName != "Write") return
        val path = pathArg(ev.toolInput) ?: return
        runCatching { cx.clearReadCache(path) }
        runCatching { cx.clearReadCache("read\t$path") }
    }
}
