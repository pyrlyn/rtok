// Pages whose backend is not ported yet (docs/web.md step 4): the server sends null (or nothing) for their
// field, which is shown as "not ready" with the CLI command that has the data, never as a failure.
package rtok.web.ui

import kotlinx.html.code
import kotlinx.html.p
import kotlinx.serialization.json.JsonNull

/** A page that renders [ready] once its field arrives, and the not-ready note until then. */
fun pendingPage(
    field: String,
    command: String,
    ready: PageView? = null,
) = PageView { out, snap, app ->
    val value = snap[field]
    if (value == null || value is JsonNull || ready == null) {
        out.panel(field, "not ready") {
            p("muted") {
                +"This page is not ported to the Kotlin build yet: the server sends no $field data. "
                code { +command }
                +" has it on the command line."
            }
        }
    } else {
        ready.render(out, snap, app)
    }
}
