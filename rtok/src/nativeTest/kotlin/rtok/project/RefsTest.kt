package rtok.project

import kotlin.test.*
import rtok.support.tmpDir
import rtok.sys.*

class RefsTest {
    /** Sibling directories: `proj` is the root under test, the rest are outside it. */
    private class Fx(tag: String) {
        val base = canonicalize(tmpDir(tag))!!
        fun file(path: String, text: String) = apply { createDirAll(parent("$base/$path")!!); writeString("$base/$path", text) }
        fun dir(vararg paths: String) = apply { paths.forEach { createDirAll("$base/$it") } }
        fun run(): Pair<List<Pair<String, String>>, List<String>> {
            val f = Refs.discover("$base/proj")
            return f.refs.map { stripPrefix(it.dir, base)!! to it.reason } to f.warnings
        }
    }

    private fun fx(tag: String, body: (Fx) -> Unit) { val f = Fx(tag); try { body(f) } finally { removeDirAll(f.base) } }

    @Test fun cargoPathPatchAndWorkspaceReferences() = fx("refs-cargo") { fx ->
        fx.file("proj/Cargo.toml", """[workspace]
members = ["crates/*", "../outer", "../ghost-member"]
[workspace.dependencies]
wsdep = { path = "../wsdep" }
[dependencies]
serde = "1"
shared = { path = "../shared" }
inner = { path = "crates/a" }
ghost = { path = "../ghost" }
[dev-dependencies.dev]
path = "../dev"
[target.'cfg(unix)'.dependencies]
unix = { path = "../unix" }
[patch.crates-io]
patched = { path = "../patched" }
registry-patch = { git = "https://example.com/x" }
""")
        fx.file("proj/crates/a/Cargo.toml", "[dependencies]\nfar = { path = \"../../../far\" }\nown = { path = \"../b\" }\n")
        fx.dir("proj/crates/b", "proj/crates/README", "outer", "wsdep", "shared", "dev", "unix", "patched", "far")
        val (refs, warnings) = fx.run()
        assertEquals(listOf("outer" to "cargo workspace member ../outer", "shared" to "cargo path dependency shared", "dev" to "cargo path dependency dev",
            "wsdep" to "cargo workspace dependency wsdep", "unix" to "cargo path dependency unix", "patched" to "cargo [patch] patched", "far" to "cargo path dependency far"), refs)
        assertEquals(listOf("Cargo.toml: references ../ghost-member, not found", "Cargo.toml: references ../ghost, not found"), warnings)
    }

    @Test fun npmPnpmAndYarnLocalReferences() = fx("refs-npm") { fx ->
        fx.file("proj/package.json", """{"workspaces": {"packages": ["packages/*", "../ws", "!packages/skip"]},
            "dependencies": {"react": "^18", "lib": "file:../lib", "wsp": "workspace:*", "tar": "file:../pack.tgz", "gone": "link:../gone"},
            "devDependencies": {"linked": "link:../linked", "rel": "workspace:../wrel"}}""")
        fx.file("proj/pnpm-workspace.yaml", "packages:\n  - '../pnpm-pkg'\n  - 'apps/*'\n")
        fx.file("proj/packages/p/package.json", """{"dependencies": {"deep": "file:../../../deep"}}""")
        fx.file("pack.tgz", "")
        fx.dir("lib", "linked", "wrel", "ws", "pnpm-pkg", "deep")
        val (refs, warnings) = fx.run()
        assertEquals(listOf("ws", "pnpm-pkg", "lib", "linked", "wrel", "deep"), refs.map { it.first })
        assertEquals("npm workspace member ../ws", refs[0].second); assertEquals("npm dependencies lib", refs[2].second)
        assertEquals(listOf("package.json: references ../gone, not found"), warnings)
    }

    @Test fun negativeWorkspacePatternsSubtractMembers() = fx("refs-neg") { fx ->
        fx.file("proj/package.json", """{"dependencies": {"root": "file:../rootdep"}}""")
        fx.file("proj/pnpm-workspace.yaml", "packages:\n  - '!packages/skip'\n  - 'packages/*'\n  - 'libs/*'\n  - '!**/gen/**'\n  - '../outer-a'\n  - '../outer-b'\n  - '!../outer-b'\n")
        for ((pkg, dep) in listOf("proj/packages/keep" to "keepdep", "proj/packages/skip" to "skipdep", "proj/libs/ok" to "okdep", "proj/libs/gen" to "gendep", "outer-a" to "adep", "outer-b" to "bdep")) {
            val up = if (pkg.startsWith("proj")) "../../../" else "../"
            fx.file("$pkg/package.json", """{"dependencies": {"d": "file:$up$dep"}}"""); fx.dir(dep)
        }
        fx.dir("rootdep")
        val (refs, warnings) = fx.run()
        assertEquals(listOf("outer-a", "rootdep", "keepdep", "okdep", "adep"), refs.map { it.first }); assertEquals(emptyList(), warnings)
    }

