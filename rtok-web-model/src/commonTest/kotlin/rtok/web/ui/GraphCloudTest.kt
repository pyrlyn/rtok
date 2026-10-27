package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphCloudTest {
    private val rows =
        Json
            .parseToJsonElement(
                """[{"id":1,"name":"a","health":{"score":90},"index":{"files":40}},
                   {"id":2,"name":"b","missing":true,"index":{"files":3}},{"name":"no id"}]""",
            ).jsonArray
            .map { it.jsonObject }

    @Test
    fun oneClusterPerProjectWithHealthToneAndFileCount() {
        val c = cloudClusters(rows, emptyMap())
        assertEquals(listOf("1", "2"), c.map { it.id })
        assertEquals(listOf("ok", "fail"), c.map { it.tone })
        assertEquals(listOf(40, 3), c.map { it.count })
        assertEquals(emptyList(), c[0].nodes)
    }

    @Test
    fun aLoadedDrillGraphCarriesNodesEdgesAndChangeMarks() {
        val g =
            DGraph(
                1,
                "a",
                "/a",
                "ok",
                listOf(DNode("f", "file", "f.kt", "f.kt", 0, 1), DNode("s", "symbol", "s", "f.kt", 3, 1, stale = true)),
                listOf(DEdge("f", "s", "contains")),
            )
        val m = Marked(g, mapOf("s" to "added"), mapOf("contains:f>s" to "changed"))
        val c = cloudClusters(rows, mapOf("1" to m)).first()
        assertEquals(listOf(CloudNode("f", "f.kt", "accent", true, false), CloudNode("s", "s", "ok", false, true)), c.nodes)
        assertEquals(listOf(CloudEdge("f", "s", "warn", true)), c.edges)
    }
}
