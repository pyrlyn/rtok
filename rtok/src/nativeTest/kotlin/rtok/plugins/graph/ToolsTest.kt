package rtok.plugins.graph

import rtok.config.Config
import rtok.plugin.sdk.Ctx
import rtok.store.listMeasurements
import rtok.store.measurementCount
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*
import kotlin.test.*

class ToolsTest {
    private fun backend(name: String): (Config) -> Config = { c -> c.copy(plugins = c.plugins.copy(graph = c.plugins.graph.copy(backend = name))) }

    private fun project(
        tag: String,
        mode: String = "tags",
        body: (rtok.plugin.Runtime, Ctx, String) -> Unit,
    ) = withRuntime(tag, backend(mode)) { rt ->
        val dir = tmpDir(tag)
        try {
            body(rt, Ctx(rt), dir)
        } finally {
            Capability.forget(dir)
            removeDirAll(dir)
        }
    }

    private fun served(
        cx: Ctx,
        root: String,
        names: List<String>,
        lsp: () -> String,
        tags: () -> String,
    ) = Tools.door(cx, root, "symbol", names, { null }, lsp, tags)

    @Test fun symbolReturnsTheDefinitionBodyAndWritesNoRowUnderTheCap() =
        project("tools-body") { rt, cx, dir ->
            writeString("$dir/a.rs", "pub fn alpha() {\n    beta();\n}\nfn beta() {}\n")
            val out = Tools.symbol(cx, dir, "alpha")
            assertTrue(out.startsWith("a.rs::alpha#function@1 a.rs:1 function\npub fn alpha() {\n"), out)
            assertTrue(out.contains("calls: beta\n"), out)
            assertEquals(0, rt.store.measurementCount("graph"))
            assertEquals("no definition of zzz", Tools.symbol(cx, dir, "zzz"))
        }

    @Test fun symbolByIdReturnsOneDefinition() =
        project("tools-byid") { _, cx, dir ->
            writeString("$dir/a.rs", "fn alpha() {}\n")
            writeString("$dir/b.rs", "fn alpha() {}\n")
            val out = Tools.symbolById(cx, dir, "b.rs::alpha#function@1")
            assertTrue(out.startsWith("b.rs::alpha#function@1 b.rs:1 function\n"), out)
            assertFalse(out.contains("a.rs"), out)
            assertEquals("no definition of nonsense", Tools.symbolById(cx, dir, "nonsense"))
            assertEquals(Tools.SymbolId("x/a.rs", "A::b", "fn", 7), Tools.parseSymbolId("x/a.rs::A::b#fn@7"))
        }

    @Test fun callersMarkAmbiguousNamesAndFilterByPath() =
        project("tools-amb") { _, cx, dir ->
            writeString("$dir/a.rs", "fn new() {}\nfn alpha() { new(); }\n")
            writeString("$dir/b.rs", "fn new() {}\n")
            assertFalse(Tools.callers(cx, dir, "alpha").contains('?'))
            val new = Tools.callers(cx, dir, "new")
            assertTrue(new.startsWith("1 names ambiguous"), new)
            assertTrue(new.contains(" ?\n"), new)
            assertTrue(Tools.callers(cx, dir, "new", Filter(path = "zz")).contains("no references to new in zz"))
        }

    @Test fun bodyLinesCutsAtTheBudgetWithAnArchiveId() {
        var archived: ByteArray? = null
        val src = (1..10).joinToString("") { "l$it\n" }
        val out =
            Tools.bodyLines(src, 2, 9, 3) {
                archived = it
                "abcdef0123456789"
            }
        assertEquals("l2\nl3\nl4\n  … 5 more lines, expand ${rtok.shortId("abcdef0123456789")}\n", out)
        assertEquals((2..9).joinToString("") { "l$it\n" }, archived!!.decodeToString())
        assertEquals("l1\n  … 1 more lines\n", Tools.bodyLines("l1\nl2\n", 1, 2, 1) { null })
    }

    @Test fun manyDefinitionsAreCappedWithAnArchiveId() =
        project("tools-cap") { rt, cx, dir ->
            writeString("$dir/a.rs", (1..500).joinToString("") { "mod m$it { fn same() {} }\n" })
            val out = Tools.symbol(cx, dir, "same")
            assertTrue(Regex("""\d+ more, expand [0-9a-f]{8}$""").containsMatchIn(out), out.takeLast(200))
            assertTrue(rt.store.listMeasurements("graph").any { it.kind == "cap" && it.refId != null })
        }

    /** T376: an empty LSP answer falls back only for a name the tags index has; `tags` never asks the server. */
    @Test fun lspEmptyAnswerFallsBackOnlyForAKnownName() {
        project("t376-empty", "lsp") { _, cx, dir ->
            writeString("$dir/a.rs", "pub fn alpha() {}\n")
            val tags = { "tags answer" }
            assertEquals("(tags; lsp: empty answer)\ntags answer", served(cx, dir, listOf("alpha"), { "no definition of alpha" }, tags))
            assertEquals("no definition of zzz", served(cx, dir, listOf("zzz"), { "no definition of zzz" }, tags))
            assertEquals("a.rs:1 function", served(cx, dir, listOf("alpha"), { "a.rs:1 function" }, tags))
        }
        project("t376-plain") { _, cx, dir -> assertEquals("tags answer", served(cx, dir, listOf("alpha"), { error("lsp") }) { "tags answer" }) }
    }

    @Test fun autoSaysWhichModeAnsweredAndRecordsRows() =
        project("tools-auto", "auto") { rt, cx, dir ->
            writeString("$dir/Cargo.toml", "[package]\nname = \"x\"\n")
            writeString("$dir/a.rs", "fn alpha() {}\n")
            assertEquals("(lsp)\na.rs:1 function", served(cx, dir, listOf("alpha"), { "a.rs:1 function" }) { "t" })
            val other = "$dir/b"
            createDirAll(other)
            writeString("$other/go.mod", "module x\n")
            val out = Tools.door(cx, other, "callers", listOf("alpha"), { Absent(false, "lsp: none") }, { error("no") }) { "t" }
            assertEquals("(tags)\nt", out)
            assertTrue(rt.store.listMeasurements("graph").any { it.kind == "tags.callers" })
            Capability.forget(other)
        }
}
