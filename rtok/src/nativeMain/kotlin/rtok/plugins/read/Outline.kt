// Port of src/plugins/read/outline.rs: `mode=map` / `mode=signatures` via tags (T4.3) or a line scan
// for Markdown (T68.8); `mode=stripped` via comment nodes (T50.3). Grammars: [Languages].
package rtok.plugins.read

import rtok.sys.extension
import rtok.sys.fileName
import rtok.sys.rustLines
import rtok.treesitter.Node
import rtok.treesitter.withTree

/** One tags-query hit (definition or reference). Lines are 1-based; bytes index the UTF-8 source. */
data class TagHit(
    val kind: String,
    val name: String,
    val line: Int,
    /** Last line of the tagged node (T8.5); a reference spans one line. */
    val endLine: Int,
    val startByte: Int,
    val endByte: Int,
    val isDef: Boolean,
    val lineText: String,
    /** Full import specifier when `kind == "import"` (T368); [name] stays the last segment. */
    val importPath: String = "",
)

/** True when [path] has a tags-supported extension (cheap; does not parse). */
fun supported(path: String): Boolean = Languages.hasTags(extension(path))

/** Whether a grammar name from `[plugins.graph.extensions]` is available in this build. */
fun grammarAvailable(grammar: String): Boolean = Languages.hasTags(grammar)

/** True when [path] is indexable with built-in extensions or the [extensions] remap (T68.10). */
fun supportedWith(path: String, extensions: Map<String, String>): Boolean =
    supported(path) || extension(path)?.let(extensions::get)?.let(::grammarAvailable) == true

/** Definitions and references from the grammar's tags query. Unknown language → empty. */
fun tags(path: String, src: String): List<TagHit> = tagsWithExtensions(path, src, emptyMap())

private fun grammarFor(path: String, extensions: Map<String, String>): String? {
    val ext = extension(path) ?: return null
    return Languages.grammarName(extensions[ext] ?: ext)
}

/** Like [tags], but `[plugins.graph.extensions]` can remap the file suffix (T68.10). */
fun tagsWithExtensions(path: String, src: String, extensions: Map<String, String>): List<TagHit> {
    val cfg = grammarFor(path, extensions)?.let(Languages::tags)?.getOrThrow() ?: return emptyList()
    val bytes = src.encodeToByteArray()
    val starts = IntArrayList().apply { add(0); bytes.forEachIndexed { i, b -> if (b == '\n'.code.toByte()) add(i + 1) } }
    fun lineOf(b: Int): Int = starts.partitionPoint { it <= b }.coerceAtLeast(1)
    val out = ArrayList<TagHit>()
    for (tag in cfg.generate(bytes)) {
        val name = bytes.decodeToString(tag.nameRange.first, tag.nameRange.last + 1)
        val lineText = bytes.decodeToString(tag.lineRange.first, tag.lineRange.last + 1)
        val endLine = lineOf((tag.range.last + 1 - 1).coerceAtLeast(0))
        val items = if (tag.syntaxType == "import") expandImport(name) else listOf("" to name)
        for ((importPath, item) in items) out += TagHit(tag.syntaxType, item, tag.start.row + 1, endLine, tag.range.first, tag.range.last + 1, tag.isDefinition, lineText, importPath)
    }
    // The bare last-segment capture keeps that node an import; the full clause is the row we store.
    fun qualified(h: TagHit) = h.importPath.any { it in ":/.\\" }
    val full = out.filter { it.kind == "import" && qualified(it) }.map { it.line to it.name }.toSet()
    return out.filter { it.kind != "import" || qualified(it) || (it.line to it.name) !in full }
}

/** One `llms.txt` list link. The URL is recorded, never fetched. */
data class LlmsLink(val title: String, val url: String, val description: String)

/** `llms.txt` and `llms-full.txt` are markdown with a `.txt` name. */
fun isLlmsTxt(path: String): Boolean = fileName(path) in setOf("llms.txt", "llms-full.txt")

private fun isMarkdown(path: String): Boolean = extension(path) in setOf("md", "mdx")

