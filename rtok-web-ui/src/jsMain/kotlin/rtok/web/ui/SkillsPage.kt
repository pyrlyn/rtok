// Port of web/src/pages/Skills.tsx: the `skills` Snapshot field (the host's skill listing joined with transcript
// invocations) as a filterable table with a detail panel. The filter is kept in `?q=`, the never-invoked switch in
// `?never=` and the selected skill in `?sel=`.
package rtok.web.ui

import kotlinx.html.FlowContent
import kotlinx.html.b
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.js.onClickFunction
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.serialization.json.JsonObject

private const val TOKEN_CHARS = 4

private fun FlowContent.detail(r: SkillRow) {
    panel(r.name, r.source) {
        div("task-marks") {
            val warnings = skillWarnings(r)
            if (warnings.isEmpty()) pill("ok", "ok") else warnings.forEach { pill(if (it == "never invoked") "muted" else "warn", it) }
        }
        kv(
            listOf(
                "source" to { +r.source },
                "desc chars" to {
                    +"${fmt(r.descChars.toDouble())} "
                    span("muted") { +"≈ ${fmt((r.descChars / TOKEN_CHARS.toDouble()).let { kotlin.math.round(it) })} tok per request" }
                },
                "body bytes" to { +fmt(r.bodyBytes.toDouble()) },
                "invocations" to { +fmt(r.invocations.toDouble()) },
                "resident" to { +fmt(r.resident.toDouble()) },
                "last invoked" to { +r.lastInvoked },
            ),
        )
        p("muted small") { +"tokens per request use chars / 4, the estimate research.md §10.2 documents" }
    }
}

/** The Skills page. */
val SkillsPage =
    PageView { out, snap, app ->
        val page = snap["skills"] as? JsonObject
        if (page == null) {
            pendingPage("skills", "rtok skills list").render(out, snap, app)
            return@PageView
        }
        val header = page.str("header").orEmpty()
        val all = page.objects("rows").map(::skillRow)
        val neverOnly = param("never", "all") == "never invoked only"
        val rows = all.filter { matchesSkill(it, neverOnly, param("q", "")) }
        val selected = all.firstOrNull { it.name == param("sel", "") } ?: all.firstOrNull()
        out.div("toolbar") {
            searchBox(app, "skills-q", "name or source")
            toggles(app, "never", listOf("all", "never invoked only"))
            span("muted small") { +"${rows.size} of ${all.size}" }
        }
        if (all.isEmpty()) {
            out.panel("skills") { p("muted") { +"No skills listed. The host lists no skills for this session yet." } }
            return@PageView
        }
        if (header.isNotEmpty()) out.p("muted small") { +header }
        out.div("split") {
            panel("skills", "listed by the host") {
                dataTable(
                    "skills",
                    rows,
                    listOf(
                        Column("skill") { r ->
                            button(classes = "link") {
                                onClickFunction = { setParam(app, "sel", r.name, "") }
                                b(if (r.never) "muted" else null) { +r.name }
                            }
                            span("muted") { +" ${r.source}" }
                        },
                        Column("desc", right = true) { r -> span(if (r.descChars > LONG_DESC_CHARS) "warn-fg" else null) { +fmt(r.descChars.toDouble()) } },
                        Column("body", right = true) { r ->
                            span(if (r.bodyBytes > LONG_BODY_BYTES) "warn-fg" else null) { +"${compact(r.bodyBytes.toDouble())} B" }
                        },
                        Column("calls", right = true) { r -> +fmt(r.invocations.toDouble()) },
                    ),
                    "No skill matches. Clear the filter to see every skill.",
                )
            }
            selected?.let { detail(it) }
        }
    }
