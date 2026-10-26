package rtok.plugins.graph

import kotlin.test.*

class DrawTest {
    private fun node(
        project: Int,
        name: String,
        kind: String,
        line: Int,
    ) = ExportNode("$project:lib.rs:$line:$name", project, kind, name, "lib.rs", line)

    private fun export(
        nodes: List<ExportNode>,
        edges: List<ExportEdge>,
    ): Export {
        fun proj(
            id: Int,
            name: String,
        ) = ExportProj(id, name, "~/x", "manual", "tags", "ok", 1_700_000_000)
        return Export(
            ExportView.SCHEMA,
            listOf(proj(1, "alpha"), proj(2, "beta")),
            listOf(ExportLink(1, 2, "manual", null, 3)),
            nodes,
            edges,
            ExportMeta(listOf("alpha", "beta"), "symbols", null, null, 1_700_000_100, "9.9.9", redacted = true, partial = true, notes = emptyList()),
        )
    }

    private fun edge(
        a: ExportNode,
        b: ExportNode,
    ) = ExportEdge(a.id, b.id, "call")

    private fun small(): Export {
        val a = node(1, "a_fn", "function", 1)
        val b = node(2, "B", "struct", 2)
        val c = node(2, "m", "module", 3)
        return export(listOf(a, b, c), listOf(edge(a, b), edge(b, c)))
    }

    /** Every opened element is closed: a cheap well-formedness check without an XML parser. */
    private fun balanced(s: String) {
        val open = Regex("<(svg|defs|marker|text)[ >]").findAll(s).count()
        val close = Regex("</(svg|defs|marker|text)>").findAll(s).count()
        assertEquals(open, close, s)
        assertFalse(Regex("&(?!amp;|lt;|gt;|quot;)").containsMatchIn(s), "bare ampersand")
    }

    @Test fun svgIsWellFormedWithLegendAndFooter() {
        val s = Draw.svg(small(), false)
        balanced(s)
        for (want in listOf(
            "alpha",
            "beta",
            "function or method",
            "call across projects",
            "rtok 9.9.9",
            "2023-11-14 22:15:00 UTC",
            "scope: alpha, beta",
            "backend tags, ok, indexed 2023-11-14 22:13:20 UTC",
            "PARTIAL",
            "paths redacted",
            "manual \u00b7 3 calls",
        )) {
            assertTrue(s.contains(want), "missing $want in\n$s")
        }
        assertFalse(s.contains("nodes hidden"))
        assertEquals(s, Draw.svg(small(), false), "the picture is deterministic")
    }

    @Test fun namesAreEscaped() {
        val e0 = small()
        val name = "a<b>&\"c\u0001"
        val e = e0.copy(nodes = listOf(e0.nodes[0].copy(name = name)) + e0.nodes.drop(1), meta = e0.meta.copy(focus = name))
        val s = Draw.svg(e, false)
        balanced(s)
        assertTrue(s.contains("a&lt;b&gt;&amp;&quot;c?"))
    }

    @Test fun moreThanTheCapDrawsTheCapAndSaysHowManyAreHidden() {
        val nodes = (0 until Draw.MAX_DRAWN + 25).map { node(1, "n$it", "function", it) }
        val s = Draw.svg(export(nodes, listOf(edge(nodes[Draw.MAX_DRAWN + 24], nodes[0]))), false)
        assertEquals(Draw.MAX_DRAWN, Regex("<circle").findAll(s).count() - 1, "the legend sample is one more")
        assertTrue(s.contains("25 nodes hidden"))
        // The best connected node survives the cap although it is the last one in file order.
        assertTrue(s.contains(">n224<"))
    }

    @Test fun transparentHasNoBackground() {
        assertTrue(Draw.svg(small(), false).contains("width=\"100%\" height=\"100%\" fill=\"#ffffff\""))
        assertFalse(Draw.svg(small(), true).contains("width=\"100%\""))
    }

    @Test fun overviewWithoutNodesDrawsProjectPanelsAndLinks() {
        val s = Draw.svg(small().copy(nodes = emptyList(), edges = emptyList()), false)
        assertEquals(2, Regex("rx=\"10\"").findAll(s).count())
        assertTrue(s.contains("marker-end=\"url(#arrow)\""))
    }

    @Test fun numbersAreFixedPoint() {
        assertEquals("1.5", Draw.fixed(1.46))
        assertEquals("-0.3", Draw.fixed(-0.3))
        assertEquals("12000000.0", Draw.fixed(1.2e7))
        assertEquals("901", Draw.fixed(900.6, 0))
    }
}