/** `- [title](url)` and `* [title](url): note`. Fenced blocks are skipped. */
fun llmsLinks(src: String): List<LlmsLink> {
    val out = ArrayList<LlmsLink>(); var fenced = false
    for (line in src.rustLines()) {
        val t = line.trim()
        if (t.startsWith("```")) { fenced = !fenced; continue }
        if (!fenced) parseLlmsLink(t)?.let(out::add)
    }
    return out
}

private fun parseLlmsLink(line: String): LlmsLink? {
    val rest = (line.removePrefixOrNull("- ") ?: line.removePrefixOrNull("* "))?.trim()?.removePrefixOrNull("[") ?: return null
    val sep = rest.indexOf("](").takeIf { it >= 0 } ?: return null
    val title = rest.substring(0, sep).trim().takeIf { it.isNotEmpty() } ?: return null
    val after = rest.substring(sep + 2)
    val close = after.indexOf(')').takeIf { it >= 0 } ?: return null
    val url = after.substring(0, close).trim()
    if (url.isEmpty() || url.any { it.isWhitespace() }) return null
    val description = after.substring(close + 1).trim().removePrefixOrNull(":")?.trim() ?: ""
    return LlmsLink(title, url, description)
}

private fun String.removePrefixOrNull(p: String): String? = if (startsWith(p)) substring(p.length) else null

private fun renderLlmsLinks(links: List<LlmsLink>): String = links.joinToString("\n") { l ->
    "link ${l.title} ${l.url}" + if (l.description.isNotEmpty()) " — ${l.description}" else ""
}

private fun markdownRender(headings: List<MdHeading>, mode: String): String =
    if (mode == "signatures") headings.joinToString("\n") { it.text }
    else headings.joinToString("\n") { h -> "h${h.level} ${h.text.trimStart('#').trim()} ${h.line}" + (h.body?.let { "\n  $it" } ?: "") }

/** One row per named item: `(full specifier, last segment)`. A brace group is one node (T368). */
internal fun expandImport(raw: String): List<Pair<String, String>> =
    expandClause("", raw.trim().trim('"', '\'', '`').trim().trimEnd(';').trim())

private fun expandClause(prefix: String, clause0: String): List<Pair<String, String>> {
    val clause = clause0.split(" as ").first().trim().removeSuffix("::*").trim()
    val start = clause.indexOf('{')
    if (start >= 0) {
        val end = clause.lastIndexOf('}').takeIf { it >= 0 } ?: clause.length
        val head = clause.substring(0, start).trim().removeSuffix("::").trim()
        val next = when { prefix.isEmpty() -> head; head.isEmpty() -> prefix; else -> "$prefix::$head" }
        return splitCommas(clause.substring(start + 1, end)).flatMap { expandClause(next, it) }
    }
    if (clause.isEmpty() || clause == "*" || clause == "self") return if (prefix.isEmpty()) emptyList() else oneImport(prefix)
    return oneImport(if (prefix.isEmpty()) clause else "$prefix::$clause")
}

private fun oneImport(path: String): List<Pair<String, String>> =
    path.trim().takeIf { it.isNotEmpty() }?.let { listOf(it to importLastSegment(it)) } ?: emptyList()

/** Commas at brace depth 0, so `a::{b, c}` stays one item inside an outer list. */
private fun splitCommas(body: String): List<String> {
    val out = ArrayList<String>(); var depth = 0; var start = 0
    body.forEachIndexed { i, c ->
        when (c) { '{' -> depth++; '}' -> depth--; ',' -> if (depth == 0) { out += body.substring(start, i); start = i + 1 } }
    }
    out += body.substring(start)
    return out
}

private val IMPORT_SUFFIXES = listOf(".dart", ".tsx", ".ts", ".mjs", ".cjs", ".jsx", ".js")

/** Last path segment of an import / use / require specifier (T68.6). */
internal fun importLastSegment(raw: String): String {
    val t0 = raw.trim().trim('"', '\'', '`')
    val t = IMPORT_SUFFIXES.firstOrNull { t0.endsWith(it) }?.let { t0.removeSuffix(it) } ?: t0
    return t.split(':', '.', '/', '\\').lastOrNull { it.isNotEmpty() } ?: t
}