    @Test fun goReplaceAndWorkspaceUse() = fx("refs-go") { fx ->
        fx.file("proj/go.mod", "module m\nreplace example.com/a => ../goa // local\nreplace (\n\texample.com/b v1.0.0 => example.com/c v1.2.0\n\texample.com/d => \"../god\"\n\texample.com/e => ../gone\n)\n")
        fx.file("proj/go.work", "go 1.22\nuse ./mod1\nuse (\n\t../gowork\n)\nreplace example.com/f => ../gof\n")
        fx.file("proj/mod1/go.mod", "replace example.com/g => ../../gog\n")
        fx.dir("goa", "god", "gowork", "gof", "gog")
        val (refs, warnings) = fx.run()
        assertEquals(listOf("gowork" to "go.work use ../gowork", "gof" to "go replace example.com/f", "goa" to "go replace example.com/a",
            "god" to "go replace example.com/d", "gog" to "go replace example.com/g"), refs)
        assertEquals(listOf("go.mod: references ../gone, not found"), warnings)
    }

    @Test fun pythonPep508PoetryUvAndRequirements() = fx("refs-py") { fx ->
        fx.file("proj/pyproject.toml", """[project]
dependencies = ["requests>=2", "pep @ file:../pep", "abs @ file:///nowhere/abs", "web @ https://example.com/w.whl"]
[project.optional-dependencies]
extra = ["ex @ file:../ex ; python_version > '3.9'"]
[tool.poetry.dependencies]
python = "^3.11"
poet = { path = "../poet" }
[tool.poetry.group.dev.dependencies]
pdev = { path = "../pdev", develop = true }
[tool.uv.sources]
uvlib = { path = "../uvlib" }
[tool.uv.workspace]
members = ["../uvws/*"]
""")
        fx.file("proj/requirements.txt", "-e ../req\n./inside\nflask\n")
        fx.file("uvws/one/pyproject.toml", "")
        fx.dir("uvws/no-manifest", "pep", "ex", "poet", "pdev", "uvlib", "req")
        val (refs, warnings) = fx.run()
        assertEquals(listOf("pep", "ex", "poet", "pdev", "uvlib", "uvws/one", "req"), refs.map { it.first })
        assertEquals("python path dependency pep", refs[0].second)
        assertEquals(listOf("pyproject.toml: references /nowhere/abs, not found"), warnings)
    }

    @Test fun aSubmoduleInsideTheRootIsAReference() = fx("refs-sub") { fx ->
        fx.file("proj/.gitmodules", "[submodule \"lib\"]\n\tpath = vendor/lib\n\turl = https://example.com/lib.git\n[submodule \"gone\"]\n\tpath = vendor/gone\n[other]\n\tpath = not-a-submodule\n")
        fx.dir("proj/vendor/lib", "proj/not-a-submodule")
        assertEquals(listOf("proj/vendor/lib" to "git submodule vendor/lib") to listOf(".gitmodules: references vendor/gone, not found"), fx.run())
    }

    @Test fun oneEntryPerDirectoryInSourceOrderAndNothingWithoutManifests() = fx("refs-one") { fx ->
        fx.dir("proj")
        assertEquals(emptyList<Pair<String, String>>() to emptyList(), fx.run())
        fx.file("proj/Cargo.toml", "[dependencies]\nx = { path = \"../x\" }\n").file("proj/package.json", """{"dependencies": {"x": "file:../x"}}""").dir("x")
        assertEquals(listOf("x" to "cargo path dependency x"), fx.run().first)
    }

    @Test fun anUnparsableManifestIsAWarningAndTheRestStillRead() = fx("refs-bad") { fx ->
        fx.file("proj/Cargo.toml", "[dependencies\n").file("proj/package.json", "{").file("proj/go.mod", "replace a => ../g\n").dir("g")
        val (refs, warnings) = fx.run()
        assertEquals(listOf("g" to "go replace a"), refs)
        assertEquals(2, warnings.size, "$warnings")
        assertTrue(warnings[0].startsWith("Cargo.toml: not read") && warnings[1].startsWith("package.json: not read"), "$warnings")
    }

    @Test fun aPathThatContainsTheRootIsNotAReference() = fx("refs-up") { fx ->
        fx.file("proj/Cargo.toml", "[dependencies]\nup = { path = \"..\" }\n")
        assertEquals(emptyList<Pair<String, String>>() to emptyList(), fx.run())
    }
}
