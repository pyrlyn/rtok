// Port of the tool half of src/plugins/graph/mod.rs: the backend door (`text_or`, `lsp_or_tags`, `door`,
// `record_mode_row`), `cap`/`cap_kind`, and the tags answers of `symbol`, `symbol_by_id` and `callers`
// (`defs_text`, `body_lines`, ambiguity marks).
package rtok.plugins.graph

import rtok.plugin.sdk.Callee
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.Measurement
import rtok.plugin.sdk.SymDef
import rtok.store.hexSha256
import rtok.sys.readBytes
import rtok.sys.rustLines
import kotlin.time.TimeSource

/** The language-server side of the door. lsp.rs plugs in here; until then no server is ever found. */
interface LspBackend {
    fun probe(root: String): Absent?

    fun symbol(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter,
    ): String

    fun callers(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter,
    ): String

    fun impact(
        cx: Ctx,
        root: String,
        name: String,
        depth: Int,
        filter: Filter,
        to: String?,
    ): String = throw Refused("lsp: unavailable")

    fun explore(
        cx: Ctx,
        root: String,
        query: String,
        filter: Filter,
    ): String = throw Refused("lsp: unavailable")

    fun outline(
        cx: Ctx,
        root: String,
        path: String,
    ): String = throw Refused("lsp: unavailable")

    /** The session key for one file's outline (lsp.rs `outline_workspace`). */
    fun outlineWorkspace(
        root: String,
        abs: String,
    ): String = root

    fun setTimeoutMs(ms: ULong) {}

    object None : LspBackend {
        override fun probe(root: String) = Absent(false, "lsp: no language server for this project")

        override fun symbol(
            cx: Ctx,
            root: String,
            name: String,
            filter: Filter,
        ): String = throw Refused("lsp: unavailable")

        override fun callers(
            cx: Ctx,
            root: String,
            name: String,
            filter: Filter,
        ): String = throw Refused("lsp: unavailable")
    }
}

/** `{path}:{line} {kind}` head decorations in a multi-project answer (mod.rs `Tag`). */
data class Tag(
    val prefix: String = "",
    val suffix: String = "",
)

object Tools {
    var lsp: LspBackend = LspBackend.None

    /** Not a saving, so before == after: `beforeBytes` is the milliseconds spent. Fails open. */
    fun recordModeRow(
        cx: Ctx,
        kind: String,
        t0: TimeSource.Monotonic.ValueTimeMark,
        out: String,
    ) {
        runCatching {
            val est = cx.estimate(out, Class.Code)
            cx.record(Measurement("graph", kind, t0.elapsedNow().inWholeMilliseconds.toULong(), out.encodeToByteArray().size.toULong(), est, est, null, cx.callId()))
        }
    }

    fun tagsKind(tool: String) =
        when (tool) {
            "symbol" -> "tags.symbol"
            "callers" -> "tags.callers"
            "outline" -> "tags.outline"
            "explore" -> "tags.explore"
            else -> "tags.impact"
        }

    fun textKind(tool: String) =
        when (tool) {
            "symbol" -> "text.symbol"
            "callers" -> "text.callers"
            "outline" -> "text.outline"
            "explore" -> "text.explore"
            else -> "text.impact"
        }

    /** T329.10: the text backend's door, in front of [lspOrTags]. */
    fun textOr(
        cx: Ctx,
        root: String,
        tool: String,
        searched: () -> String,
        rest: () -> String,
    ): String {
        if (!Text.applies(cx, root, lsp::probe)) return rest()
        val t0 = TimeSource.Monotonic.markNow()
        val body =
            try {
                searched()
            } catch (e: Throwable) {
                throw IllegalStateException("no graph backend available: ${e.message ?: e}", e)
            }
        val out = "(text)\n${cap(cx, body)}"
        recordModeRow(cx, textKind(tool), t0, out)
        return out
    }

    fun lspOrTags(
        cx: Ctx,
        root: String,
        tool: String,
        names: List<String>,
        lspAnswer: () -> String,
        tags: () -> String,
    ): String = door(cx, root, tool, names, lsp::probe, lspAnswer, tags)

