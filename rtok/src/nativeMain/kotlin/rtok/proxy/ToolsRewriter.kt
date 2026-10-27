// Port of src/proxy/tools_rewrite.rs: opt-in `tools[]` description rewrite (T59.5) and schema prose compaction
// (T686). Off: the request is untouched. Kotlin JSON is immutable, so the rewritten body is returned.
package rtok.proxy

import kotlinx.serialization.json.*
import rtok.config.Estimator
import rtok.config.ToolsRewrite
import rtok.estimate
import rtok.plugin.sdk.Class
import rtok.store.SerdeJson

object ToolsRewriter {
    /** Before/after description bytes and estimates for one request. */
    data class Delta(
        val beforeBytes: Long,
        val afterBytes: Long,
        val estBefore: Int,
        val estAfter: Int,
        val changed: Boolean,
        /** The `tools[]` array as received, serialized, when any of it changed (T686). */
        val original: String?,
    )

    data class Result(
        val body: JsonElement,
        val delta: Delta,
    )

    private val SCHEMA_PATHS = listOf(listOf("input_schema"), listOf("parameters"), listOf("function", "parameters"))

    /** One tool's outcome: the kept tool (null = dropped) and its before/after stats. */
    private class Step(
        val kept: JsonElement?,
        val before: Pair<Long, Int>,
        val after: Pair<Long, Int>,
    )

    private val NONE = 0L to 0

    private fun step(
        tool: JsonElement,
        cfg: ToolsRewrite,
        est: Estimator,
    ): Step {
        val name = toolName(tool) ?: return Step(tool, NONE, NONE)
        val drop = name in cfg.deny || (cfg.allow.isNotEmpty() && name !in cfg.allow)
        val before = stats(tool, cfg.compactSchema, est)
        val next = if (drop) null else shrink(tool, cfg, est)
        return Step(next, before, next?.let { stats(it, cfg.compactSchema, est) } ?: NONE)
    }

    /** Truncate descriptions and drop allow/deny names; null when off or `tools[]` is absent. */
    fun rewrite(
        body: JsonElement,
        cfg: ToolsRewrite,
        est: Estimator,
    ): Result? {
        val o = body as? JsonObject
        val tools = (o?.get("tools") as? JsonArray)?.takeIf { cfg.enabled } ?: return null
        val steps = tools.map { step(it, cfg, est) }
        val out = steps.mapNotNull { it.kept }
        val changed = out != tools.toList()
        val delta =
            Delta(
                steps.sumOf { it.before.first },
                steps.sumOf { it.after.first },
                steps.sumOf { it.before.second },
                steps.sumOf { it.after.second },
                changed,
                if (changed) SerdeJson.encode(tools) else null,
            )
        return Result(if (changed) JsonObject(o + ("tools" to JsonArray(out))) else body, delta)
    }

    private fun shrink(
        tool: JsonElement,
        cfg: ToolsRewrite,
        est: Estimator,
    ): JsonElement {
        var t = tool
        val max = cfg.maxDescriptionTokens.toInt()
        val path = descPath(t)
        val desc = path?.let { (t.at(it) as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (max > 0 && path != null && desc != null) {
            val trimmed = Sentences.truncateAtSentence(desc, max, est)
            if (trimmed != desc) t = t.with(path, JsonPrimitive(trimmed))
        }
        if (cfg.compactSchema) {
            for (p in SCHEMA_PATHS) t.at(p)?.let { s -> t = t.with(p, SchemaCompact.compactSchema(s)) }
        }
        return t
    }

    private fun JsonElement.at(path: List<String>): JsonElement? = path.fold(this as JsonElement?) { e, k -> (e as? JsonObject)?.get(k) }

    private fun JsonElement.with(
        path: List<String>,
        v: JsonElement,
    ): JsonElement {
        val o = this as? JsonObject ?: return this
        val k = path.first()
        return JsonObject(o + (k to if (path.size == 1) v else (o[k] ?: JsonNull).with(path.drop(1), v)))
    }

    private fun toolName(tool: JsonElement): String? = tool.str("name") ?: (tool as? JsonObject)?.get("function").str("name")

    private fun descPath(tool: JsonElement): List<String>? =
        when {
            tool.str("description") != null -> listOf("description")
            (tool as? JsonObject)?.get("function").str("description") != null -> listOf("function", "description")
            else -> null
        }

    /** What the rewrite can shrink: the description, or the whole tool once the schema is in play. */
    private fun stats(
        tool: JsonElement,
        whole: Boolean,
        est: Estimator,
    ): Pair<Long, Int> {
        val text = if (whole) SerdeJson.encode(tool) else descPath(tool)?.let { tool.at(it).str() }.orEmpty()
        return text.encodeToByteArray().size.toLong() to estimate(text, if (whole) Class.Json else Class.Prose, est)
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