/** Definitions as `kind name line`, or verbatim definition lines. */
fun render(path: String, src: String, mode: String): String {
    if (isMarkdown(path) || isLlmsTxt(path)) {
        val hs = markdownHeadings(src)
        val links = if (isLlmsTxt(path)) llmsLinks(src) else emptyList()
        if (hs.isEmpty() && links.isEmpty()) return fallback(src)
        val parts = listOfNotNull(hs.takeIf { it.isNotEmpty() }?.let { markdownRender(it, mode) }, links.takeIf { it.isNotEmpty() }?.let(::renderLlmsLinks))
        return parts.joinToString("\n")
    }
    val hits = tags(path, src)
    val imports = hits.filter { it.kind == "import" && !it.isDef }.map { it.name }.distinct()
    val defs = hits.filter { it.isDef }
    if (defs.isEmpty() && imports.isEmpty()) return fallback(src)
    val out = ArrayList<String>()
    if (imports.isNotEmpty() && mode != "signatures") out += "imports: ${imports.joinToString(", ")}"
    for (hit in defs) out += if (mode == "signatures") hit.lineText.trimEnd()
    else "${hit.lineText.split(' ', '\t', '\n').firstOrNull { it.isNotEmpty() } ?: hit.kind} ${hit.name} ${hit.line}"
    return out.joinToString("\n")
}

private fun fallback(src: String): String =
    src.rustLines().take(60).withIndex().joinToString("\n") { (i, l) -> "${i + 1}:$l" } + "\n(note: unknown language, showing lines 1-60)"

/** Comments removed, newlines kept so line numbers still match. `null` → no grammar or parse failure (T50.3). */
fun stripped(path: String, src: String): String? {
    val grammar = Languages.grammar(extension(path)) ?: return null
    val bytes = src.encodeToByteArray()
    return grammar.withTree(bytes) { tree ->
        val drop = BooleanArray(bytes.size)
        markComments(tree.root, drop)
        keepBytes(bytes, drop).decodeToString() // comment nodes end on char boundaries
    }
}

private fun keepBytes(bytes: ByteArray, drop: BooleanArray): ByteArray {
    val out = ByteArray(bytes.size); var n = 0
    for (i in bytes.indices) if (!drop[i] || bytes[i] == '\n'.code.toByte()) out[n++] = bytes[i]
    return out.copyOf(n)
}

private fun markComments(node: Node, drop: BooleanArray) {
    if ("comment" in node.kind) {
        drop.fill(true, node.startByte.coerceAtMost(drop.size), node.endByte.coerceAtMost(drop.size)); return
    }
    for (child in node.children()) markComments(child, drop)
}

// --- markdown (T68.8) ---

data class MdHeading(val line: Int, val level: Int, val text: String, var body: String? = null)

/** `#` headings with the first non-empty body line; fenced blocks skipped (T68.8). */
fun markdownHeadings(src: String): List<MdHeading> {
    val out = ArrayList<MdHeading>()
    var fenced = false; var slot: Int? = null
    for ((i, line) in src.rustLines().withIndex()) {
        val t = line.trim()
        when {
            t.startsWith("```") -> fenced = !fenced
            fenced -> {}
            t.startsWith('#') -> {
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                out += MdHeading(i + 1, level, t); slot = out.size - 1
            }
            slot != null && t.isNotEmpty() -> { out[slot].body = t; slot = null }
        }
    }
    return out
}

/** Heading map for skill digest: full heading line plus indented first body line. */
fun markdownDigest(src: String): String = buildString {
    for (h in markdownHeadings(src)) {
        append(h.text).append('\n')
        h.body?.let { append("  ").append(it).append('\n') }
    }
}

/** Growable int list with Rust's `partition_point` (line-start tables). */
internal class IntArrayList {
    private var a = IntArray(64); var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
    fun partitionPoint(pred: (Int) -> Boolean): Int { var lo = 0; var hi = size; while (lo < hi) { val m = (lo + hi) ushr 1; if (pred(a[m])) lo = m + 1 else hi = m }; return lo }
}
