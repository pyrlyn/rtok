// Port of src/plugins/graph/mod.rs `GraphPlugin`: manifest, dashboard page, the Edit/Write stale
// mark, the session-start repo map and the MCP tool descriptors.
package rtok.plugins.graph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.json.StrictJson
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.DashboardPage
import rtok.plugin.sdk.Manifest
import rtok.plugin.sdk.Plugin
import rtok.plugin.sdk.PostToolUse
import rtok.plugin.sdk.SessionStart
import rtok.plugin.sdk.Surface
import rtok.plugin.sdk.ToolDef

object GraphPlugin : Plugin {
    override fun manifest() = Manifest("graph", listOf(Surface.Mcp, Surface.Hook), true)

    override fun dashboardPage() = DashboardPage("Graph", "symbol / callers / impact from a tree-sitter-tags index.", true)

    override fun postTool(
        ev: PostToolUse,
        cx: Ctx,
    ): String? {
        if (ev.toolName != "Edit" && ev.toolName != "Write") return null
        val path = ((ev.toolInput as? JsonObject)?.get("file_path") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (path != null) runCatching { cx.markSymbolsStale(Index.canon(path)) }
        return null
    }

    override fun sessionStart(
        ev: SessionStart,
        cx: Ctx,
    ) = RepoMap.inject(ev, cx)

    override fun mcpTools() = TOOLS

    private fun def(
        name: String,
        description: String,
        schema: String,
    ) = ToolDef(name, description, StrictJson.parse(schema))

    private val TOOLS =
        listOf(
            def(
                "symbol",
                "Definitions of a symbol — id, path:line kind, then the body — optional id or names; path or kind narrows a name.",
                """{"type":"object","properties":{"name":{"type":"string"},"names":{"type":"array","items":{"type":"string"}},"id":{"type":"string"},"path":{"type":"string"},"kind":{"type":"string"},"project":{"type":"string"}}}""",
            ),
            def(
                "callers",
                "Which definitions reference a symbol: path, caller, count. Optional path keeps one subtree. No name: unreferenced.",
                """{"type":"object","properties":{"name":{"type":"string"},"path":{"type":"string"},"kind":{"type":"string"},"project":{"type":"string"}}}""",
            ),
            def(
                "impact",
                "What breaks if a symbol changes: callers up to depth, grouped by file and cut at a token budget (all=true: every row). Optional to: chains reaching it. Empty name + path lists affected tests.",
                """{"type":"object","properties":{"name":{"type":"string"},"to":{"type":"string"},"depth":{"type":"integer"},"all":{"type":"boolean"},"path":{"type":"string"},"project":{"type":"string"}}}""",
            ),
            def(
                "outline",
                "Definitions in one file (read mode=map); kind filters.",
                """{"type":"object","properties":{"path":{"type":"string"},"kind":{"type":"string"},"project":{"type":"string"}},"required":["path"]}""",
            ),
            def(
                "explore",
                "Answers a code question: the query's symbols as definitions with bodies, call paths between them, impact counts. Optional path narrows.",
                """{"type":"object","properties":{"query":{"type":"string"},"path":{"type":"string"},"project":{"type":"string"}},"required":["query"]}""",
            ),
            def(
                "graph_diff",
                "Symbols changed between git refs, with callers.",
                """{"type":"object","properties":{"from":{"type":["string","array"],"description":"ref, default HEAD; project:ref for one project; a list for several"},"to":{"type":"string","description":"ref or working (default)"},"project":{"type":"string"}}}""",
            ),
            def(
                "graph_export",
                "The graph as redacted rtok.graph.v1 JSON.",
                """{"type":"object","properties":{"level":{"type":"string","description":"overview (default) or symbols"},"focus":{"type":"string","description":"symbol; with depth"},"depth":{"type":"integer"},"project":{"type":"string"}}}""",
            ),
        )
}
