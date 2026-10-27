// Port of src/plugins/toon/mod.rs — tabular JSON in old tool results → TOON, only when it estimates
// fewer tokens; the original is archived and `expand` returns it. Same live-zone rule as `archive`.
package rtok.plugins.toon

import kotlinx.serialization.json.*
import rtok.MIN_PREFIX
import rtok.json.StrictJson
import rtok.plugin.sdk.*
import rtok.plugins.TOON_PREFIX
import rtok.plugins.archive.blockText
import rtok.plugins.archive.outsideLiveZone
import rtok.plugins.archive.utf8Len
import rtok.shortId
import rtok.config.Toon as ToonCfg

private const val ID = "toon"

object ToonPlugin : Plugin {
    override fun manifest() = Manifest(ID, listOf(Surface.Proxy, Surface.Mcp), true)

    override fun dashboardPage() = DashboardPage("TOON", "Compact tabular JSON in old tool results; expand returns the original.", true)

    override fun proxyFilter(
        req: WireRequest,
        cx: Ctx,
    ): List<Measurement> {
        val cfg = cx.pluginConfig(ID, ToonCfg.serializer(), ToonCfg())
        if (!cfg.enabled) return emptyList()
        return outsideLiveZone(req.toolResults(), cx).mapNotNull { rewriteBlock(it, cx, cfg.minRows.toInt()) }
    }
}

private fun Ctx.err(
    what: String,
    e: Throwable,
) = log("error", "plugin", ID, "$what: ${e.message}")

private fun rewriteBlock(
    r: ToolResultRef,
    cx: Ctx,
    minRows: Int,
): Measurement? {
    val content = r.content
    val orig = blockText(content) ?: (content as? JsonArray)?.toString() ?: return null
    val d =
        runCatching { cx.archiveDecision(r.id) }.getOrElse {
            cx.err("decision", it)
            return null
        }
    if (d != null) {
        // A pointer another plugin wrote under this id was measured there.
        if (d.expanded || !d.pointer.startsWith(TOON_PREFIX)) return null
        r.content = JsonPrimitive(d.pointer)
        return Measurement(ID, "encode", orig.utf8Len(), d.pointer.utf8Len(), cx.estimate(orig, Class.Code), cx.estimate(d.pointer, Class.Code), d.archiveId)
    }
    val table = runCatching { StrictJson.parse(orig) }.getOrNull() ?: return null
    val keys = tabularKeys(table, minRows) ?: return null
    val encoded = encode(table.jsonArray, keys)
    // Decide before writing anything, so a skipped block leaves no decision behind.
    val estBefore = cx.estimate(orig, Class.Code)
    val estPointer = cx.estimate("$TOON_PREFIX${"0".repeat(MIN_PREFIX)}]\n", Class.Code)
    if (estPointer + cx.estimate(encoded, Class.Code) >= estBefore) return null
    val id =
        runCatching { cx.putArchive(orig.encodeToByteArray()) }.getOrElse {
            cx.err("put", it)
            return null
        }
    val replacement = "$TOON_PREFIX${shortId(id)}]\n$encoded"
    runCatching { cx.putArchiveDecision(r.id, id, replacement) }.getOrElse {
        cx.err("decision", it)
        return null
    }
    r.content = JsonPrimitive(replacement)
    return Measurement(ID, "encode", orig.utf8Len(), replacement.utf8Len(), estBefore, cx.estimate(replacement, Class.Code), id)
}

private fun JsonElement.isScalar() = this is JsonPrimitive

/** Only arrays of uniform scalar objects (≥ 3 keys, ≥ minRows rows) encode; shared with the `--ai` report. */
fun tabularKeys(
    value: JsonElement,
    minRows: Int,
): List<String>? {
    val arr = value as? JsonArray ?: return null
    if (arr.size < maxOf(minRows, 1)) return null
    val first = arr[0] as? JsonObject ?: return null
    if (first.size < 3) return null
    val keys = first.keys.toList()
    if (keys.any { needsQuotes(it) || ':' in it }) return null
    for (item in arr) {
        val o = item as? JsonObject ?: return null
        if (o.size != keys.size) return null
        for (k in keys) if (o[k]?.isScalar() != true) return null
    }
    return keys
}

/** The TOON block for uniform [rows] under [keys], in the caller's column order. */
fun encode(
    rows: List<JsonElement>,
    keys: List<String>,
): String =
    buildString {
        append("[${rows.size}]{${keys.joinToString(",")}}:")
        for (row in rows) {
            append("\n  ")
            keys.forEachIndexed { i, k ->
                if (i > 0) append(',')
                append(encodeCell(row.jsonObject[k] ?: JsonNull))
            }
        }
    }

private fun encodeCell(v: JsonElement): String =
    when {
        v is JsonNull -> {
            ""
        }

        v is JsonPrimitive && v.isString -> {
            v.content.let { s ->
                if (needsQuotes(s)) {
                    "\"" +
                        s
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t") + "\""
                } else {
                    s
                }
            }
        }

        v is JsonPrimitive -> {
            v.content
        }

        else -> {
            ""
        }
    }

/** A bare cell must read back as the same string: empty, delimiters, padding, or number/bool/null-looking text is quoted. */
internal fun needsQuotes(s: String): Boolean = s.isEmpty() || s.any { it in ",{}\"\n\r\t" } || s.startsWith(' ') || s.endsWith(' ') || looksScalar(s)

private val JSON_NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

/** Text serde_json would read as a number, bool or null (kotlinx accepts bare words, so this is explicit). */
internal fun looksScalar(s: String) = s == "true" || s == "false" || s == "null" || JSON_NUMBER.matches(s)
