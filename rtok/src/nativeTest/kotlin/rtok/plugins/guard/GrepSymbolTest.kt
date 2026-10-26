package rtok.plugins.guard

import kotlinx.serialization.json.*
import rtok.config.Config
import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.PreToolDecision
import rtok.plugin.sdk.PreToolUse
import rtok.plugins.graph.Index
import rtok.store.measurementCount
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*
import kotlin.test.*

class GrepSymbolTest {
    private fun j(vararg kv: Pair<String, Any>) =
        JsonObject(
            kv.associate { (k, v) ->
                k to
                    when (v) {
                        is Boolean -> JsonPrimitive(v)
                        else -> JsonPrimitive(v.toString())
                    }
            },
        )

    @Test fun classifier() {
        val cases =
            listOf(
                j("pattern" to "parse_since") to "parse_since",
                j("pattern" to " parse_since ") to "parse_since",
                j("pattern" to "fn parse_since") to "parse_since",
                j("pattern" to "class  Foo") to "Foo",
                j("pattern" to "interface IFoo", "output_mode" to "content") to "IFoo",
                j("pattern" to "func Run", "path" to ".") to "Run",
                j("pattern" to "TODO|FIXME") to null,
                j("pattern" to "\\bfoo\\b") to "foo",
                j("pattern" to "\\bfoo\\(") to "foo",
                j("pattern" to "foo\\(") to "foo",
                j("pattern" to "\\bfoo") to "foo",
                j("pattern" to "fn foo\\(") to "foo",
                j("pattern" to "foo\\b") to "foo",
                j("pattern" to "\\bfoo|bar\\b") to null,
                j("pattern" to "\\b\\bfoo") to null,
                j("pattern" to "^foo$") to null,
                j("pattern" to "foo.*") to null,
                j("pattern" to "\\bfoo.*\\b") to null,
                j("pattern" to "[fF]oo") to null,
                j("pattern" to "\\b\\b") to null,
                j("pattern" to "fn parse_since(") to null,
                j("pattern" to "foo.bar") to null,
                j("pattern" to "impl Foo") to null,
                j("pattern" to "fn foo bar") to null,
                j("pattern" to "x") to null,
                j("pattern" to "9lives") to null,
                j("pattern" to "") to null,
                j("pattern" to "foo", "glob" to "*.rs") to null,
                j("pattern" to "foo", "type" to "rust") to null,
                j("pattern" to "foo", "-i" to true) to null,
                j("pattern" to "foo", "-i" to false) to "foo",
                j("pattern" to "foo", "multiline" to true) to null,
                j() to null,
            )
        for ((input, want) in cases) assertEquals(want, GrepSymbol.symbolName(input), "$input")
    }

    private val lib = "pub fn parse_since(s: &str) -> u32 {\n    s.len() as u32\n}\n\npub fn caller() -> u32 {\n    parse_since(\"1d\") + parse_since(\"2d\")\n}\n"

