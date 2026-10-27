package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DrillModelTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    private val g =
        parseDrillGraph(
            obj(
                """{"project":1,"name":"rtok","root":"/r","state":"ok","nodes":[
                {"id":"f:a.rs","kind":"file","label":"a.rs","path":"a.rs","line":0,"project":1,"signature":"","weight":3,"stale":false},
                {"id":"s:a.rs:3:run","kind":"function","label":"run","path":"a.rs","line":3,"project":1,"signature":"","weight":1,"stale":false},
                {"id":"s:a.rs:9:go","kind":"function","label":"go","path":"a.rs","line":9,"project":1,"signature":"","weight":1,"stale":false}],
                "edges":[{"from":"s:a.rs:3:run","to":"s:a.rs:9:go","kind":"calls","count":2}],"more":0,"partial":false,"hits":[]}""",
            ),
        )!!

    @Test
    fun parsesTheGraphFrame() {
        assertEquals(3, g.nodes.size)
        assertEquals(DEdge("s:a.rs:3:run", "s:a.rs:9:go", "calls", 2), g.edges.single())
    }

    @Test
    fun marksChangesGhostsAndEdges() {
        val d =
            obj(
                """{"project":"rtok","from":"HEAD","changed":[{"name":"run","kind":"function","path":"a.rs","line":3}],
                "added":[{"name":"go","kind":"function","path":"a.rs","line":9}],
                "removed":[{"name":"old","kind":"struct","path":"a.rs","line":20}],"renamed":[],"moved":[],
                "edges_added":[{"path":"a.rs","scope":"run","name":"go"}],
                "edges_removed":[{"path":"a.rs","scope":"run","name":"old"}],"not_analysed":[]}""",
            )
        val m = markGraph(g, d, listOf("a.rs"))
        assertEquals("changed", m.nodes["f:a.rs"])
        assertEquals("changed", m.nodes["s:a.rs:3:run"])
        assertEquals("added", m.nodes["s:a.rs:9:go"])
        assertEquals("removed", m.nodes["d:a.rs:20:old"])
        assertEquals(
            "type",
            m.graph.nodes
                .last()
                .kind,
        )
        assertEquals("added", m.edges["calls:s:a.rs:3:run>s:a.rs:9:go"])
        assertEquals("removed", m.edges["calls:s:a.rs:3:run>d:a.rs:20:old"])
        assertEquals(emptyMap(), markGraph(g, null, emptyList()).nodes)
        assertEquals(3, markGraph(g, d, emptyList()).graph.nodes.size)
    }

    @Test
    fun requestsMatchTheServerShapes() {
        val s = DrillState("1", listOf("a.rs"), DrillFocus("a.rs", "run"), 2)
        assertEquals(
            """{"graph":{"project":"1","expand":["a.rs"],"focus":{"path":"a.rs","name":"run"},"depth":2,"limit":400,"query":""}}""",
            drillRequest(s, 400),
        )
        assertTrue(""""focus":null,"depth":null""" in drillRequest(DrillState("1"), 400))
        assertEquals("""{"diff":{"project":"1","from":["HEAD~1"]}}""", diffRequest("1", listOf("HEAD~1")))
        assertEquals("""{"export":{"project":"1","level":"overview","format":"svg"}}""", exportRequest("1", "overview", "svg"))
        assertEquals("""{"import":{"name":"g.json","text":"{}"}}""", importRequest("g.json", "{}"))
    }

    @Test
    fun layoutIsDeterministicAndInside() {
        val ids = listOf("a", "b", "c", "d")
        val l = forceLayout(ids, listOf("a" to "b", "b" to "c"))
        assertEquals(l, forceLayout(ids, listOf("a" to "b", "b" to "c")))
        assertTrue(l.values.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
        assertEquals(mapOf("x" to LayoutPoint(0.5, 0.5)), forceLayout(listOf("x"), emptyList()))
    }
}
