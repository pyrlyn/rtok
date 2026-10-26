package rtok.plugins.graph

import rtok.plugin.sdk.Ctx
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*
import kotlin.test.*

class ExploreTest {
    private fun project(
        tag: String,
        body: (Ctx, String) -> Unit,
    ) = withRuntime(tag) { rt ->
        val dir = tmpDir(tag)
        try {
            body(Ctx(rt), dir)
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun tokensSplitQueryAndDedupe() = assertEquals(listOf("how", "do", "Foo_bar", "and", "foo", "bar", "relate"), Explore.tokens("how do Foo_bar and foo-bar relate?"))

    @Test fun answersATwoSymbolQuestionByteExact() =
        project("explore") { cx, dir ->
            writeString("$dir/chain.rs", "fn a() {\n    b();\n}\nfn b() {\n    c();\n}\nfn c() {}\n")
            writeString("$dir/other.rs", "fn d() {\n    c();\n    c();\n}\n")
            assertEquals(
                "= b\nchain.rs::b#function@4 chain.rs:4 function\nfn b() {\n    c();\n}\ncalls: c\n" +
                    "= c\nchain.rs::c#function@7 chain.rs:7 function\nfn c() {}\n" +
                    "paths:\nc → b\nimpact:\nb ← 1\nc ← 2\n",
                Explore.explore(cx, dir, "how do b and c interact"),
            )
        }

    @Test fun prefixResolutionRanksByReferenceCount() =
        project("explore-prefix") { cx, dir ->
            writeString("$dir/p.rs", "fn alpha() {}\nfn alphabet() {}\nfn user() {\n    alphabet();\n    alpha();\n    alphabet();\n}\n")
            val out = Explore.explore(cx, dir, "alph")
            val alphabet = out.indexOf("= alphabet\n")
            val alpha = out.indexOf("= alpha\n")
            assertTrue(alphabet >= 0 && alpha >= 0 && alphabet < alpha, out)
            assertEquals("no symbols resolved for \"zz zz\"", Explore.explore(cx, dir, "zz zz"))
        }

    @Test fun pathFilterKeepsOneSubtree() =
        project("explore-path") { cx, dir ->
            writeString("$dir/a.rs", "fn dup() {}\n")
            writeString("$dir/b.rs", "fn dup() {}\n")
            val out = Explore.explore(cx, dir, "dup", Filter(path = "b.rs"))
            assertTrue(out.contains("b.rs:1") && !out.contains("a.rs:1"), out)
        }

    @Test fun ftsFallbackFindsANameFromItsDoc() =
        project("explore-fts") { cx, dir ->
            writeString("$dir/t.rs", "/// Cuts truncated source lines to a budget.\npub fn body_lines() {}\n")
            val out = Explore.explore(cx, dir, "truncated source lines")
            assertTrue(out.contains("= body_lines\n"), out)
        }
}
