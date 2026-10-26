// Port of `outline`/`outline_in`, `outline_of_kind`, `symbol_arg_names` and `head_stale_prefix` from
// src/plugins/graph/mod.rs.
package rtok.plugins.graph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.HostFs
import rtok.plugin.sdk.Ctx
import rtok.plugins.read.Roots
import rtok.plugins.read.readCfg
import rtok.plugins.read.readWith
import rtok.plugins.read.resolve

object Outline {
    /** `outline(path)`: the `read` plugin's `map` mode, capped like the other tools. */
    fun outlineIn(
        cx: Ctx,
        root: String,
        path: String,
    ): String {
        // The path guard runs outside the door: a refused path is an error to report, not a server failure.
        val abs = if (GraphCore.modeOf(cx, root) != Mode.Tags) resolve(root, path, readCfg(cx).allowPaths) else null
        if (abs != null && !rtok.sys.exists(abs)) {
            val hint = Roots.movedHint(root, abs)
            if (hint.isNotEmpty()) throw IllegalStateException("No such file or directory: $path$hint")
        }
        return Tools.textOr(cx, root, "outline", { Text.outline(cx, root, abs ?: path) }) {
            val ws = abs?.let { Tools.lsp.outlineWorkspace(root, it) } ?: root
            Tools.lspOrTags(cx, ws, "outline", emptyList(), {
                GraphCore.withStale(cx, root, Tools.lsp.outline(cx, root, abs ?: path))
            }) {
                val text = readWith(cx, HostFs, root, path, "map", null)
                Tally.hit(root, "", listOf(path))
                GraphCore.withStale(cx, root, Tools.cap(cx, text))
            }
        }
    }

    /** T665: `outline` narrowed to one kind (`fn`, `struct`, …), the word after any `pub…` marker. */
    fun ofKind(
        text: String,
        path: String,
        kind: String,
    ): String {
        if (kind.isEmpty()) return text
        val kept = text.lines().filter { l -> l.split(' ', '\t').filter { it.isNotEmpty() }.firstOrNull { !it.startsWith("pub") } == kind }
        return if (kept.isEmpty()) "no $kind in $path" else kept.joinToString("\n")
    }

    /** `name`, or `names` when the caller asks for several. */
    fun symbolArgNames(args: JsonObject): List<String> {
        (args["names"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.ifEmpty { null } }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return listOf(str(args["name"]))
    }

    internal fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    /** `index <stored> head <live>` per root whose indexed commit is not HEAD; empty otherwise. */
    fun headStalePrefix(
        cx: Ctx,
        roots: List<String>,
    ): String =
        buildString {
            for (root in roots) {
                val raw = runCatching { cx.pluginStateGet("graph", Index.headKey(root)) }.getOrNull() ?: continue
                val stored = runCatching { Json.decodeFromString(Index.IndexedHead.serializer(), raw).head }.getOrNull()?.ifEmpty { null } ?: continue
                val live =
                    Git
                        .stdout(root, listOf("rev-parse", "HEAD"))
                        ?.decodeToString()
                        ?.trim()
                        ?.ifEmpty { null } ?: continue
                if (stored != live) append("index $stored head $live\n")
            }
        }
}
