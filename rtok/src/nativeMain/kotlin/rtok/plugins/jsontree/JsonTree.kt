// Port of src/plugins/json_tree/mod.rs — fold a nested JSON tree into hoisted values (`V-`) and repeated
// element bodies (`EL-`), so a large design or AST is not replaced by an archive head/tail. Off by default;
// the original bytes are archived and `rtok expand <id>` returns them.
package rtok.plugins.jsontree

import kotlinx.serialization.json.*
import rtok.MIN_PREFIX
import rtok.json.StrictJson
import rtok.plugin.sdk.*
import rtok.plugins.JSON_TREE_PREFIX
import rtok.plugins.TOON_PREFIX
import rtok.plugins.archive.blockText
import rtok.plugins.archive.outsideLiveZone
import rtok.plugins.archive.utf8Len
import rtok.plugins.toon.tabularKeys
import rtok.shortId
import rtok.sys.Sha1
import rtok.sys.Utf8Order
import rtok.sys.jsonQuote
import rtok.config.JsonTree as JsonTreeCfg

private const val ID = "json_tree"
internal const val MIN_BYTES = 256
private const val CHILDREN = "children"

/** A folded tree: `nodes` counts object lines, `templates` counts `EL-` bodies. */
data class Folded(
    val text: String,
    val nodes: Int,
    val templates: Int,
)

object JsonTreePlugin : Plugin {
    override fun manifest() = Manifest(ID, listOf(Surface.Proxy, Surface.Mcp), false)

    override fun dashboardPage() = DashboardPage("JSON tree", "Fold repeated structure in old JSON tool results; expand returns the original.", true)

    override fun proxyFilter(
        req: WireRequest,
        cx: Ctx,
    ): List<Measurement> {
        if (!cx.pluginConfig(ID, JsonTreeCfg.serializer(), JsonTreeCfg()).enabled) return emptyList()
        return outsideLiveZone(req.toolResults(), cx).mapNotNull { rewriteBlock(it, cx) }
    }
}

private fun Ctx.err(
    what: String,
    e: Throwable,
) = log("error", "plugin", ID, "$what: ${e.message}")

private fun measurement(
    cx: Ctx,
    before: String,
    after: String,
    id: String,
) = Measurement(ID, "fold", before.utf8Len(), after.utf8Len(), cx.estimate(before, Class.Code), cx.estimate(after, Class.Code), id)

private fun rewriteBlock(
    r: ToolResultRef,
    cx: Ctx,
): Measurement? {
    val orig = blockText(r.content) ?: return null
    val d =
        runCatching { cx.archiveDecision(r.id) }.getOrElse {
            cx.err("decision", it)
            return null
        }
    if (d != null) {
        if (d.expanded || d.pointer.startsWith(TOON_PREFIX) || !d.pointer.startsWith(JSON_TREE_PREFIX)) return null
        r.content = JsonPrimitive(d.pointer)
        return measurement(cx, orig, d.pointer, d.archiveId)
    }
    val value = runCatching { StrictJson.parse(orig) }.getOrNull() ?: return null
    val folded = foldJson(value) ?: return null
    val preview = "$JSON_TREE_PREFIX${"0".repeat(MIN_PREFIX)}]\n${folded.text}"
    if (cx.estimate(preview, Class.Code) >= cx.estimate(orig, Class.Code)) return null
    val id =
        runCatching { cx.putArchive(orig.encodeToByteArray()) }.getOrElse {
            cx.err("put", it)
            return null
        }
    val replacement = "$JSON_TREE_PREFIX${shortId(id)}]\n${folded.text}"
    runCatching { cx.putArchiveDecision(r.id, id, replacement) }.getOrElse {
        cx.err("decision", it)
        return null
    }
    r.content = JsonPrimitive(replacement)
    return measurement(cx, orig, replacement, id)
}

/** Fold with identity keys `id` and `name`; null when too small, flat, or a uniform table (`toon` keeps those). */
fun foldJson(value: JsonElement): Folded? = foldJsonWith(value, listOf("id", "name"))

fun foldJsonWith(
    value: JsonElement,
    identity: List<String>,
): Folded? {
    if (value !is JsonObject && value !is JsonArray) return null
    if (value.toString().encodeToByteArray().size < MIN_BYTES) return null
    if (!hasNestedObject(value)) return null
    if (tabularKeys(value, 1) != null) return null
    val counts = HashMap<String, Int>()
    tally(value, counts)
    val vars = assignIds("V-", counts, 2)
    val pieces: List<Piece> =
        when (value) {
            is JsonArray -> value.map { pieceOf(it, identity, vars) }
            is JsonObject -> listOf(Piece.Node(buildNode(value, identity, vars)))
            else -> return null
        }
    val bodies = HashMap<String, Int>()
    collectBodies(pieces, bodies)
    val elements = assignIds("EL-", bodies, 2)
    stamp(pieces, elements)
    val text = render(vars, elements, referencedVars(pieces), pieces)
    if (text.isEmpty()) return null
    return Folded(text, countNodes(pieces), elements.size)
}

