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
    rule(".pill.warn") { put("color", v("warn")) }
    rule(".pill.fail") { put("color", v("fail")) }
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
        rule(".layout") { put("grid-template-columns", "1fr") }
        rule(".sidebar") { put("border-right", "none") }
    }
}

/** Puts the stylesheet into `<head>` once. */
fun installStyles() {
    val style = document.createElement("style")
    style.textContent = stylesheet()
    document.head?.appendChild(style)
}
