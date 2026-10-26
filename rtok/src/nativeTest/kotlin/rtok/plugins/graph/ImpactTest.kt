package rtok.plugins.graph

import kotlin.test.*
import rtok.config.Config
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class ImpactTest {
    private fun project(tag: String, cfg: (Config) -> Config = { it }, body: (Ctx, String) -> Unit) = withRuntime(tag, cfg) { rt ->
        val dir = tmpDir(tag)
        try { body(Ctx(rt), dir) } finally { removeDirAll(dir) }
    }

    private val chain = "fn a() {\n    b();\n}\nfn b() {\n    c();\n}\nfn c() {}\n"

    @Test fun impactWalksTheCallChainAndTerminates() = project("impact") { cx, dir ->
        writeString("$dir/chain.rs", "fn a() {\n    b();\n}\nfn b() {\n    c();\n}\nfn x() {\n    y();\n    c();\n}\nfn y() {\n    x();\n}\n")
        fun at(out: String, n: String) = out.lines().find { it.endsWith("  $n") }?.take(1)
        val two = Impact.impact(cx, dir, "c", 2)
        assertEquals("1", at(two, "b"), two); assertEquals("2", at(two, "a"), two)
        val one = Impact.impact(cx, dir, "c", 1)
        assertEquals("1", at(one, "b"), one); assertNull(at(one, "a"), one)
        val deep = Impact.impact(cx, dir, "c", 4)
        assertEquals(1, deep.lines().count { it.endsWith("  x") }, deep)
        assertEquals("nothing reaches no_such_fn", Impact.impact(cx, dir, "no_such_fn", 2))
    }

    @Test fun impactPathFilterReportsMatchingLines() = project("filter-impact") { cx, dir ->
        writeString("$dir/chain.rs", chain)
        writeString("$dir/other.rs", "fn d() {\n    c();\n    c();\n}\n")
        assertEquals("1  other.rs  d\n", Impact.impactFiltered(cx, dir, "c", 2, Filter(path = "other")))
        assertEquals("nothing reaches c in zzz", Impact.impactFiltered(cx, dir, "c", 2, Filter(path = "zzz")))
    }

    @Test fun impactToFiltersCallChains() = project("impact-to") { cx, dir ->
        writeString("$dir/chain.rs", chain)
        assertEquals("a → b → c\n", Impact.impact(cx, dir, "a", 3, "c"))
        assertEquals("a → b\n", Impact.impact(cx, dir, "a", 3, "b"))
        assertEquals("no path from b to a within depth 3", Impact.impact(cx, dir, "b", 3, "a"))
        assertEquals("no path from a to c within depth 1", Impact.impact(cx, dir, "a", 1, "c"))
    }

    @Test fun impactOnAnIndexedHubIsBudgetedAndAllLiftsIt() = project("t377-e2e", { c -> c.copy(plugins = c.plugins.copy(graph = c.plugins.graph.copy(impactTokens = 200u))) }) { cx, dir ->
        writeString("$dir/hub.rs", "pub fn hub() {}\n")
        for (f in 0 until 60) writeString("$dir/c${f.toString().padStart(2, '0')}.rs", (0 until 4).joinToString("") { "pub fn caller_${f}_$it() {\n    hub();\n}\n" })
        val out = Impact.impactFiltered(cx, dir, "hub", 1, Filter.None)
        assertTrue(cx.estimate(out, Class.Code) <= 200u, out)
        assertTrue(out.contains("c00.rs (4 refs, depth 1)\n"), out)
        assertTrue(out.contains(" refs not shown — impact hub --all\n"), out)
        val full = Impact.impactFiltered(cx, dir, "hub", 1, Filter(all = true))
        assertEquals(240, Regex("caller_").findAll(full).count(), full)
        assertFalse(full.contains("not shown"), full)
    }
}
