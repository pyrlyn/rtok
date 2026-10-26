package rtok.plugins.graph

import kotlin.test.*
import rtok.config.Config
import rtok.plugin.sdk.Ctx
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class CoreTest {
    private fun auto(byLanguage: Map<String, String>): (Config) -> Config = { c ->
        c.copy(plugins = c.plugins.copy(graph = c.plugins.graph.copy(backend = "auto", backendByLanguage = byLanguage)))
    }

    /** The language comes from the marker file; a value the schema refuses reads as tags. */
    @Test fun modeOfReadsTheLanguageAndDistrustsUnknownValues() =
        withRuntime("t3299-mode", auto(mapOf("go" to "tags", "rust" to "grep"))) { rt ->
            val cx = Ctx(rt); val dir = tmpDir("t3299-mode")
            assertEquals(Mode.Auto, GraphCore.modeOf(cx, dir))
            writeString("$dir/go.mod", "module x\n")
            assertEquals("go", Langs.languageOf(dir)); assertEquals(Mode.Tags, GraphCore.modeOf(cx, dir))
            writeString("$dir/Cargo.toml", "")
            assertEquals("rust", Langs.languageOf(dir)); assertEquals(Mode.Tags, GraphCore.modeOf(cx, dir))
            assertEquals("body\n", GraphCore.withoutModeLine("(tags; lsp: x)\nbody\n"))
            assertEquals("body", GraphCore.withoutModeLine("(lsp)\nbody"))
            assertEquals("body", GraphCore.withoutModeLine("(text)\nbody"))
            assertEquals("(not a mode line)\n", GraphCore.withoutModeLine("(not a mode line)\n"))
            removeDirAll(dir)
        }

    @Test fun filterMatchesAndNotesOnlyWhatIsSet() {
        assertEquals("", Filter.None.scopeNote())
        val f = Filter(path = "src/", kind = "struct")
        assertEquals(" in src/ of kind struct", f.scopeNote())
        assertTrue(f.pathOk("a/src/x.rs")); assertFalse(f.pathOk("lib/x.rs"))
        assertTrue(f.kindOk("struct")); assertFalse(f.kindOk("function"))
        assertTrue(Filter.None.pathOk("x") && Filter.None.kindOk("y"))
    }

    @Test fun languageTablesMatchRust() {
        assertEquals(LspServer("dart", listOf("language-server")), Langs.serverOf("dart"))
        assertNull(Langs.serverOf("go"))
        assertEquals(listOf("rust", "c", "typescript", "dart"), Langs.servedLanguages())
        assertEquals("kotlin", Langs.languageOfExt("KT")); assertNull(Langs.languageOfExt("txt"))
    }

    @Test fun lspNoneAnswerRecognisesEmptyAnswers() {
        assertTrue(GraphCore.lspNoneAnswer("  \n"))
        assertTrue(GraphCore.lspNoneAnswer(" no references to x"))
        assertFalse(GraphCore.lspNoneAnswer("a.rs:1 fn x"))
    }

    /** T8.15: with `auto_index = false` an edit shows a stale banner until an explicit index; `indexFor` reads nothing. */
    @Test fun autoIndexFalseIsStaleUntilExplicitIndex() =
        withRuntime("noauto", { c -> c.copy(plugins = c.plugins.copy(graph = c.plugins.graph.copy(autoIndex = false))) }) { rt ->
            val cx = Ctx(rt); val dir = canonicalize(tmpDir("noauto"))!!
            writeString("$dir/a.rs", "fn alpha() {}\n")
            assertEquals(1, GraphCore.indexFor(cx, dir).indexed)
            assertEquals("x", GraphCore.withStale(cx, dir, "x"))
            platform.posix.usleep(5_000u)
            writeString("$dir/a.rs", "// bump\nfn alpha() {}\n")
            val stale = GraphCore.withStale(cx, dir, "body")
            assertTrue(stale.startsWith("stale: 1 files pending (a.rs)\n") && stale.endsWith("body"), stale)
            assertEquals(0, GraphCore.indexFor(cx, dir).read, "the call must open no file")
            Index.run(cx, dir, false)
            assertEquals("body", GraphCore.withStale(cx, dir, "body"))
            removeDirAll(dir)
        }
}
