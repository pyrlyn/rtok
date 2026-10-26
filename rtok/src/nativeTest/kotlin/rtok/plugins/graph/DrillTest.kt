package rtok.plugins.graph

import kotlin.test.*
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.LinkKind
import rtok.store.Origin
import rtok.store.linkProjects
import rtok.store.registerProject
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class DrillTest {
    /** A registered, indexed project at `dir/name` with the given files. */
    private fun project(rt: Runtime, dir: String, name: String, files: List<Pair<String, String>>): Int {
        val root = "$dir/$name"; createDirAll(root)
        files.forEach { (f, body) -> writeString("$root/$f", body) }
        Index.run(Ctx(rt), root, false)
        return rt.store.registerProject(root, Origin.Manual).id
    }
    private fun at(tag: String, body: (Runtime, String) -> Unit) = withRuntime(tag) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try { body(rt, dir) } finally { removeDirAll(dir) }
    }
    private fun req(id: Int) = DrillRequest(project = id.toString())
    private fun edge(g: DrillGraph, from: String, to: String, kind: DrillEdgeKind) = g.edges.firstOrNull { it.from == from && it.to == to && it.kind == kind }?.count

    private val calls = listOf("a.rs" to "pub fn alpha() {\n    beta();\n    beta();\n}\n", "b.rs" to "pub fn beta() {\n    gamma();\n}\npub fn gamma() {}\n")

    @Test fun openingAProjectShowsFilesWithAggregatedEdges() = at("t329-14-files") { rt, dir ->
        val g = Drill.run(rt, req(project(rt, dir, "a", calls)))
        assertEquals("ok", g.state); assertTrue(g.nodes.all { it.kind == DrillNodeKind.File })
        assertEquals(2L, edge(g, "f:a.rs", "f:b.rs", DrillEdgeKind.Calls)); assertEquals(0, g.more)
    }

    @Test fun expandingAFileShowsItsFunctionsInsideIt() = at("t329-14-expand") { rt, dir ->
        val g = Drill.run(rt, req(project(rt, dir, "a", calls)).copy(expand = listOf("b.rs")))
        val beta = g.nodes.first { it.label == "beta" }
        assertEquals(DrillNodeKind.Function, beta.kind)
        assertEquals(1L, edge(g, "f:b.rs", beta.id, DrillEdgeKind.Contains))
        assertEquals(2L, edge(g, "f:a.rs", beta.id, DrillEdgeKind.Calls))
        val gamma = g.nodes.first { it.label == "gamma" }
        assertEquals(1L, edge(g, beta.id, gamma.id, DrillEdgeKind.Calls))
    }

    @Test fun aFocusShowsCallersAndCalleesToTheDepth() = at("t329-14-focus") { rt, dir ->
        val id = project(rt, dir, "a", listOf("c.rs" to "fn one() { two(); }\nfn two() { three(); }\nfn three() { four(); }\nfn four() {}\n"))
        val r = req(id).copy(focus = DrillFocus("c.rs", "two"))
        fun labels(g: DrillGraph) = g.nodes.filter { it.kind != DrillNodeKind.File }.map { it.label }.sorted()
        assertEquals(listOf("one", "three", "two"), labels(Drill.run(rt, r)))
        assertEquals(listOf("four", "one", "three", "two"), labels(Drill.run(rt, r.copy(depth = 2))))
    }

    @Test fun aCallIntoALinkedProjectEndsAtANodeOfThatProject() = at("t329-14-linked") { rt, dir ->
        val a = project(rt, dir, "a", listOf("m.rs" to "fn run() {\n    shared();\n}\n"))
        val c = project(rt, dir, "c", listOf("lib.rs" to "pub fn shared() {}\n"))
        assertTrue(Drill.run(rt, req(a)).nodes.none { it.kind == DrillNodeKind.External })
        rt.store.linkProjects(a, c, LinkKind.Manual, null)
        val g = Drill.run(rt, req(a))
        val x = g.nodes.first { it.kind == DrillNodeKind.External }
        assertEquals(Triple(c, "lib.rs", "shared"), Triple(x.project, x.path, x.label))
        assertEquals(1L, edge(g, "f:m.rs", x.id, DrillEdgeKind.Calls))
        val hit = Drill.run(rt, req(a).copy(query = "shared")).hits[0]
        assertEquals(c to "lib.rs", hit.project to hit.path)
    }

    @Test fun nodesBeyondTheCapAreCountedNotDrawn() = at("t329-14-cap") { rt, dir ->
        val id = project(rt, dir, "a", (0 until 120).map { "f${it.toString().padStart(3, '0')}.rs" to "fn f$it() {}\n" })
        Drill.run(rt, req(id).copy(limit = 100)).let { assertEquals(100 to 20, it.nodes.size to it.more) }
        Drill.run(rt, req(id).copy(limit = 500)).let { assertEquals(120 to 0, it.nodes.size to it.more) }
    }

    @Test fun anUnindexedOrMissingProjectSaysSo() = at("t329-14-empty") { rt, dir ->
        createDirAll("$dir/empty")
        val id = rt.store.registerProject("$dir/empty", Origin.Manual).id
        assertEquals("not indexed", Drill.run(rt, req(id)).state)
        removeDirAll("$dir/empty")
        assertEquals("missing", Drill.run(rt, req(id)).state)
    }
}
