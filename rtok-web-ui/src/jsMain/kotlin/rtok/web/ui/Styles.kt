// The UI's stylesheet in kotlin-css (port of web/src/styles/app.css): brand tokens as custom properties,
// dark by default with a light theme on `[data-theme=light]` (theme-init.js sets it before first paint).
package rtok.web.ui

import kotlinx.browser.document
import kotlinx.css.CssBuilder

private val DARK =
    mapOf(
        "--bg" to "#06101A",
        "--surface" to "#0A1622",
        "--surface-2" to "#0F1E2C",
        "--border" to "#1A3348",
        "--fg" to "#E8F7FF",
        "--fg-muted" to "#8AA8BC",
        "--accent" to "#5CE1FF",
        "--ok" to "#3DDC97",
        "--warn" to "#FFB547",
        "--fail" to "#FF6B6B",
    )

private val LIGHT =
    DARK +
        mapOf(
            "--bg" to "#F4F8FB",
            "--surface" to "#FFFFFF",
            "--surface-2" to "#EAF1F6",
            "--border" to "#C5D3E0",
            "--fg" to "#0B1A24",
            "--fg-muted" to "#4F6679",
            "--accent" to "#0A84A8",
        )

private fun v(name: String) = "var(--$name)"

/** The whole stylesheet as text. */
fun stylesheet(): String =
    CssBuilder()
        .apply {
            rule(":root") { DARK.forEach { (k, value) -> put(k, value) } }
            rule("[data-theme=light]") { LIGHT.forEach { (k, value) -> put(k, value) } }
            rule("*") { put("box-sizing", "border-box") }
            rule("body") {
                put("margin", "0")
                put("background", v("bg"))
                put("color", v("fg"))
                put("font", "13px/1.5 'IBM Plex Mono', ui-monospace, SFMono-Regular, Menlo, monospace")
            }
            rule("a") {
                put("color", v("accent"))
                put("text-decoration", "none")
            }
            layout()
            widgets()
            fixStyles()
            taskStyles()
            controls()
            charts()
            grid()
            text()
            tables()
        }.toString()

private fun CssBuilder.layout() {
    rule(".layout") {
        put("display", "grid")
        put("grid-template-columns", "220px minmax(0,1fr)")
        put("min-height", "100vh")
    }
    rule(".sidebar") {
        put("border-right", "1px solid ${v("border")}")
        put("padding", "16px 8px")
        put("background", v("surface"))
    }
    rule(".brand") {
        put("font-weight", "700")
        put("letter-spacing", ".2em")
        put("padding", "0 8px 12px")
    }
    rule(".nav a") {
        put("display", "block")
        put("padding", "4px 8px")
        put("border-radius", "6px")
        put("color", v("fg-muted"))
    }
    rule(".nav a[aria-current=page]") {
        put("background", v("surface-2"))
        put("color", v("fg"))
    }
    rule("header") {
        put("display", "flex")
        put("align-items", "center")
        put("gap", "12px")
        put("padding", "16px 12px 12px")
    }
    rule("h1") {
        put("flex", "1")
        put("margin", "0")
        put("font-size", "20px")
    }
    rule("main") {
        put("display", "flex")
        put("flex-direction", "column")
        put("gap", "12px")
        put("padding", "4px 12px 24px")
    }
}

private fun CssBuilder.charts() {
    rule(".chart") {
        put("display", "block")
        put("width", "100%")
        put("height", "auto")
    }
    rule(".kpi .chart") { put("height", "32px") }
    rule(".track, .mix") {
        put("display", "flex")
        put("height", "8px")
        put("border-radius", "999px")
        put("overflow", "hidden")
        put("background", v("surface-2"))
        put("min-width", "60px")
    }
    rule(".legend") {
        put("display", "flex")
        put("flex-wrap", "wrap")
        put("justify-content", "space-between")
        put("gap", "4px 12px")
        put("font-size", "11px")
        put("color", v("fg-muted"))
        put("margin-top", "6px")
    }
    rule(".legend i, .dot") {
        put("display", "inline-block")
        put("width", "8px")
        put("height", "8px")
        put("border-radius", "2px")
        put("margin-right", "4px")
    }
}

