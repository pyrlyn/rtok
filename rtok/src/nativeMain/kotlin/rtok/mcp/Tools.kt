// `tools/call` dispatch of `rtok mcp` (mcp.rs `invoke` and the per-tool argument readers).
package rtok.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.Expand
import rtok.mcp.Protocol.bool
import rtok.mcp.Protocol.int
import rtok.mcp.Protocol.long
import rtok.mcp.Protocol.saturatingU32
import rtok.mcp.Protocol.str
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.ToolDef
import rtok.plugins.memory.Saved

object Tools {
    val EXPAND =
        ToolDef(
            "expand",
            "Return archived payload by id; optional lines a-b, regex grep (hits as N:line), context N.",
            Json.parseToJsonElement(
                """{"type":"object","properties":{"id":{"type":"string"},"lines":{"type":"string"},"grep":{"type":"string"},"context":{"type":"integer"}},"required":["id"]}""",
            ),
        )

    val PING =
        ToolDef(
            "ping",
            "Alive check. Returns MCP <agent> жив for the host display name.",
            Json.parseToJsonElement("""{"type":"object","properties":{"agent":{"type":"string"}}}"""),
        )

    private const val PACK_LIMIT = 8L
    private const val PACK_LIMIT_MAX = 20L
    private const val PACK_TOKENS = 400L
    private const val PACK_TOKENS_MAX = 2000L
    private const val HANDOFF_TOKENS = 800u

    private val GRAPH = setOf("symbol", "callers", "impact", "outline", "explore", "graph_diff", "graph_export")

    /** [invoke] as `(text, ok)`: a throw's bare message is the error text. */
    fun invokeText(
        cx: Runtime,
        name: String,
        args: JsonObject,
    ): Pair<String, Boolean> = runCatching { invoke(cx, name, args) }.fold({ it to true }) { (it.message ?: it.toString()) to false }

    fun invoke(
        cx: Runtime,
        name: String,
        args: JsonObject,
    ): String =
        when (name) {
            "ping" -> {
                pingText(str(args, "agent").orEmpty())
            }

            "expand" -> {
                expand(cx, args)
            }

            "mem_save" -> {
                memSave(cx, args)
            }

            "mem_search" -> {
                memSearch(cx, args)
            }

            "mem_pack" -> {
                rtok.plugins.memory.memPack(
                    cx,
                    str(args, "query").orEmpty(),
                    (long(args, "limit")?.coerceIn(1, PACK_LIMIT_MAX) ?: PACK_LIMIT).toUInt(),
                    (long(args, "max_tokens")?.coerceIn(1, PACK_TOKENS_MAX) ?: PACK_TOKENS).toUInt(),
                )
            }

            "mem_get" -> {
                memGet(cx, args)
            }

            "mem_update" -> {
                rtok.plugins.memory.memUpdate(cx, noteId(args, "id", "note"), bool(args, "retire") ?: false, int(args, "superseded_by"), bool(args, "pinned"))
            }

            "handoff" -> {
                rtok.plugins.memory.handoff(Ctx(cx), saturatingU32(args, "budget_tokens") ?: HANDOFF_TOKENS)
            }

            "docs_resolve" -> {
                rtok.plugins.docs.docsResolve(cx, str(args, "name").orEmpty())
            }

            "docs_query" -> {
                rtok.plugins.docs.docsQuery(cx, str(args, "name").orEmpty(), str(args, "version"), str(args, "query").orEmpty())
            }

            "docs_get" -> {
                rtok.plugins.docs.docsGet(cx, noteId(args, "id", "doc"))
            }

            "read" -> {
                rtok.plugins.read.read(Ctx(cx), str(args, "path").orEmpty(), str(args, "mode").orEmpty(), str(args, "range"))
            }

            "search" -> {
                rtok.plugins.read.Search
                    .search(Ctx(cx), str(args, "pattern").orEmpty(), str(args, "path") ?: ".", saturatingU32(args, "max"))
            }

            "tree" -> {
                rtok.plugins.read.Search
                    .tree(Ctx(cx), str(args, "path") ?: ".", saturatingU32(args, "depth"))
            }

            in GRAPH -> {
                GraphTools.call(cx, name, args)
            }

            else -> {
                throw Protocol.unknownTool(name)
            }
        }

    fun pingText(agent: String) = "MCP $agent жив"

    private fun expand(
        cx: Runtime,
        args: JsonObject,
    ): String {
        val id = str(args, "id").orEmpty()
        val lines = str(args, "lines")
        lines?.let { Expand.parseRange(it, Int.MAX_VALUE) }
        val bytes = Expand.fetchRange(cx, id, lines) ?: throw ToolException(Expand.unknownIdMessage(id))
        val context = long(args, "context")?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0
        val body = Expand.filterLines(bytes.decodeToString(), lines, str(args, "grep"), context).joinToString("\n")
        return capResult(
            body,
            id,
            cx.config.mcp.maxResultChars
                .toInt(),
        )
    }

    /** [text] within [max] chars; a longer one is cut around an `expand(<id>)` marker. */
    internal fun capResult(
        text: String,
        id: String,
        max: Int,
    ): String {
        if (text.length <= max) return text
        val shown = if (id.length >= 64) rtok.shortId(id) else id
        return Expand.cut(text, "\n… expand($shown) …\n", max)
    }

    private fun memSave(
        cx: Runtime,
        args: JsonObject,
    ): String {
        val saved = rtok.plugins.memory.memSaveGated(cx, str(args, "kind") ?: "note", str(args, "title").orEmpty(), str(args, "body").orEmpty(), str(args, "project"))
        return when (saved) {
            is Saved.Stored -> {
                buildJsonObject {
                    put("id", saved.id)
                    put("updated", saved.updated)
                }
            }

            is Saved.Refused -> {
                buildJsonObject {
                    put("id", saved.existing)
                    put("updated", false)
                    put("refused", saved.reason)
                }
            }
        }.toString()
    }

    private fun memSearch(
        cx: Runtime,
        args: JsonObject,
    ): String {
        val max =
            cx.config.plugins.memory.searchLimit
                .toLong()
        val limit = (long(args, "limit")?.coerceIn(0, max) ?: max).toUInt()
        val hits = rtok.plugins.memory.memSearch(cx, str(args, "query").orEmpty(), limit)
        if (hits.isEmpty()) return "no notes match"
        return hits.joinToString("\n") { Protocol.memHitLine(it.id, it.title, it.snippet) }
    }

    private fun memGet(
        cx: Runtime,
        args: JsonObject,
    ): String {
        val (kind, id, text) =
            if (args.containsKey("obs") && long(args, "obs") != null) {
                val id = noteId(args, "obs", "obs")
                Triple("observation", id, rtok.plugins.memory.obsGet(cx, id))
            } else {
                val id = noteId(args, "id", "note")
                Triple("note", id, rtok.plugins.memory.memGet(cx, id))
            }
        return text ?: throw ToolException("unknown $kind id: $id")
    }

    private fun noteId(
        args: JsonObject,
        key: String,
        what: String,
    ): Int = int(args, key) ?: throw ToolException("invalid $what id: ${args[key] ?: "null"}")
}