    /** T376, T329.9, T329.11: the one door the five tools take; [probe] is passed in so a test can count it. */
    fun door(
        cx: Ctx,
        root: String,
        tool: String,
        names: List<String>,
        probe: Probe,
        lspAnswer: () -> String,
        tags: () -> String,
    ): String {
        val name = GraphCore.backendName(cx, root)
        val mode = Mode.named(name)
        if (mode == Mode.Tags || mode == Mode.Text) return tags()
        val auto = mode == Mode.Auto
        val t0 = TimeSource.Monotonic.markNow()
        val counted = Tally.mark()
        lsp.setTimeoutMs(graphCfg(cx).lspTimeoutMs.toULong())

        fun said(text: String) = if (auto) "(lsp)\n$text" else text
        val reason: String =
            when (val asked = Capability.ask(cx, root, name, probe, lspAnswer)) {
                is Asked.Tags -> {
                    if (auto && !asked.rec.server) {
                        val out = "(tags)\n${tags()}"
                        recordModeRow(cx, tagsKind(tool), t0, out)
                        return out
                    }
                    asked.rec.reason.orEmpty()
                }

                is Asked.Lsp -> {
                    val e = asked.answer.exceptionOrNull()
                    if (e != null) {
                        Capability.reasonOf(e)
                    } else {
                        val text = asked.answer.getOrThrow()
                        if (!GraphCore.lspNoneAnswer(text)) return said(text)
                        if (names.isNotEmpty() && !tagsKnow(cx, root, names)) return said(text)
                        "empty answer"
                    }
                }
            }
        Tally.rewind(counted)
        val shown = if (auto && reason.startsWith("lsp: ")) reason.removePrefix("lsp: ") else reason
        val out = "(tags; lsp: $shown)\n${tags()}"
        recordModeRow(cx, "lsp_fallback", t0, out)
        if (auto) recordModeRow(cx, tagsKind(tool), t0, out)
        return out
    }

    private fun tagsKnow(
        cx: Ctx,
        root: String,
        names: List<String>,
    ): Boolean {
        GraphCore.indexFor(cx, root)
        val key = Index.canon(root)
        return names.any { cx.symbolDefs(key, it).isNotEmpty() }
    }

    /** Over `max_tokens`, archives the whole answer and keeps the head lines that fit, with an expand id. */
    fun capKind(
        cx: Ctx,
        text: String,
        beforeBytes: ULong,
        kind: String,
    ): String {
        val max = graphCfg(cx).maxTokens
        val est = cx.estimate(text, Class.Code)
        var refId: String? = null
        val out =
            if (est <= max) {
                text
            } else {
                val id = cx.putArchive(text.encodeToByteArray()).also { refId = it }
                val textChars = text.codePointLen()
                val budget = (textChars.toLong() * max.toLong() / est.toLong() - 120).coerceAtLeast(0)
                val lines = text.rustLines()
                val head = StringBuilder()
                var headChars = 0L
                var shown = 0
                for (line in lines) {
                    val n = line.codePointLen()
                    if (shown > 0 && headChars + n + 1 > budget) break
                    head.append(line).append('\n')
                    headChars += n + 1
                    shown++
                }
                "$head${lines.size - shown} more, expand ${rtok.shortId(id)}"
            }
        val after = out.encodeToByteArray().size.toULong()
        if (refId != null || after < beforeBytes) {
            cx.record(Measurement("graph", kind, beforeBytes, after, est, cx.estimate(out, Class.Code), refId, cx.callId()))
        }
        return out
    }

    fun cap(
        cx: Ctx,
        text: String,
    ): String = capKind(cx, text, text.encodeToByteArray().size.toULong(), "cap")