private fun CssBuilder.grid() {
    rule(".stack") {
        put("display", "flex")
        put("flex-direction", "column")
        put("gap", "12px")
    }
    rule(".grid") {
        put("display", "grid")
        put("gap", "12px")
    }
    rule(".kpis") { put("grid-template-columns", "repeat(auto-fill, minmax(170px, 1fr))") }
    rule(".wide") { put("grid-template-columns", "repeat(12, minmax(0, 1fr))") }
    "4 5 7 8 12".split(' ').forEach { n -> rule(".span$n") { put("grid-column", "span $n") } }
    rule("@media (max-width: 1279px)") { rule(".wide > *") { put("grid-column", "span 12") } }
    rule(".panel-head") {
        put("display", "flex")
        put("align-items", "baseline")
        put("gap", "8px")
        put("margin-bottom", "8px")
    }
    rule(".panel-head h2") {
        put("font-size", "13px")
        put("margin", "0")
    }
    rule(".panel-head .go") { put("margin-left", "auto") }
}

private fun CssBuilder.text() {
    rule(".kicker") {
        put("font-size", "10px")
        put("letter-spacing", ".12em")
        put("text-transform", "uppercase")
        put("color", v("fg-muted"))
        put("margin", "0")
    }
    rule(".kpi .value, .big") {
        put("font-size", "22px")
        put("font-weight", "600")
        put("margin", "2px 0")
    }
    rule(".big") { put("font-size", "28px") }
    rule(".accent, .delta") { put("color", v("accent")) }
    rule(".small") {
        put("font-size", "11px")
        put("margin", "0")
    }
    rule(".saved") {
        put("display", "flex")
        put("flex-wrap", "wrap")
        put("gap", "16px 32px")
        put("align-items", "center")
    }
    rule(".budget") {
        put("flex", "1")
        put("min-width", "180px")
        put("max-width", "320px")
        put("margin-left", "auto")
    }
}

private fun CssBuilder.tables() {
    rule(".scroll") {
        put("overflow", "auto")
        put("max-height", "360px")
    }
    rule("table") {
        put("width", "100%")
        put("border-collapse", "collapse")
    }
    rule("th, td") {
        put("text-align", "left")
        put("padding", "4px 6px")
        put("border-bottom", "1px solid ${v("border")}")
        put("white-space", "nowrap")
    }
    rule("th") {
        put("font-size", "10px")
        put("text-transform", "uppercase")
        put("color", v("fg-muted"))
        put("position", "sticky")
        put("top", "0")
        put("background", v("surface"))
    }
    rule(".r") {
        put("text-align", "right")
        put("font-variant-numeric", "tabular-nums")
    }
    rule(".check") { put("padding", "2px 0") }
    rule(".toolbar") {
        put("display", "flex")
        put("flex-wrap", "wrap")
        put("align-items", "center")
        put("gap", "8px")
        put("margin-bottom", "8px")
    }
    rule(".toggles button") { put("border-radius", "0") }
    rule(".toggles button.on") {
        put("background", v("accent"))
        put("color", v("bg"))
    }
    rule(".search") {
        put("font", "inherit")
        put("color", v("fg"))
        put("background", v("bg"))
        put("border", "1px solid ${v("border")}")
        put("border-radius", "6px")
        put("padding", "2px 8px")
        put("min-width", "200px")
    }
    rule("td .ok") { put("color", v("ok")) }
    rule("td .wrap, td:last-child") { put("white-space", "normal") }
}