private fun hasNestedObject(v: JsonElement): Boolean =
    when (v) {
        is JsonArray -> v.any(::containsObject)
        is JsonObject -> v.values.any(::containsObject)
        else -> false
    }

private fun containsObject(v: JsonElement): Boolean =
    when (v) {
        is JsonObject -> true
        is JsonArray -> v.any(::containsObject)
        else -> false
    }

private fun tally(
    v: JsonElement,
    counts: MutableMap<String, Int>,
) {
    when (v) {
        is JsonArray -> {
            v.forEach { tally(it, counts) }
        }

        is JsonObject -> {
            for ((k, c) in v) {
                if (k == CHILDREN && c is JsonArray) {
                    tally(c, counts)
                    continue
                }
                if (c is JsonObject || c is JsonArray) counts[canonical(c)] = (counts[canonical(c)] ?: 0) + 1
                tally(c, counts)
            }
        }

        else -> {}
    }
}

/** material → id for every material seen at least [min] times, assigned in byte order. */
private fun assignIds(
    prefix: String,
    counts: Map<String, Int>,
    min: Int,
): Map<String, String> {
    val used = HashMap<String, String>()
    return counts
        .filterValues { it >= min }
        .keys
        .sortedWith(Utf8Order)
        .associateWith { contentId(prefix, it, used) }
}

/** Truncated content id; a clash with a different body lengthens the hex by 4 until free or full. */
internal fun contentId(
    prefix: String,
    material: String,
    used: MutableMap<String, String>,
): String {
    val hash = Sha1.hex(material.encodeToByteArray())
    var len = 8
    while (true) {
        val take = minOf(len, hash.length)
        val id = prefix + hash.take(take)
        val existing = used[id]
        when {
            existing == null -> {
                used[id] = material
                return id
            }

            existing == material || take == hash.length -> {
                return id
            }

            else -> {
                len += 4
            }
        }
    }
}

/** A body field: a reference to a hoisted `V-` value, or the literal — kept apart so a string spelled like an id stays a string. */
private sealed interface Field {
    data class Ref(
        val id: String,
    ) : Field

    data class Lit(
        val v: JsonElement,
    ) : Field
}

private class ObjNode(
    val label: String,
    val bracket: String?,
    val idText: String?,
    val extras: Map<String, String>,
    val body: Map<String, Field>,
    val children: List<Piece>,
    var template: String? = null,
)

private sealed interface Piece {
    class Node(
        val n: ObjNode,
    ) : Piece

    class Atom(
        val text: String,
    ) : Piece
}

private fun pieceOf(
    v: JsonElement,
    identity: List<String>,
    vars: Map<String, String>,
): Piece = if (v is JsonObject) Piece.Node(buildNode(v, identity, vars)) else Piece.Atom(canonical(v))

private fun <V> sortedByKey(m: Map<String, V>): Map<String, V> = m.keys.sortedWith(Utf8Order).associateWith { m.getValue(it) }

private fun buildNode(
    obj: JsonObject,
    identity: List<String>,
    vars: Map<String, String>,
): ObjNode {
    val bracket = bracketKey(obj)
    val label = bracket?.let { obj[it] }?.let(::scalarText) ?: "object"
    val extras = HashMap<String, String>()
    val body = HashMap<String, Field>()
    val children = ArrayList<Piece>()
    for ((k, v) in obj) {
        when {
            k == CHILDREN && v is JsonArray -> v.mapTo(children) { pieceOf(it, identity, vars) }
            k == "id" -> if ("id" !in identity) body[k] = field(v, vars)
            k in identity -> extras[k] = renderValue(v)
            else -> body[k] = field(v, vars)
        }
    }
    return ObjNode(label, bracket, obj["id"]?.let(::scalarText), sortedByKey(extras), sortedByKey(body), children)
}

private fun field(
    v: JsonElement,
    vars: Map<String, String>,
): Field = (if (v is JsonObject || v is JsonArray) vars[canonical(v)]?.let(Field::Ref) else null) ?: Field.Lit(v)

private fun bracketKey(obj: JsonObject): String? {
    for (k in listOf("type", "kind")) {
        val v = obj[k] ?: continue
        if (v !is JsonObject && v !is JsonArray) return k
    }
    return null
}

private fun JsonElement.stringOrNull() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun scalarText(v: JsonElement) = v.stringOrNull() ?: canonical(v)

private fun typeOnly(body: Map<String, Field>) = body.keys.all { it == "type" || it == "kind" }

