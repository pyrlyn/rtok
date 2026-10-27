// Port of the filter and expand state of src/tui/app.rs: the Sessions live-only filter (`l`, T60.10), the Config
// page's `/` filter (T228) and the Calls expand pane with its own `/` filter (T60.4). An open `/` filter owns
// every printable key, `?`, `r` and `q` included (T637).
package rtok.tui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import rtok.Expand
import tui.Key

/** A `/` filter: `/` starts capturing, Esc/Enter stop, Backspace edits. */
class LineFilter {
    var text = ""
        private set
    var typing = false
        private set

    /** Whether [k] was taken: while typing every key is, otherwise only `/`. */
    fun key(k: Key): Boolean {
        if (!typing) {
            if (k == Key.Char("/")) typing = true
            return typing
        }
        when (k) {
            Key.Esc, Key.Enter -> typing = false
            Key.Backspace -> text = text.dropLast(1)
            is Key.Char -> text += k.c
            else -> Unit
        }
        return true
    }

    /** view.rs `config_page` / app.rs `calls_expand`: grep hits as `N:line`; the text unchanged when empty. */
    fun apply(body: String): String = if (text.isEmpty()) body else runCatching { Expand.filterLines(body, null, text, 0).joinToString("\n") }.getOrDefault(body)
}

/** The Calls page's expand pane: the archive [id], its `[expand] max_lines`-capped [body], a filter and a scroll. */
class ExpandPane(
    val id: String,
    val body: String,
) {
    val filter = LineFilter()
    var scroll = 0
        private set

    /** app.rs `calls_key` with a pane open; `false` lets the page close it or move on. */
    fun key(k: Key): Boolean =
        when {
            filter.key(k) -> true
            k == Key.Up -> true.also { scroll = (scroll - 1).coerceAtLeast(0) }
            k == Key.Down -> true.also { scroll++ }
            else -> false
        }

    fun text(): String = filter.apply(body)
}

/** The Sessions tab's visible rows: all, or the live ones while `l` is on (app.rs `visible_sessions`). */
fun TuiApp.sessionRows(): List<JsonObject> =
    (snapshot["sessions"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter {
        !liveOnly || it["ended_at"] == null || it["ended_at"] is JsonNull
    }

/** The Skills tab's visible rows: all, or the never-invoked ones while `n` is on (app.rs `visible_skills`). */
fun TuiApp.skillRows(): List<JsonObject> =
    ((snapshot["skills"] as? JsonObject)?.get("rows") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter {
        !neverOnly || (it["never"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    }