    private fun String.codePointLen(): Int {
        var n = 0
        var i = 0
        while (i < length) {
            if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) i++
            i++
            n++
        }
        return n
    }

    // ---- symbol ----

    fun symbol(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter = Filter.None,
    ): String =
        textOr(cx, root, "symbol", { Text.symbol(cx, root, name, filter) }) {
            lspOrTags(cx, root, "symbol", listOf(name), { lsp.symbol(cx, root, name, filter) }) { symbolTags(cx, root, name, filter) }
        }

    fun symbolTags(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter,
    ): String {
        GraphCore.indexFor(cx, root)
        val key = Index.canon(root)
        val rows = cx.symbolDefs(key, name).filter { filter.pathOk(it.a) && filter.kindOk(it.kind) }
        if (rows.isEmpty()) return GraphCore.withStale(cx, root, "no definition of $name${filter.scopeNote()}")
        Tally.hit(root, name, rows.map { it.a })
        val callees = cx.symbolCallees(key, name)
        return GraphCore.withStale(cx, root, cap(cx, defsText(cx, root, name, rows, callees, Tag())))
    }

    fun symbolById(
        cx: Ctx,
        root: String,
        id: String,
    ): String {
        val (path, name, kind, line) = parseSymbolId(id) ?: return "no definition of $id"
        GraphCore.indexFor(cx, root)
        val key = Index.canon(root)
        val rows = cx.symbolDefs(key, name).filter { it.a == path && it.kind == kind && it.line == line }
        if (rows.isEmpty()) return GraphCore.withStale(cx, root, "no definition of $id")
        Tally.hit(root, name, listOf(path))
        return GraphCore.withStale(cx, root, cap(cx, defsText(cx, root, name, rows, cx.symbolCallees(key, name), Tag())))
    }

    fun callsLine(
        names: List<String>,
        cap: Int,
    ): String {
        if (names.isEmpty()) return ""
        val show = minOf(names.size, cap)
        val listed = names.take(show).joinToString(", ")
        val extra = names.size - show
        return if (extra > 0) "calls: $listed (+$extra)\n" else "calls: $listed\n"
    }

    fun ambiguousBanner(n: Int) = "$n names ambiguous (?): narrow with path or kind, or backend = \"lsp\"\n"

    fun markAmbiguousLines(out: String): String = if (out.isEmpty()) "" else out.rustLines().joinToString("\n") { "$it ?" } + "\n"

    fun flagAmbiguous(
        defs: Int,
        out: String,
    ) = if (defs > 1) ambiguousBanner(1) + markAmbiguousLines(out) else out

    private fun annotateAmbiguous(
        cx: Ctx,
        root: String,
        name: String,
        out: String,
    ) = flagAmbiguous(cx.symbolDefs(Index.canon(root), name).size, out)

    fun defsText(
        cx: Ctx,
        root: String,
        name: String,
        rows: List<SymDef>,
        callees: List<Callee>,
        tag: Tag,
    ): String {
        val budget = graphCfg(cx).bodyLines.toInt()
        val callCap = budget / 2
        val key = Index.canon(root)
        val byDef = HashMap<Pair<String, Int>, MutableList<String>>()
        for (c in callees) byDef.getOrPut(c.defPath to c.defLine) { ArrayList() } += c.callee
        val out = StringBuilder()
        val bytesOf = HashMap<String, ByteArray?>()
        val staleSaid = HashSet<String>()
        val archive: (ByteArray) -> String? = { b -> runCatching { cx.putArchive(b) }.getOrNull() }

        fun bytes(path: String) = bytesOf.getOrPut(path) { runCatching { readBytes("$root/$path") }.getOrNull() }
        for (r in rows) {
            val span = runCatching { cx.symbolSpan(key, r.a, name, r.kind, r.line) }.getOrNull()?.takeIf { it.contentHash.isNotEmpty() }
            val body =
                if (span != null) {
                    val b = bytes(r.a)
                    if (b == null || hexSha256(b) != span.fileSha) {
                        if (staleSaid.add(r.a)) out.append("stale ${r.a}\n")
                        ""
                    } else {
                        val s = span.startByte.toInt()
                        val e = span.endByte.toInt()
                        if (e > b.size || s > e) {
                            if (staleSaid.add(r.a)) out.append("stale ${r.a}\n")
                            ""
                        } else {
                            val text = b.copyOfRange(s, e).decodeToString()
                            val n = text.rustLines().size
                            if (n == 0) "" else bodyLines(text, 1, n, budget, archive)
                        }
                    }
                } else {
                    bodyLines(bytes(r.a)?.decodeToString().orEmpty(), r.line, r.endLine, budget, archive)
                }
            out
                .append(tag.prefix)
                .append(defHead(r.a, name, r.kind, r.line))
                .append(tag.suffix)
                .append('\n')
                .append(body)
            byDef[r.a to r.line]?.let { out.append(callsLine(it, callCap)) }
        }
        return out.toString()
    }

    fun defId(
        path: String,
        name: String,
        kind: String,
        line: Int,
    ) = "$path::$name#$kind@$line"

    fun defHead(
        path: String,
        name: String,
        kind: String,
        line: Int,
    ) = "${defId(path, name, kind, line)} $path:$line $kind"

    data class SymbolId(
        val path: String,
        val name: String,
        val kind: String,
        val line: Int,
    )

    fun parseSymbolId(id: String): SymbolId? {
        val at = id.lastIndexOf('@')
        if (at < 0) return null
        val line = id.substring(at + 1).toIntOrNull() ?: return null
        val rest = id.substring(0, at)
        val hash = rest.lastIndexOf('#')
        if (hash < 0) return null
        val kind = rest.substring(hash + 1)
        val pn = rest.substring(0, hash)
        val sep = pn.indexOf("::")
        if (sep < 0) return null
        val path = pn.substring(0, sep)
        val name = pn.substring(sep + 2)
        if (path.isEmpty() || name.isEmpty() || kind.isEmpty()) return null
        return SymbolId(path, name, kind, line)
    }

    fun defText(
        src: String,
        name: String,
        path: String,
        kind: String,
        line: Int,
        endLine: Int,
        budget: Int,
        archive: (ByteArray) -> String?,
    ) = "${defHead(path, name, kind, line)}\n${bodyLines(src, line, endLine, budget, archive)}"

    /** Byte span of lines `first..last` (0-based, end exclusive) of [src]. */
    private fun lineByteSpan(
        src: ByteArray,
        first: Int,
        last: Int,
    ): Pair<Int, Int> {
        val starts = ArrayList<Int>().apply { add(0) }
        for (i in src.indices) if (src[i] == '\n'.code.toByte()) starts += i + 1
        val start = starts.getOrElse(first) { src.size }
        val end = starts.getOrElse(last) { src.size }
        return minOf(start, end) to end
    }

    /** Lines `line..=endLine` of [src], at most [budget], then an archived `… N more lines, expand <id>` trailer. */
    fun bodyLines(
        src: String,
        line: Int,
        endLine: Int,
        budget: Int,
        archive: (ByteArray) -> String?,
    ): String {
        val first = maxOf(line, 1) - 1
        val last = maxOf(endLine, line)
        val body = src.rustLines().drop(first).take(maxOf(last - first, 0))
        val out = StringBuilder()
        for (l in body.take(budget)) out.append(l).append('\n')
        if (body.size > budget) {
            val bytes = src.encodeToByteArray()
            val (s, e) = lineByteSpan(bytes, first, last)
            val extra = body.size - budget
            val id = archive(bytes.copyOfRange(s, e))
            out.append(if (id != null) "  … $extra more lines, expand ${rtok.shortId(id)}\n" else "  … $extra more lines\n")
        }
        return out.toString()
    }

    // ---- callers ----

    fun callers(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter = Filter.None,
    ): String =
        textOr(cx, root, "callers", { Text.callers(cx, root, name, filter) }) {
            lspOrTags(cx, root, "callers", listOf(name), { lsp.callers(cx, root, name, filter) }) { callersTags(cx, root, name, filter) }
        }

    fun callersTags(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter,
    ): String {
        GraphCore.indexFor(cx, root)
        val key = Index.canon(root)
        val ranked = Resolve.rankName(cx, key, name)
        val rows = cx.symbolRefGroups(key, name).filter { filter.pathOk(it.path) && ranked.allows(it.path) }
        if (rows.isEmpty()) return GraphCore.withStale(cx, root, annotateAmbiguous(cx, root, name, "no references to $name${filter.scopeNote()}"))
        Tally.hit(root, name, rows.map { it.path })
        val out = StringBuilder()
        for (r in rows) out.append(r.path).append(if (r.kind.isEmpty()) "" else "  ${r.kind}").append(" ×${r.count} (L${r.line})\n")
        if (ranked.others > 0) out.append(otherDefsLine(name, ranked.others))
        return GraphCore.withStale(cx, root, cap(cx, annotateAmbiguous(cx, root, name, out.toString())))
    }

    fun otherDefsLine(
        name: String,
        others: Int,
    ) = "+$others other definitions of $name\n"
}