    /** An indexed project `proj` in a temp dir, `grep_symbol` set as asked; the session cwd is its root. */
    private fun project(
        tag: String,
        on: Boolean,
        files: List<Pair<String, String>>,
        tune: (Config) -> Config = { it },
        body: (Runtime, String) -> Unit,
    ) = withRuntime(tag, { c -> tune(c.copy(plugins = c.plugins.copy(guard = c.plugins.guard.copy(grepSymbol = on)))) }) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try {
            val root = "$dir/proj"
            createDirAll(root)
            for ((n, b) in files) writeString("$root/$n", b)
            Index.run(Ctx(rt), root, false)
            rt.cwd = root
            body(rt, root)
        } finally {
            removeDirAll(dir)
        }
    }

    private fun grep(
        rt: Runtime,
        input: JsonObject,
    ): String? = (GuardPlugin.preTool(PreToolUse("Grep", input), Ctx(rt)) as? PreToolDecision.Deny)?.reason

    @Test fun answersOneDefinitionWithSourceAndReferenceCount() =
        project("t369-one", true, listOf("lib.rs" to lib)) { rt, root ->
            for (pattern in listOf("fn parse_since", "parse_since")) {
                val r = grep(rt, j("pattern" to pattern))!!
                assertTrue(r.contains("lib.rs:1 function"), r)
                assertTrue(r.contains("pub fn parse_since(s: &str) -> u32 {"), r)
                assertTrue(r.contains("2 refs"), r)
            }
            assertNotNull(grep(rt, j("pattern" to "parse_since", "path" to root)))
            assertEquals(3, rt.store.measurementCount("guard"))
        }

    @Test fun fallsThroughWhenItCannotAnswer() =
        project("t369-skip", true, listOf("lib.rs" to lib, "d.rs" to "fn dup() {}\n".repeat(6))) { rt, root ->
            for (input in listOf(
                j("pattern" to "TODO|FIXME"),
                j("pattern" to "never_defined"),
                j("pattern" to "dup"),
                j("pattern" to "parse_since", "glob" to "*.rs"),
                j("pattern" to "parse_since", "path" to "src"),
                j("pattern" to "parse_since", "path" to "$root/lib.rs"),
            )) {
                assertNull(grep(rt, input), "$input")
            }
            assertEquals(0, rt.store.measurementCount("guard"))
        }

    @Test fun flagOffByDefault() = project("t369-off", false, listOf("lib.rs" to lib)) { rt, _ -> assertNull(grep(rt, j("pattern" to "parse_since"))) }

    @Test fun aStaleIndexFallsOpen() =
        project("t369-stale", true, listOf("lib.rs" to lib)) { rt, root ->
            assertNotNull(grep(rt, j("pattern" to "parse_since")))
            writeString("$root/lib.rs", "// moved\n".repeat(4))
            assertNull(grep(rt, j("pattern" to "parse_since")))
            removeFile("$root/lib.rs")
            assertNull(grep(rt, j("pattern" to "parse_since")))
        }

    @Test fun aDirectoryPathKeepsOnlyTheDefinitionsUnderIt() =
        project("t369-dir", true, listOf("lib.rs" to lib)) { rt, root ->
            createDirAll("$root/sub")
            writeString("$root/sub/m.rs", "pub fn parse_since() {}\n")
            createDirAll("$root/other")
            Index.run(Ctx(rt), root, false)
            val wide = grep(rt, j("pattern" to "parse_since"))!!
            assertTrue(wide.contains("lib.rs:1") && wide.contains("sub/m.rs:1"), wide)
            for (spelling in listOf("sub", "sub/", "./sub", "$root/sub")) {
                val r = grep(rt, j("pattern" to "parse_since", "path" to spelling))!!
                assertTrue(r.contains("sub/m.rs:1") && !r.contains("lib.rs:1"), r)
            }
            for (p in listOf("other", "../outside", "/", "sub/m.rs", "missing")) assertNull(grep(rt, j("pattern" to "parse_since", "path" to p)), p)
        }

    @Test fun wordBoundaryAndCallSpellingsAreAnswered() =
        project("t369-spell", true, listOf("lib.rs" to lib)) { rt, _ ->
            for (p in listOf("\\bparse_since\\b", "\\bparse_since\\(", "parse_since\\(", "\\bparse_since")) {
                assertTrue(grep(rt, j("pattern" to p))!!.contains("lib.rs:1 function"), p)
            }
            assertNull(grep(rt, j("pattern" to "\\bparse_since|caller\\b")))
        }

    @Test fun answerIsCappedAtTheInjectBudget() =
        project(
            "t369-cap",
            true,
            listOf("b.rs" to "fn big() {\n${"    let value = 1 + 1;\n".repeat(30)}}\n"),
            { c -> c.copy(plugins = c.plugins.copy(inject = c.plugins.inject.copy(budgetTokens = 60u))) },
        ) { rt, _ ->
            val r = grep(rt, j("pattern" to "big"))!!
            assertTrue(Ctx(rt).estimate(r, Class.Code) <= 60u, r)
            assertTrue(r.contains("b.rs:1 function"), r)
        }

    @Test fun subdirectoryCwdFindsTheGitRootIndex() =
        project("t369-sub", true, listOf("lib.rs" to lib)) { rt, root ->
            createDirAll("$root/.git")
            createDirAll("$root/deep")
            rt.cwd = "$root/deep"
            assertNotNull(grep(rt, j("pattern" to "parse_since")))
        }
}