/** Template key and ELEMENTS text: a reference is a bare id, a string always quoted, so they never read alike. */
private fun canonicalBody(body: Map<String, Field>) =
    body.entries.joinToString(",", "{", "}") { (k, f) ->
        "${jsonQuote(k)}:" +
            when (f) {
                is Field.Ref -> f.id
                is Field.Lit -> canonical(f.v)
            }
    }

private fun nodes(pieces: List<Piece>): Sequence<ObjNode> =
    sequence {
        for (p in pieces) {
            if (p is Piece.Node) {
                yield(p.n)
                yieldAll(nodes(p.n.children))
            }
        }
    }

private fun collectBodies(
    pieces: List<Piece>,
    counts: MutableMap<String, Int>,
) = nodes(pieces).filterNot { typeOnly(it.body) }.forEach {
    val k = canonicalBody(it.body)
    counts[k] = (counts[k] ?: 0) + 1
}

private fun stamp(
    pieces: List<Piece>,
    elements: Map<String, String>,
) = nodes(pieces).forEach { it.template = elements[canonicalBody(it.body)] }

private fun referencedVars(pieces: List<Piece>): Set<String> =
    nodes(pieces)
        .flatMap { n ->
            n.body.values
                .filterIsInstance<Field.Ref>()
                .map { it.id }
        }.toSet()

private fun countNodes(pieces: List<Piece>) = nodes(pieces).count()

private fun render(
    vars: Map<String, String>,
    elements: Map<String, String>,
    referenced: Set<String>,
    pieces: List<Piece>,
): String =
    buildString {
        fun section(
            title: String,
            lines: List<Pair<String, String>>,
        ) {
            if (lines.isEmpty()) return
            append(title).append(":\n")
            lines.sortedWith { a, b -> compareUtf8Pair(a, b) }.forEach { (id, canon) -> append(id).append(": ").append(canon).append('\n') }
        }
        section("VARS", vars.filterValues { it in referenced }.map { (canon, id) -> id to canon })
        section("ELEMENTS", elements.map { (canon, id) -> id to canon })
        renderPieces(pieces, 0)
    }

private fun compareUtf8Pair(
    a: Pair<String, String>,
    b: Pair<String, String>,
) = Utf8Order.compare(a.first, b.first).takeIf { it != 0 } ?: Utf8Order.compare(a.second, b.second)

private fun StringBuilder.renderPieces(
    pieces: List<Piece>,
    depth: Int,
) {
    for (p in pieces) {
        when (p) {
            is Piece.Atom -> append("  ".repeat(depth)).append(p.text).append('\n')
            is Piece.Node -> renderNode(p.n, depth)
        }
    }
}

private fun StringBuilder.renderNode(
    n: ObjNode,
    depth: Int,
) {
    append("  ".repeat(depth)).append('[').append(bracketText(n.label)).append(']')
    n.idText?.let { append(" #").append(renderToken(it)) }
    for ((k, v) in n.extras) append(' ').append(renderToken(k)).append('=').append(v)
    val t = n.template
    if (t != null) {
        append(" template=").append(t)
    } else {
        for ((k, f) in n.body) {
            if (n.bracket == k) continue
            append(' ').append(renderToken(k)).append('=').append(
                when (f) {
                    is Field.Ref -> f.id
                    is Field.Lit -> renderValue(f.v)
                },
            )
        }
    }
    append('\n')
    renderPieces(n.children, depth + 1)
}

private fun bracketText(t: String) = if (t.isEmpty() || t.any { it == ']' || it == ' ' || it == '\n' }) jsonQuote(t) else t

private fun renderValue(v: JsonElement) = v.stringOrNull()?.let(::renderToken) ?: canonical(v)

private fun renderToken(t: String) = if (bare(t)) t else jsonQuote(t)

/** A bare token is what a reference prints as, so text shaped like a generated id is quoted. */
private fun bare(t: String) = t.isNotEmpty() && t.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_-./:+@" } && !referenceShaped(t)

private fun referenceShaped(t: String) =
    listOf("V-", "EL-").any { p ->
        t.startsWith(p) && t.substring(p.length).let { h -> h.length >= 8 && h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }
    }

/** Canonical JSON: object keys in byte order, no whitespace, serde string escaping. */
internal fun canonical(v: JsonElement): String = buildString { writeCanon(v) }

private fun StringBuilder.writeCanon(v: JsonElement) {
    when (v) {
        is JsonNull -> {
            append("null")
        }

        is JsonPrimitive -> {
            if (v.isString) append(jsonQuote(v.content)) else append(v.content)
        }

        is JsonArray -> {
            append('[')
            v.forEachIndexed { i, e ->
                if (i > 0) append(',')
                writeCanon(e)
            }
            append(']')
        }

        is JsonObject -> {
            append('{')
            v.keys.sortedWith(Utf8Order).forEachIndexed { i, k ->
                if (i > 0) append(',')
                append(jsonQuote(k)).append(':')
                writeCanon(v.getValue(k))
            }
            append('}')
        }
    }
}
