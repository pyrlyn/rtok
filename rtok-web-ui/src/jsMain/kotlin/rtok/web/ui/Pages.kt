// Page renderers by id. A page not ported yet shows its snapshot field as formatted JSON, so every screen
// already carries the server's data; ported pages replace their entry.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.pre
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Renders one page into the main area. */
fun interface PageView {
    fun render(
        out: FlowContent,
        snapshot: JsonObject,
        app: App,
    )
}

object Pages {
    private val pretty = Json { prettyPrint = true }
    private val views = HashMap<String, PageView>()

    fun register(
        id: String,
        view: PageView,
    ) {
        views[id] = view
    }

    fun render(
        out: FlowContent,
        page: Page,
        snapshot: JsonObject,
        app: App,
    ) {
        val view = views[page.id]
        if (view != null) {
            view.render(out, snapshot, app)
            return
        }
        val field = snapshot[page.field] ?: JsonNull
        out.div("card") {
            val text = (field as? JsonPrimitive)?.takeIf { it.isString }?.content
            pre { +(text ?: pretty.encodeToString(field)) }
        }
    }
}

/** Typed replies (`message`, `expand`, ...) go to whichever page listens for that type. */
object Replies {
    private val listeners = HashMap<String, MutableList<(Frame.Reply) -> Unit>>()

    /** Adds [listener] for frames of [type]; several pages may listen to one type (`message`). */
    fun on(
        type: String,
        listener: (Frame.Reply) -> Unit,
    ) {
        listeners.getOrPut(type) { mutableListOf() }.add(listener)
    }

    fun dispatch(f: Frame.Reply) {
        listeners[f.type]?.toList()?.forEach { it(f) }
    }
}
