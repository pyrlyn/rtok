package rtok.plugins.graph

import kotlin.test.*
import rtok.config.Config
import rtok.plugin.sdk.Ctx
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class TextTest {
    private val text: (Config) -> Config = { c -> c.copy(plugins = c.plugins.copy(graph = c.plugins.graph.copy(backend = "text"))) }

    private fun pinned(tag: String, body: (Ctx, String) -> Unit) = withRuntime(tag, text) { rt ->
        val dir = tmpDir(tag)
        try { body(Ctx(rt), dir) } finally { removeDirAll(dir) }
    }

    private fun seed(dir: String) {
        writeString("$dir/a.zig", "const std = 1;\nfn alpha() void {\n    beta();\n}\nfn beta() void {}\n// beta is used\n")
        writeString("$dir/b.zig", "fn gamma() void { alpha(); alphabet(); }\n")
    }

    private fun counted(f: () -> String): Pair<String, Triple<Int, Int, Int>> {
        Tally.arm(); val answer = f(); val got = Tally.take()
        return answer to Triple(got.symbols, got.files, got.projects)
    }

    /** T329.36: the text backend counts the definition files and the mentioning files it lists. */
    @Test fun theTextBackendCountsWhatItLists() = pinned("t32936-text") { cx, dir ->
        seed(dir)
        assertEquals(Triple(1, 1, 1), counted { Text.symbol(cx, dir, "alpha", Filter.None) }.second, "defined in a.zig")
        val (answer, got) = counted { Text.callers(cx, dir, "alpha", Filter.None) }
        assertTrue(answer.startsWith("b.zig"), answer); assertEquals(Triple(1, 1, 1), got, "mentioned in b.zig only")
        assertEquals(Triple(1, 1, 1), counted { Text.impact(cx, dir, "beta", 1u, Filter.None, null) }.second)
        assertEquals(Triple(0, 0, 0), counted { Text.symbol(cx, dir, "alp", Filter.None) }.second)
    }

    @Test fun symbolFindsDefinitionsByWordNotByPrefix() = pinned("t32910-symbol") { cx, dir ->
        seed(dir)
        val out = Text.symbol(cx, dir, "alpha", Filter.None)
        assertTrue(out.startsWith("a.zig:2 function\nfn alpha() void {\n"), out)
        assertFalse(out.contains("alphabet"), out); assertTrue(out.endsWith(Text.CAVEAT), out)
        assertEquals("no definition of alp", Text.symbol(cx, dir, "alp", Filter.None))
    }

    @Test fun callersAndImpactListMentionsOutsideTheDefinition() = pinned("t32910-callers") { cx, dir ->
        seed(dir)
        val callers = Text.callers(cx, dir, "beta", Filter.None)
        assertTrue(callers.startsWith("a.zig ×2 (L3)\n"), callers)
        val impact = Text.impact(cx, dir, "alpha", 2u, Filter.None, null)
        assertTrue(impact.startsWith("1  b.zig  ×1\n"), impact); assertTrue(impact.contains("one level only"), impact)
        assertTrue(Text.impact(cx, dir, "alpha", 1u, Filter.None, "gamma").contains(Text.UNAVAILABLE))
    }

    @Test fun outlineListsDefinitionKeywords() = pinned("t32910-outline") { cx, dir ->
        seed(dir)
        val out = Text.outline(cx, dir, "$dir/a.zig")
        assertTrue(out.startsWith("1 const std\n2 function alpha\n5 function beta\n"), out)
    }

    /** The walk is the `search` tool's: ignore files and the graph's `exclude` keep files out. */
    @Test fun theWalkHonoursGitignoreAndExclude() = withRuntime("t32910-ignore", { c ->
        text(c).let { it.copy(plugins = it.plugins.copy(graph = it.plugins.graph.copy(exclude = listOf("skip/")))) }
    }) { rt ->
        val dir = tmpDir("t32910-ignore")
        createDirAll("$dir/skip"); createDirAll("$dir/.git")
        writeString("$dir/.gitignore", "ignored.zig\n")
        for (f in listOf("ignored.zig", "skip/s.zig", "kept.zig")) writeString("$dir/$f", "fn alpha() void {}\n")
        val out = Text.symbol(Ctx(rt), dir, "alpha", Filter.None)
        assertTrue(out.contains("kept.zig:1"), out)
        assertFalse(out.contains("ignored.zig") || out.contains("skip/"), out)
        removeDirAll(dir)
    }

    @Test fun aMissingRootIsAnErrorNotAPanic() = pinned("t32910-missing") { cx, dir ->
        val err = assertFails { Text.symbol(cx, "$dir/nope", "alpha", Filter.None) }
        assertTrue(err.message!!.contains("not a readable directory"), err.message)
    }

    @Test fun exploreScansOncePerNameAndHasNoPaths() = pinned("t32910-explore") { cx, dir ->
        seed(dir)
        val parts = Text.Explore(cx, dir, Filter.None)
        assertEquals(listOf("alpha"), parts.resolve("alpha")); assertEquals(emptyList(), parts.resolve("alp"))
        assertEquals(1, parts.defCount("alpha")); assertEquals("1  b.zig  ×1\n" to 1, parts.impact1("alpha"))
        assertEquals(emptyList(), parts.paths("alpha", "beta"))
    }

    @Test fun pinnedTextAppliesAndTagsDoesNot() {
        pinned("t32910-applies") { cx, dir -> assertTrue(Text.applies(cx, dir) { error("no probe when pinned") }) }
        withRuntime("t32910-tags") { rt -> assertFalse(Text.applies(Ctx(rt), tmpDir("t32910-tags")) { null }) }
    }
}
