// Port of src/plugins/graph/text.rs (T329.10): the last-resort graph backend. A project with no grammar and no
// language server (or pinned with `backend = "text"`) is answered by a word-boundary regex over its files, in
// process, with the walk rules of the `search` tool. Answers are best effort and there is no call graph.
package rtok.plugins.graph

import rtok.plugin.sdk.Ctx
import rtok.plugins.read.Search
import rtok.plugins.read.readCfg
import rtok.sys.*

object Text {
    /** What `dead` and the `to` chains of `impact` print instead of a guess. */
    const val UNAVAILABLE = "not available in text mode"

    /** Closes every non-empty text answer, so a reader knows what the lines are worth. */
    const val CAVEAT = "(text search: may include comments, strings and same-named symbols)\n"

    /** Definition keywords of the common languages and the kind each one prints. */
    private val KEYWORDS =
        listOf(
            "fn" to "function",
            "def" to "function",
            "defp" to "function",
            "defn" to "function",
            "function" to "function",
            "func" to "function",
            "fun" to "function",
            "proc" to "function",
            "sub" to "function",
            "class" to "class",
            "struct" to "struct",
            "enum" to "enum",
            "trait" to "trait",
            "interface" to "interface",
            "type" to "type",
            "module" to "module",
            "object" to "object",
            "impl" to "impl",
            "macro" to "macro",
            "namespace" to "namespace",
            "protocol" to "protocol",
            "record" to "record",
            "const" to "const",
            "static" to "static",
        )
    private val ALTERNATION = KEYWORDS.joinToString("|") { it.first }

    /** `func (r *T) Name` carries a receiver between the keyword and the name. */
    private const val RECEIVER = """(?:\([^)]*\)\s*)?"""

    private fun kindOf(keyword: String) = KEYWORDS.firstOrNull { it.first == keyword }?.second ?: "symbol"

    /**
     * Whether the text backend answers for [root]: pinned with `text`, or `auto` with no ready language server (the
     * capability record, so checked once per project) and no file a grammar parses.
     */
    fun applies(
        cx: Ctx,
        root: String,
        probe: Probe,
    ): Boolean =
        when (GraphCore.modeOf(cx, root)) {
            Mode.Text -> true
            Mode.Auto -> !Capability.serverReady(cx, root, GraphCore.backendName(cx, root), probe) && !hasGrammar(cx, root)
            Mode.Tags, Mode.Lsp -> false
        }

    /** One walk that stops at the first file the tags index could parse. */
    private fun hasGrammar(
        cx: Ctx,
        root: String,
    ): Boolean {
        val m = Matcher(root, graphCfg(cx))
        return m.walk().any { it.isFile && m.indexable(it.path) }
    }

    /** `(relative path, text)` of every searchable file, with the graph's include/exclude and the search size limit. */
    private fun files(
        cx: Ctx,
        root: String,
    ): Sequence<Pair<String, String>> {
        if (!isDir(root)) throw IllegalStateException("$root is not a readable directory")
        val walk = Matcher(root, graphCfg(cx)).walk()
        return Search.textFiles(walk, readCfg(cx).searchMaxBytes).map { (path, text) ->
            (stripPrefix(path, root)?.trimStart('/') ?: path) to text
        }
    }

    private fun escape(s: String) =
        buildString {
            for (c in s) {
                if (c in """\.+*?()|[]{}^$#&-~""") append('\\')
                append(c)
            }
        }

    /** `\b` only where the name's edge is a word character, so a name like `+=` still matches. */
    private fun wordBounded(name: String): String {
        fun edge(c: Char?) = if (c != null && (c.isLetterOrDigit() || c == '_')) """\b""" else ""
        return edge(name.firstOrNull()) + escape(name) + edge(name.lastOrNull())
    }

    class Def(
        val path: String,
        val line: Int,
        val kind: String,
        val text: String,
    )

    /** Every definition of one name, and per file the non-definition lines mentioning it (count, first line). */
    class Scan(
        val defs: List<Def>,
        val mentions: Map<String, Pair<Int, Int>>,
    )

