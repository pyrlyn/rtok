package rtok.web.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphHealthTest {
    private val hm: (Double) -> String = { "t${it.toLong()}" }

    @Test
    fun levelsAndLabels() {
        assertEquals(listOf("good", "warn", "bad"), listOf(80.0, 50.0, 49.9).map(::levelOf))
        val s = Score("warn", 61.0, mapOf("freshness" to 1.0, "backend" to 0.5, "links" to 0.0), listOf("stale" to "reindex"))
        assertEquals(listOf("health 61", "freshness 100% · backend 50% · links 0%", "stale; fix: reindex"), healthLines(s))
        assertEquals("indexing", healthLabel(s.copy(level = "indexing")))
        assertEquals("fail", healthTone("missing"))
    }

    @Test
    fun alertsGroupByKindAndToastOnChange() {
        val b = GraphAlert("/b", "b", "missing", "root gone", 20.0)
        val c = GraphAlert("/c", "c", "missing", "root gone", 10.0)
        val d = GraphAlert("/d", "d", "backend_down", "no daemon", 5.0)
        val g = groupAlerts(listOf(d, b, c))
        assertEquals(listOf("missing", "backend_down"), g.map { it.kind })
        assertEquals("b, c missing since t10 (2 projects)", describeGroup(g[0], hm))
        assertEquals("d backend down since t5 (no daemon)", describeGroup(g[1], hm))
        assertEquals(
            listOf("fail" to "c missing since t10 (root gone)", "ok" to "d backend down: recovered"),
            alertToasts(listOf(b, d), listOf(b.copy(since = 99.0), c), hm),
        )
    }

    @Test
    fun drillStateRoundTripsAndRejectsJunk() {
        assertNull(parseDrill(mapOf("p" to listOf("x1"))))
        val s = parseDrill(mapOf("p" to listOf("7"), "x" to listOf("a.rs", "a.rs", ""), "fp" to listOf("a.rs"), "fn" to listOf("f"), "d" to listOf("9")))!!
        assertEquals(DrillState("7", listOf("a.rs"), DrillFocus("a.rs", "f"), DEPTH_DEFAULT), s)
        assertEquals(listOf("p" to "7", "x" to "a.rs", "fp" to "a.rs", "fn" to "f"), drillSearch(s))
        assertEquals(listOf("p" to "7", "x" to "a.rs", "fp" to "a.rs", "fn" to "f", "d" to "3"), drillSearch(s.copy(depth = 3)))
        assertEquals(DrillState("7", listOf("a.rs", "b.rs")), toggleFile(s, "b.rs"))
        assertEquals(DrillState("7"), toggleFile(s, "a.rs"))
        assertEquals(listOf("All projects", "rtok", "a.rs"), breadcrumb(s, "rtok").map { it.label })
        assertEquals(listOf("All projects", "rtok"), breadcrumb(DrillState("7"), "rtok").map { it.label })
    }

    @Test
    fun editorLinkEncodesSegments() {
        val enc: (String) -> String = { it.replace("#", "%23").replace(":", "%3A") }
        assertEquals("vscode://file/C:/a%23b/x.rs:12", editorLink("C:\\a#b", "x.rs", 12, enc))
        assertEquals("vscode://file/r/x.rs", editorLink("/r", "x.rs", 0, enc))
    }
}
