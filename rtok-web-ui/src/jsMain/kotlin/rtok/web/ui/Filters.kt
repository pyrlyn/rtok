// Page filters kept in the URL query (web/src tableLink search params), so a link can open a filtered table
// and a reload keeps it.
package rtok.web.ui

import kotlinx.browser.window
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL

/** The query parameter [name], or [default]. */
fun param(
    name: String,
    default: String,
): String = URL(window.location.href).searchParams.get(name)?.takeIf { it.isNotEmpty() } ?: default

/** Sets (or, at its [default], drops) the query parameter [name] without a history entry, then re-renders. */
fun setParam(
    app: App,
    name: String,
    value: String,
    default: String,
) {
    val url = URL(window.location.href)
    if (value == default) url.searchParams.delete(name) else url.searchParams.set(name, value)
    window.history.replaceState(null, "", url.href)
    app.refresh()
}

/** A search box bound to `?q=`. */
fun FlowContent.searchBox(
    app: App,
    id: String,
    placeholder: String,
) {
    input(InputType.search, classes = "search") {
        this.id = id
        this.placeholder = placeholder
        attributes["aria-label"] = placeholder
        value = param("q", "")
        onInputFunction = { e -> setParam(app, "q", (e.target as HTMLInputElement).value, "") }
    }
}

/** A row of toggle buttons for `?[name]=` over [options]; the first option is the default. */
fun FlowContent.toggles(
    app: App,
    name: String,
    options: List<String>,
) {
    val current = param(name, options.first())
    div("toggles") {
        attributes["role"] = "group"
        attributes["aria-label"] = name
        options.forEach { opt ->
            button(classes = if (opt == current) "on" else null) {
                attributes["aria-pressed"] = (opt == current).toString()
                onClickFunction = { setParam(app, name, opt, options.first()) }
                +opt
            }
        }
    }
}
