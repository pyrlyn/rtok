package rtok.plugins.graph

import kotlin.test.*
import rtok.plugin.sdk.Ctx
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class CochangeTest {
    private fun git(dir: String, vararg args: String) {
        val (c, _) = ProgressRunner("", "git", listOf("-C", dir, "-c", "user.name=t", "-c", "user.email=t@t") + args).quiet().runCapped(1L shl 20)
        check(c.exitCode == 0) { "git ${args.toList()}: ${c.stderr.decodeToString()}" }
    }

    /** Stages only [files]: the runtime may keep state next to them. */
    private fun commit(dir: String, files: List<String>, msg: String) {
        for (f in files) { parent("$dir/$f")?.let(::createDirAll); writeString("$dir/$f", "$msg\n") }
        git(dir, *(listOf("add", "--") + files).toTypedArray()); git(dir, "commit", "-q", "-m", msg)
    }

    /** `a`+`b`, `a`+`b`+`c`, and 23 files over the cap. Only the first two count. */
    private fun scriptedRepo(tag: String): String = canonicalize(tmpDir(tag))!!.also { dir ->
        git(dir, "init", "-q", "-b", "main")
        commit(dir, listOf("a.rs", "b.rs"), "one")
        commit(dir, listOf("a.rs", "b.rs", "c.rs"), "two")
        commit(dir, (0 until 21).map { "w$it.rs" } + listOf("a.rs", "b.rs"), "three")
    }

    @Test fun countsPairsOverTheCapAndBelowTheMinimum() = withRuntime("cochange-count") { rt ->
        val dir = scriptedRepo("cochange-count"); val cx = Ctx(rt)
        assertEquals(listOf(CochangePair("a.rs", "b.rs", 2)), Cochange.pairs(cx, dir))
        assertEquals("changes with: b.rs (2)", Cochange.changesWith(cx, dir, "a.rs"))
        assertNull(Cochange.changesWith(cx, dir, "c.rs"))
        removeDirAll(dir)
    }

    @Test fun recountsOnlyWhenHeadMovesAndSkipsDeletedPartners() = withRuntime("cochange-head") { rt ->
        val dir = scriptedRepo("cochange-head"); val cx = Ctx(rt); val key = Cochange.key(dir)
        assertNull(cx.pluginStateGet("graph", key), "nothing stored before the first call")
        assertEquals(1, Cochange.pairs(cx, dir).size)
        val head = Cochange.decode(cx.pluginStateGet("graph", key))!!.first
        // A document for the current HEAD is trusted as is: no recount.
        val fake = listOf(CochangePair("x.rs", "y.rs", 9))
        cx.pluginStateSet("graph", key, Cochange.encode(head, fake))
        assertEquals(fake, Cochange.pairs(cx, dir))
        commit(dir, listOf("a.rs", "b.rs"), "four")
        assertEquals(listOf(CochangePair("a.rs", "b.rs", 3)), Cochange.pairs(cx, dir))
        removeFile("$dir/b.rs")
        assertNull(Cochange.changesWith(cx, dir, "a.rs"))
        removeDirAll(dir)
    }

    @Test fun impactListsThePartnersOfTheDefiningFile() = withRuntime("cochange-impact") { rt ->
        val dir = canonicalize(tmpDir("cochange-impact"))!!; val cx = Ctx(rt)
        git(dir, "init", "-q", "-b", "main")
        for (m in listOf("one", "two")) commit(dir, listOf("core.rs", "schema.sql", "docs/core.md"), m)
        commit(dir, listOf("core.rs"), "three")
        writeString("$dir/core.rs", "pub fn core_fn() {}\n")
        writeString("$dir/caller.rs", "fn go() {\n    core_fn();\n}\n")
        val out = Impact.impact(cx, dir, "core_fn", 2)
        assertTrue(out.contains("caller.rs"), out)
        assertTrue(out.trimEnd().endsWith("changes with: docs/core.md (2), schema.sql (2)"), out)
        val silent = Impact.impact(cx, dir, "go", 2)
        assertFalse(silent.contains("changes with"), silent)
        removeDirAll(dir)
    }

    @Test fun aRootThatIsNotARepoHasNoPairs() = withRuntime("cochange-norepo") { rt ->
        val dir = tmpDir("cochange-norepo"); val cx = Ctx(rt)
        assertTrue(Cochange.pairs(cx, dir).isEmpty())
        assertNull(Cochange.changesWith(cx, dir, "a.rs"))
        removeDirAll(dir)
    }

    /** The document keeps the Rust serde layout (`pairs` as `[a, b, n]` tuples), so either binary reads it. */
    @Test fun documentMatchesTheRustLayout() {
        val text = Cochange.encode("h", listOf(CochangePair("a.rs", "b.rs", 2)))
        assertEquals("""{"head":"h","pairs":[["a.rs","b.rs",2]]}""", text)
        assertEquals("h" to listOf(CochangePair("a.rs", "b.rs", 2)), Cochange.decode(text))
        assertNull(Cochange.decode("not json"))
    }
}
