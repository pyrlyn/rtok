// Shared page parts (web/src/pages/parts.tsx, ui/DataTable.tsx, states.tsx) in kotlinx.html.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/** A titled card with an optional hint and a link to its page (parts.tsx `Panel`). */
fun FlowContent.panel(
    title: String,
    hint: String? = null,
    link: String? = null,
    cls: String = "",
    body: FlowContent.() -> Unit,
) {
    section("card panel $cls") {
        attributes["aria-label"] = title
        div("panel-head") {
            h2 { +title }
            hint?.let { span("muted") { +it } }
            link?.let { a("/$it", classes = "go") { +"$it →" } }
        }
        body()
    }
}

/** A KPI tile: label, value, sub line and an optional small chart (Kpi.tsx). */
fun FlowContent.kpi(
    label: String,
    value: String,
    sub: String,
    viz: (FlowContent.() -> Unit)? = null,
) {
    div("card kpi") {
        p("kicker") { +label }
        p("value") { +value }
        p("muted small") { +sub }
        viz?.let { div("viz") { it() } }
    }
}

/** A tone pill (`ok`, `info`, `warn`, `fail`). */
fun FlowContent.pill(
    tone: String,
    text: String,
) = span("pill $tone") { +text }

/** The pill tone for a check state. */
fun toneOf(st: String): String =
    when (st) {
        "pass" -> "ok"
        "warn" -> "warn"
        "fail" -> "fail"
        else -> "info"
    }

/** One column of [dataTable]: header, alignment and the cell. */
class Column<T>(
    val header: String,
    val right: Boolean = false,
    val cell: FlowContent.(T) -> Unit,
)

/** A plain table over [rows]; [empty] shows when there are none (DataTable.tsx without virtualisation). */
fun <T> FlowContent.dataTable(
    label: String,
    rows: List<T>,
    columns: List<Column<T>>,
    empty: String,
) {
    if (rows.isEmpty()) {
        p("muted") { +empty }
        return
    }
    div("scroll") {
        table {
            attributes["aria-label"] = label
            thead { tr { columns.forEach { c -> th(classes = if (c.right) "r" else null) { +c.header } } } }
            tbody {
                rows.forEach { r ->
                    tr { columns.forEach { c -> td(classes = if (c.right) "r" else null) { c.cell(this, r) } } }
                }
            }
        }
    }
}
