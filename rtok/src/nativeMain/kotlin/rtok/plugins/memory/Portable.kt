// Port of src/plugins/memory/export.rs (T66.2) and import.rs (T6.3): the JSONL notes travel in.
package rtok.plugins.memory

import kotlinx.serialization.json.*
import rtok.config.Config
import rtok.json.StrictJson
import rtok.plugin.Runtime
import rtok.store.*
import rtok.sys.readBytes

object Export {
    /** One serde line in struct field order; `None` fields are skipped. */
    internal fun line(r: ExportNote): String =
        buildString {
            fun key(k: String) {
                if (length > 1) append(',')
                SerdeJson.str(this, k)
                append(':')
            }

            fun s(
                k: String,
                v: String,
            ) {
                key(k)
                SerdeJson.str(this, v)
            }
            append('{')
            key("id")
            append(r.id)
            key("ts")
            append(r.ts)
            s("kind", r.kind)
            s("title", r.title)
            s("body", r.body)
            r.project?.let { s("project", it) }
            r.retired?.let {
                key("retired")
                append(it)
            }
            r.supersededBy?.let {
                key("superseded_by")
                append(it)
            }
            key("pinned")
            append(r.pinned)
            append('}')
        }

    /** One JSON object per line in id order; `checkpoint:*` and `session:*` stay behind. Retired notes travel as tombstones (T294). */
    fun run(
        cfg: Config,
        project: String?,
        out: (String) -> Unit,
    ): UInt =
        Runtime.open(cfg, "export").use { cx ->
            val rows = cx.store.listExportNotes(project)
            rows.forEach { out(line(it) + "\n") }
            rows.size.toUInt()
        }
}

object Import {
    data class Report(
        val inserted: UInt = 0u,
        val skipped: UInt = 0u,
        val malformed: UInt = 0u,
    ) {
        override fun toString() = "inserted $inserted  skipped $skipped  malformed $malformed"
    }

    private class Line(
        val kind: String,
        val title: String,
        val body: String,
        val project: String?,
        val id: Int?,
        val ts: Long?,
        val retired: Long?,
        val supersededBy: Int?,
        val pinned: Int?,
    )

    /** serde's derive for `Line`: required strings, optional typed numbers (null allowed), unknown keys ignored. */
    private fun parse(text: String): Line? {
        val o = runCatching { StrictJson.parse(text) }.getOrNull() as? JsonObject ?: return null

        fun req(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        var bad = false

        fun <T> opt(
            k: String,
            conv: (JsonPrimitive) -> T?,
        ): T? {
            val v = o[k] ?: return null
            if (v is JsonNull) return null
            val p = v as? JsonPrimitive
            val r = p?.let(conv)
            if (r == null) bad = true
            return r
        }

        fun str(p: JsonPrimitive) = p.takeIf { it.isString }?.content

        fun i64(p: JsonPrimitive) = p.takeIf { !it.isString }?.content?.toLongOrNull()

        fun i32(p: JsonPrimitive) = p.takeIf { !it.isString }?.content?.toIntOrNull()
        val kind = req("kind") ?: return null
        val title = req("title") ?: return null
        val body = req("body") ?: return null
        val line = Line(kind, title, body, opt("project", ::str), opt("id", ::i32), opt("ts", ::i64), opt("retired", ::i64), opt("superseded_by", ::i32), opt("pinned", ::i32))
        return if (bad) null else line
    }

    private fun sha(body: String) = hexSha256(body.encodeToByteArray())

    /**
     * Import one JSON object per line, deduped by body sha256 and by an existing `(project, kind, title)` (T209).
     * Malformed lines are counted; an unreadable file is an error naming its path, before the store opens (T361).
     */
    fun run(
        cfg: Config,
        path: String,
        dryRun: Boolean,
    ): Report {
        val raw = readBytes(path).decodeToString()
        return Runtime.open(cfg, "import").use { cx ->
            val seen =
                cx.store
                    .noteBodies()
                    .map(::sha)
                    .toHashSet()
            val keys =
                cx.store
                    .listNotes(null, true)
                    .map { Triple(it.project, it.kind, it.title) }
                    .toHashSet()
            var ins = 0u
            var skip = 0u
            var bad = 0u
            for (l in raw.lines()) {
                val t = l.trim()
                if (t.isEmpty()) continue
                val row =
                    parse(t) ?: run {
                        bad++
                        null
                    } ?: continue
                val title = stripPrivate(row.title)
                val body = stripPrivate(row.body)
                if (title.isEmpty() || body.isEmpty()) {
                    skip++
                    continue
                }
                val h = sha(body)
                val key = Triple(row.project, row.kind, title)
                if (h in seen || key in keys) {
                    skip++
                    continue
                }
                val stored = dryRun || cx.store.insertPortableNoteIfAbsent(PortableNote(row.project, row.kind, title, body, row.id, row.ts, row.retired, row.supersededBy, row.pinned)) != null
                if (stored) {
                    seen += h
                    keys += key
                    ins++
                } else {
                    skip++
                }
            }
            Report(ins, skip, bad)
        }
    }
}