    private fun scan(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter,
    ): Scan {
        val word = wordBounded(name)
        val def = Regex("""\b($ALTERNATION)\s+$RECEIVER$word""")
        val mention = Regex(word)
        val defs = ArrayList<Def>()
        val mentions = HashMap<String, Pair<Int, Int>>()
        for ((path, text) in files(cx, root)) {
            if (!filter.pathOk(path)) continue
            for ((i, line) in text.rustLines().withIndex()) {
                val found = def.find(line)
                if (found != null) {
                    val kind = kindOf(found.groupValues[1])
                    if (filter.kindOk(kind)) defs += Def(path, i + 1, kind, line.trim())
                } else if (mention.containsMatchIn(line)) {
                    val (n, first) = mentions[path] ?: (0 to i + 1)
                    mentions[path] = (n + 1) to first
                }
            }
        }
        defs.sortWith(compareBy<Def> { it.path }.thenBy { it.line })
        return Scan(defs, mentions.entries.sortedBy { it.key }.associate { it.key to it.value })
    }

    private fun defsText(defs: List<Def>) = defs.joinToString("") { "${it.path}:${it.line} ${it.kind}\n${it.text}\n" }

    private fun withCaveat(body: String) = body + CAVEAT

    /** `symbol`: each definition line, with no body (a text search cannot tell where it ends). */
    fun symbol(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter,
    ): String {
        val found = scan(cx, root, name, filter)
        if (found.defs.isEmpty()) return "no definition of $name${filter.scopeNote()}"
        Tally.hit(root, name, found.defs.map { it.path })
        return withCaveat(defsText(found.defs))
    }

    /** The `path ×N (Lline)` rows `callers` prints, per file: no enclosing definition is known. */
    private fun mentionRows(found: Scan) = found.mentions.entries.joinToString("") { (p, v) -> "$p ×${v.first} (L${v.second})\n" }

    fun callers(
        cx: Ctx,
        root: String,
        name: String,
        filter: Filter,
    ): String {
        val found = scan(cx, root, name, filter)
        if (found.mentions.isEmpty()) return "no references to $name${filter.scopeNote()}"
        Tally.hit(root, name, found.mentions.keys)
        return withCaveat(mentionRows(found))
    }

    /** `impact`: the files that mention the name, one level; a `to` chain needs call edges text does not have. */
    fun impact(
        cx: Ctx,
        root: String,
        name: String,
        depth: UInt,
        filter: Filter,
        to: String?,
    ): String {
        if (!to.isNullOrEmpty()) return "impact to a symbol: $UNAVAILABLE"
        val found = scan(cx, root, name, filter)
        if (found.mentions.isEmpty()) return "nothing reaches $name${filter.scopeNote()}"
        Tally.hit(root, name, found.mentions.keys)
        val out = impactRows(found) + if (depth > 1u) "one level only in text mode\n" else ""
        return withCaveat(out)
    }

    private fun impactRows(found: Scan) = found.mentions.entries.joinToString("") { (p, v) -> "1  $p  ×${v.first}\n" }

    /** `outline`: the definition keywords found in one file, `line kind name`. */
    fun outline(
        cx: Ctx,
        root: String,
        abs: String,
    ): String {
        val len = fileLen(abs) ?: throw IoException(IoException.Kind.NotFound, "$abs: No such file or directory")
        if (len.toULong() > readCfg(cx).searchMaxBytes) throw IllegalStateException("$abs is over plugins.read.search_max_bytes")
        val text = readBytes(abs).decodeToString(throwOnInvalidSequence = true)
        val def = Regex("""\b($ALTERNATION)\s+$RECEIVER([A-Za-z_$][\w$]*)""")
        val rows =
            text
                .rustLines()
                .withIndex()
                .mapNotNull { (i, line) ->
                    def.find(line)?.let { "${i + 1} ${kindOf(it.groupValues[1])} ${it.groupValues[2]}\n" }
                }.joinToString("")
        if (rows.isEmpty()) return "no definitions in $abs"
        Tally.hit(root, "", listOf(abs))
        return withCaveat(rows)
    }

    /** `explore` over scans: a token is a symbol when something defines it; no prefix resolution, no call path. */
    class Explore(
        private val cx: Ctx,
        private val root: String,
        private val filter: Filter,
    ) : ExploreParts {
        private val scans = HashMap<String, Scan>()

        /** One walk per name, however many of the four parts ask. */
        private fun scanOf(name: String) = scans.getOrPut(name) { scan(cx, root, name, filter) }

        override fun resolve(token: String) = if (scanOf(token).defs.isNotEmpty()) listOf(token) else emptyList()

        override fun defs(name: String): String {
            val found = scanOf(name)
            if (found.defs.isNotEmpty()) Tally.hit(root, name, found.defs.map { it.path })
            return defsText(found.defs)
        }

        override fun paths(
            a: String,
            b: String,
        ) = emptyList<String>()

        override fun impact1(name: String) = scanOf(name).let { impactRows(it) to it.mentions.size }

        override fun defCount(name: String) = scanOf(name).defs.size
    }
}
