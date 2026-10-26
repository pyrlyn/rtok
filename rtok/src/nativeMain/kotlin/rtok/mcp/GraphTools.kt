// The graph branch of mcp.rs `invoke`: auto-add the project, resolve the scope, record the call
// events and head the answer with the health notice.
package rtok.mcp

import kotlinx.serialization.json.JsonObject
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.plugins.graph.Alerts
import rtok.plugins.graph.Dispatch
import rtok.plugins.graph.ExportView
import rtok.plugins.graph.GraphCall
import rtok.plugins.graph.GraphDiffs
import rtok.plugins.graph.Scope
import rtok.store.Origin
import rtok.store.autoAddProject

object GraphTools {
    fun call(
        cx: Runtime,
        name: String,
        args: JsonObject,
    ): String {
        val cwd = rtok.sys.cwd()
        if (cx.config.plugins.graph.autoAddProjects) runCatching { cx.store.autoAddProject(cwd, Origin.Mcp, null) }
        val project = Protocol.str(args, "project")
        val call = GraphCall.start(cx, name, args)
        val answer =
            runCatching {
                val scope = Scope.resolve(cx.store, project, cwd)
                call.progress(scope.size)
                when (name) {
                    "graph_diff" -> GraphDiffs.call(cx, args, scope)
                    "graph_export" -> ExportView.call(cx, args, scope)
                    else -> Dispatch.call(Ctx(cx), name, args, scope)
                }
            }
        call.end(answer)
        val text = answer.getOrThrow()
        return Alerts.notice(cx, project, cwd)?.let { it + text } ?: text
    }
}
