package rtok.plugins.graph

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import rtok.plugin.sdk.Ctx

/** MCP dispatch for the five graph tools (mod.rs `call`). A throw becomes an `isError` result. */
object Dispatch {
    /**
     * [scope] is the project scope of the call, resolved by the caller; empty means the cwd alone.
     * Every tool but `outline` is headed by the stale-HEAD lines of its scope.
     */
    fun call(cx: Ctx, name: String, args: JsonObject, scope: List<Member>): String {
        val members = scope.ifEmpty { listOf(Member("", rtok.sys.cwd())) }
        fun arg(k: String) = Outline.str(args[k])
        fun depth(default: Int) = ((args["depth"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: default.toLong()).toInt()
        val filter = Filter(arg("path"), arg("kind"), (args["all"] as? JsonPrimitive)?.booleanOrNull ?: false)
        val text = when (name) {
            "symbol" -> {
                val id = arg("id")
                if (id.isNotEmpty()) Scope.symbolId(cx, members, id)
                else {
                    val names = Outline.symbolArgNames(args)
                    if (names.all { it.isEmpty() }) throw IllegalArgumentException("missing `name` or `id`")
                    Scope.symbols(cx, members, names, filter)
                }
            }
            "callers" -> if (arg("name").isEmpty()) Scope.deadFiltered(cx, members, filter) else Scope.callers(cx, members, arg("name"), filter)
            "impact" -> if (arg("name").isEmpty()) Scope.affectedPath(cx, members, arg("path"), depth(3))
                else Scope.impact(cx, members, arg("name"), depth(2), filter, (args["to"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
            "outline" -> return Outline.ofKind(Scope.outline(cx, members, arg("path")), arg("path"), filter.kind)
            "explore" -> Scope.explore(cx, members, arg("query"), filter)
            else -> throw IllegalArgumentException("unknown tool: $name")
        }
        return Outline.headStalePrefix(cx, members.map { it.root }) + text
    }
}