/** Split list/detail layouts, link buttons and switches. */
private fun CssBuilder.controls() {
    rule(".split") {
        put("display", "grid")
        put("grid-template-columns", "minmax(0,3fr) minmax(0,2fr)")
        put("gap", "12px")
        put("align-items", "start")
    }
    rule("button.link") {
        put("background", "none")
        put("border", "none")
        put("padding", "0")
        put("text-align", "left")
    }
    rule(".overlay") {
        put("position", "fixed")
        put("inset", "0")
        put("z-index", "50")
        put("display", "flex")
        put("justify-content", "center")
        put("align-items", "flex-start")
        put("padding-top", "12vh")
        put("background", "rgba(0,0,0,0.45)")
    }
    rule(".palette") {
        put("width", "min(32rem, 92vw)")
        put("max-height", "70vh")
        put("overflow-y", "auto")
    }
    rule(".search.wide") { put("width", "100%") }
    rule(".palette .item") {
        put("padding", "4px 8px")
        put("border-radius", "6px")
        put("cursor", "pointer")
    }
    rule(".palette .item.on") { put("background", v("surface-2")) }
    rule("button.switch") {
        put("min-width", "40px")
        put("border-radius", "999px")
        put("font-size", "11px")
    }
    rule("button.switch.on") {
        put("background", v("accent"))
        put("color", v("bg"))
    }
}

private fun CssBuilder.widgets() {
    rule(".card") {
        put("background", v("surface"))
        put("border", "1px solid ${v("border")}")
        put("border-radius", "10px")
        put("padding", "12px")
    }
    rule(".pill") {
        put("border", "1px solid ${v("border")}")
        put("border-radius", "999px")
        put("padding", "0 8px")
        put("font-size", "11px")
    }
    rule(".pill.ok") { put("color", v("ok")) }
    rule(".pill.info") { put("color", v("accent")) }
    rule(".pill.warn, .warn-fg") { put("color", v("warn")) }
    rule(".pill.fail") { put("color", v("fail")) }
    rule(".pill.muted") { put("color", v("fg-muted")) }
    rule(".kv") {
        put("display", "grid")
        put("grid-template-columns", "minmax(0,16rem) minmax(0,1fr)")
        put("gap", "4px 16px")
        put("word-break", "break-all")
    }
    rule(".cards") { put("grid-template-columns", "repeat(auto-fill, minmax(320px, 1fr))") }
    rule("ul.plain") {
        put("list-style", "none")
        put("padding", "0")
    }
    rule(".muted") { put("color", v("fg-muted")) }
    rule("button") {
        put("font", "inherit")
        put("color", v("fg"))
        put("background", v("surface-2"))
        put("border", "1px solid ${v("border")}")
        put("border-radius", "6px")
        put("padding", "2px 10px")
        put("cursor", "pointer")
    }
    rule("pre") {
        put("margin", "0")
        put("white-space", "pre-wrap")
        put("word-break", "break-word")
    }
    rule(".center") {
        put("display", "grid")
        put("place-items", "center")
        put("min-height", "100vh")
        put("text-align", "center")
    }
    rule("@media (max-width: 767px)") {
        rule(".layout, .split") { put("grid-template-columns", "1fr") }
        rule(".sidebar") { put("border-right", "none") }
    }
}

/** Puts the stylesheet into `<head>` once. */
fun installStyles() {
    val style = document.createElement("style")
    style.textContent = stylesheet()
    document.head?.appendChild(style)
}

private fun CssBuilder.fixStyles() {
    // DoctorFix.tsx: the checklist rows and the diff's added and removed lines.
    rule(".fix-list") {
        put("list-style", "none")
        put("padding", "0")
    }
    rule(".diff") {
        put("max-height", "20rem")
        put("overflow", "auto")
    }
    rule(".diff .ok") { put("color", v("ok")) }
    rule(".diff .fail") { put("color", v("fail")) }
    // Doctor.tsx: the hooks bars and the agents × modules grid.
    rule(".bar-row") {
        put("display", "grid")
        put("grid-template-columns", "9rem 1fr 2rem")
        put("gap", "0.5rem")
        put("align-items", "center")
    }
    rule(".bar") {
        put("height", "6px")
        put("border-radius", "999px")
        put("background", v("surface-2"))
    }
    rule(".bar .fill") {
        put("height", "100%")
        put("border-radius", "999px")
        put("background", v("accent"))
    }
    rule(".modules") { put("grid-template-columns", "repeat(auto-fill, minmax(8rem, 1fr))") }
}
