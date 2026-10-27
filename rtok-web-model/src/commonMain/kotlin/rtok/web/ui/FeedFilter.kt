// Port of web/src/pages/graph3d/live/feedFilter.ts and useCalls.ts helpers: the live graph-call feed's filter,
// distinct values and the subscription request.
package rtok.web.ui

import kotlinx.serialization.json.JsonObject

/** CallFeed's three selects; an empty value matches every row. */
data class FeedFilter(
    val caller: String = "",
    val tool: String = "",
    val project: String = "",
)

/** feedFilter.ts `filterFeed`. */
fun filterFeed(
    feed: List<JsonObject>,
    f: FeedFilter,
): List<JsonObject> =
    feed.filter { r ->
        (f.caller.isEmpty() || r.str("caller") == f.caller) &&
            (f.tool.isEmpty() || r.str("tool") == f.tool) &&
            (f.project.isEmpty() || r.str("project") == f.project)
    }

/** feedFilter.ts `distinct`: the sorted non-empty values of [key]. */
fun distinct(
    feed: List<JsonObject>,
    key: String,
): List<String> = feed.mapNotNull { it.str(key)?.takeIf(String::isNotEmpty) }.distinct().sorted()

/** useCalls.ts `totalCalls`: the server totals "since start" last. */
fun totalCalls(view: JsonObject): Double = view.objects("windows").lastOrNull()?.num("calls") ?: 0.0

/** The socket's subscription message (mod.rs `calls_subscription`). */
fun callsSubscribe(on: Boolean): String = """{"calls":{"subscribe":$on}}"""
