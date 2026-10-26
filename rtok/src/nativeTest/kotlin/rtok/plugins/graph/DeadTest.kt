package rtok.plugins.graph

import rtok.plugin.sdk.Ctx
import rtok.store.measurementCount
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*
import rtok.sys.ProgressRunner
import kotlin.test.*

class DeadTest {
    private fun project(
        tag: String,
        body: (rtok.plugin.Runtime, Ctx, String) -> Unit,
    ) = withRuntime(tag) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try {
            body(rt, Ctx(rt), dir)
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun deadListsOnlyThePrivateOrphan() =
        project("dead") { _, cx, dir ->
            writeString("$dir/live.rs", "pub fn caller() {\n    used();\n}\nfn used() {}\n")
            writeString("$dir/dead.rs", "fn orphan() {}\n")
            writeString("$dir/api.rs", "pub fn exported() {}\n")
            writeString("$dir/traits.rs", "struct S;\ntrait T {\n    fn m(&self);\n}\nimpl T for S {\n    fn m(&self) {}\n}\n")
            writeString("$dir/mac.rs", "macro_rules! gen {\n    () => {};\n}\n")
            writeString("$dir/tested.rs", "#[test]\nfn my_test() {}\n")
            writeString(
                "$dir/param.rs",
                "#[rstest]\n#[case(1)]\n#[case(2)]\n#[case(3)]\n#[case(4)]\nfn param_test(#[case] n: u8) {}\n" +
                    "#[test]\n#[ignore]\n#[should_panic]\n#[cfg(unix)]\nfn stacked_test() {}\n#[tokio::test]\nasync fn async_test() {}\n",
            )
            createDirAll("$dir/tests")
            writeString("$dir/tests/helper.rs", "fn help_me() {}\n")
            val out = Dead.dead(cx, dir)
            assertTrue(out.contains("orphan"), out)
            for (kept in listOf("used", "caller", "exported", " m\n", "gen", "my_test", "param_test", "stacked_test", "async_test", "help_me", " T\n", " S\n")) {
                assertFalse(out.contains(kept), "$kept in $out")
            }
        }

    private fun fixture(dir: String) {
        writeString("$dir/lib.rs", "fn add() {}\n")
        createDirAll("$dir/tests")
        writeString("$dir/tests/add.rs", "fn test_add() {\n    add();\n}\n")
        writeString("$dir/tests/other.rs", "fn test_other() {}\n")
    }

    @Test fun affectedTwoTestsOneReachesTheChange() =
        project("affected") { rt, cx, dir ->
            fixture(dir)
            val out = Affected.fromPaths(cx, dir, listOf("lib.rs"), 3, false)
            assertTrue(out.contains("tests/add.rs ← via test_add") && out.contains("cargo test test_add") && !out.contains("test_other"), out)
            assertEquals(0, rt.store.measurementCount("graph"))
            assertTrue(Affected.fromPaths(cx, dir, listOf("lib.rs"), 3, true).contains("\"command\":\"cargo test test_add\",\"file\":\"tests/add.rs\""))
            assertEquals(Affected.EMPTY, Affected.fromPaths(cx, dir, listOf("nope.rs"), 3, false))
            assertEquals("""{"message":"${Affected.EMPTY}","tests":[]}""", Affected.fromPaths(cx, dir, listOf("nope.rs"), 3, true))
            assertEquals("pytest t.py::n", Affected.testCommand("t.py", "n"))
            assertEquals("go test -run N", Affected.testCommand("t.go", "N"))
            assertEquals("vitest t.ts", Affected.testCommand("t.ts", "n"))
        }

    @Test fun affectedLinksTestsByNamingConvention() =
        project("affected-by-name") { _, cx, dir ->
            createDirAll("$dir/tests")
            createDirAll("$dir/src")
            writeString("$dir/src/foo.rs", "pub fn foo() {}\n")
            writeString("$dir/tests/foo.rs", "fn test_foo_name_only() {\n    let _ = 1;\n}\n")
            writeString("$dir/src/lonely.rs", "pub fn lonely() {}\n")
            assertTrue(Affected.fromPaths(cx, dir, listOf("src/foo.rs"), 3, false).contains("tests/foo.rs ← via (by name)"))
            assertFalse(Affected.fromPaths(cx, dir, listOf("src/lonely.rs"), 3, false).contains("(by name)"))
            assertEquals(listOf("tests/foo.rs", "src/foo_test.rs"), Affected.nameLinkedTests("src/foo.rs"))
            assertEquals(listOf("src/foo.test.ts", "src/foo.spec.ts", "src/__tests__/foo.ts"), Affected.nameLinkedTests("src/foo.ts"))
            assertEquals(listOf("pkg/test_foo.py", "pkg/foo_test.py"), Affected.nameLinkedTests("pkg/foo.py"))
            assertEquals(listOf("pkg/foo_test.go"), Affected.nameLinkedTests("pkg/foo.go"))
            assertTrue(Affected.nameLinkedTests("readme.md").isEmpty())
            for (p in listOf("src/foo.test.ts", "src/foo.spec.ts", "src/__tests__/foo.ts", "src/test_foo.py", "src/foo_test.go")) assertTrue(Dead.isTestPath(p), p)
        }

    @Test fun affectedReadsGitDiffNameOnly() =
        project("affected-git") { _, cx, dir ->
            fixture(dir)

            fun git(vararg a: String) {
                val (c, _) = ProgressRunner("", "git", listOf("-C", dir, "-c", "user.name=t", "-c", "user.email=t@t") + a).quiet().runCapped(1L shl 20)
                check(c.exitCode == 0)
            }
            git("init", "-q", "-b", "main")
            git("add", ".")
            git("commit", "-q", "-m", "init")
            writeString("$dir/lib.rs", "fn add() {\n    let _ = 1;\n}\n")
            val out = Affected.affected(cx, dir, null, false, false)
            assertTrue(out.contains("tests/add.rs ← via test_add") && !out.contains("test_other"), out)
        }
}
