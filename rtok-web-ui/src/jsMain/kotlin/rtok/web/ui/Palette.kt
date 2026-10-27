// Port of web/src/palette/Palette.tsx and shortcuts.ts: ⌘K / Ctrl+K opens a jump palette over pages, plugins,
// recent sessions and hosts (arrows, Enter, Escape, outside click), `g <key>` opens a page and `?` lists the
// keys. The overlay lives outside the app root so snapshot re-renders never steal its input.
package rtok.web.ui

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.dom.append
import kotlinx.html.input
import kotlinx.html.js.onClickFunction
import kotlinx.html.kbd
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

/** ⌘K on macOS, Ctrl+K elsewhere; works inside fields too. */
private fun isPaletteKey(e: KeyboardEvent) = e.key.lowercase() == "k" && (e.metaKey || e.ctrlKey) && !e.altKey

private fun modified(e: KeyboardEvent) = e.metaKey || e.ctrlKey || e.altKey

/** A letter typed into a field is text, never a shortcut. */
private fun isTyping(e: KeyboardEvent): Boolean {
    val t = e.target as? HTMLElement ?: return false
    return t.isContentEditable || t.tagName in setOf("INPUT", "TEXTAREA", "SELECT")
}

/** The palette and the global shortcuts. */
class Palette(
    private val snapshot: () -> JsonObject?,
    private val go: (String) -> Unit,
) {
    private var overlay: HTMLElement? = null
    private var query = ""
    private var focus = 0
    private var pendingG = 0.0

    fun install() {
        window.addEventListener("keydown", { e -> onKey(e as KeyboardEvent) })
    }

    private fun onKey(e: KeyboardEvent) {
        when {
            e.defaultPrevented -> {
                Unit
            }

            isPaletteKey(e) -> {
                e.preventDefault()
                if (overlay == null) open() else close()
            }

            overlay != null -> {
                paletteKey(e)
            }

            modified(e) || isTyping(e) -> {
                Unit
            }

            else -> {
                shortcut(e)
            }
        }
    }

    private fun shortcut(e: KeyboardEvent) {
        val now = e.timeStamp.toDouble()
        if (pendingG > 0 && now - pendingG < GO_WINDOW_MS) {
            pendingG = 0.0
            pageForKey(e.key)?.let {
                e.preventDefault()
                go("/$it")
            }
        } else if (e.key == "g") {
            pendingG = now
        } else if (e.key == "?") {
            e.preventDefault()
            help()
        }
    }

    private fun entries() = filterGroups(paletteGroups(snapshot()), query).flatMap { it.second }

    private fun paletteKey(e: KeyboardEvent) {
        val n = entries().size
        when (e.key) {
            "Escape" -> close()
            "ArrowDown" -> if (n > 0) focus = (focus + 1) % n
            "ArrowUp" -> if (n > 0) focus = (focus - 1 + n) % n
            "Enter" -> entries().getOrNull(focus)?.let { pick(it.href) }
            else -> return
        }
        e.preventDefault()
        if (overlay != null) draw()
    }

    private fun pick(href: String) {
        close()
        go(href)
    }

    fun open() {
        query = ""
        focus = 0
        val el = document.createElement("div") as HTMLElement
        el.className = "overlay"
        el.addEventListener("click", { e -> if (e.target == el) close() })
        document.body?.appendChild(el)
        overlay = el
        draw()
    }

    fun close() {
        overlay?.remove()
        overlay = null
    }

    private fun draw() {
        val el = overlay ?: return
        val caret = (document.getElementById("palette-q") as? HTMLInputElement)?.selectionStart
        el.innerHTML = ""
        el.append {
            div("card palette") {
                attributes["role"] = "dialog"
                attributes["aria-label"] = "Command palette"
                input(classes = "search wide") {
                    attributes["id"] = "palette-q"
                    placeholder = "Jump to…"
                    attributes["aria-label"] = "Search pages, plugins, sessions and hosts"
                    value = query
                }
                val groups = filterGroups(paletteGroups(snapshot()), query)
                if (groups.isEmpty()) p("muted") { +"Nothing matches." }
                var i = 0
                groups.forEach { (title, es) ->
                    p("kicker") { +title }
                    ul("plain") {
                        es.forEach { e ->
                            val mine = i++
                            li(if (mine == focus) "item on" else "item") {
                                onClickFunction = { pick(e.href) }
                                span { +e.label }
                                if (e.hint.isNotEmpty()) span("muted small") { +" ${e.hint}" }
                            }
                        }
                    }
                }
            }
        }
        (document.getElementById("palette-q") as? HTMLInputElement)?.let { inp ->
            inp.addEventListener("input", {
                query = inp.value
                focus = 0
                draw()
            })
            inp.focus()
            val c = caret ?: query.length
            inp.setSelectionRange(c, c)
        }
    }

    private fun help() {
        open()
        val el = overlay ?: return
        el.innerHTML = ""
        el.append {
            div("card palette") {
                attributes["role"] = "dialog"
                attributes["aria-label"] = "Keyboard shortcuts"
                p("kicker") { +"Keyboard shortcuts" }
                ul("plain") {
                    li {
                        kbd { +"⌘K / Ctrl+K" }
                        +" command palette"
                    }
                    PAGES.forEach { pg ->
                        li {
                            kbd { +"g ${GO[pg.id]}" }
                            +" ${pg.id}"
                        }
                    }
                }
                button {
                    onClickFunction = { close() }
                    +"Close"
                }
            }
        }
    }
}
