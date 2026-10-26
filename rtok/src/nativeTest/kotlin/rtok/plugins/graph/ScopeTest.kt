package rtok.plugins.graph

import kotlin.test.*
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.LinkKind
import rtok.store.Origin
import rtok.store.linkProjects
import rtok.store.projectByRoot
import rtok.store.registerProject
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class ScopeTest {
    private val call = "fn shared() {}\n"

    /** `a` → `b` → `c`, and an unlinked `d`; [files] are each project's `lib.rs`. */
    private fun world(tag: String, files: List<String>, body: (Runtime, Ctx, String) -> Unit) = withRuntime(tag) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try {
            val ids = listOf("a", "b", "c", "d").zip(files).map { (n, src) -> createDirAll("$dir/$n"); writeString("$dir/$n/lib.rs", src); rt.store.registerProject("$dir/$n", Origin.Manual).id }
            rt.store.linkProjects(ids[0], ids[1], LinkKind.Manual, null); rt.store.linkProjects(ids[1], ids[2], LinkKind.Manual, null)
            body(rt, Ctx(rt), dir)
        } finally { removeDirAll(dir) }
    }

    private fun fixture(tag: String, body: (Runtime, Ctx, String) -> Unit) =
        world(tag, listOf("fn a_caller() { shared(); }\n", "fn b_caller() { shared(); }\n", call, "fn d_caller() { shared(); }\n"), body)

    private fun at(rt: Runtime, dir: String, cwd: String, project: String? = null) = Scope.resolve(rt.store, project, "$dir/$cwd")

    @Test fun callersOfAFunctionInCLabelTheCallSitesInAAndB() = fixture("t3294-callers") { rt, cx, dir ->
        val scope = at(rt, dir, "a"); assertEquals(3, scope.size)
        assertEquals("[a] lib.rs  a_caller ×1 (L1)\n[b] lib.rs  b_caller ×1 (L1)\n", Scope.callers(cx, scope, "shared", Filter.None))
    }

    @Test fun projectDDoesNotCrossAndASingleProjectKeepsThePlainOutput() = fixture("t3294-d") { rt, cx, dir ->
        val scope = at(rt, dir, "a", "$dir/d"); assertEquals(1, scope.size)
        val out = Scope.callers(cx, scope, "shared", Filter.None)
        assertEquals("lib.rs  d_caller ×1 (L1)\n", out); assertEquals(out, Tools.callers(cx, "$dir/d", "shared"))
    }

    @Test fun noProjectFromAEqualsAByPathAndById() = fixture("t3294-default") { rt, _, dir ->
        val id = rt.store.projectByRoot("$dir/a")!!.id.toString()
        val plain = at(rt, dir, "a").map { it.root }
        assertEquals(plain, at(rt, dir, "d", "$dir/a").map { it.root }); assertEquals(plain, at(rt, dir, "d", id).map { it.root })
    }

    @Test fun anUnregisteredDirectoryIsAScopeOfOneAndAnUnknownProjectErrors() = fixture("t3294-unreg") { rt, _, dir ->
        createDirAll("$dir/loose")
        val scope = Scope.resolve(rt.store, null, "$dir/loose")
        assertEquals(listOf("$dir/loose"), scope.map { it.root })
        assertTrue(assertFails { Scope.resolve(rt.store, "9999", "$dir/loose") }.message!!.contains("no project"))
    }

    @Test fun aNameDefinedInTwoProjectsIsGroupedFlaggedAndTheSelectedOneLeads() =
        world("t3294-dup", listOf("fn dup() {}\n", "fn b() {}\n", "fn dup() {}\nfn c_caller() { dup(); }\n", "")) { rt, cx, dir ->
            val scope = at(rt, dir, "a")
            assertEquals("1 names ambiguous (?): narrow with path or kind, or backend = \"lsp\"\n" +
                "[a] lib.rs::dup#function@1 lib.rs:1 function ?\nfn dup() {}\n[c] lib.rs::dup#function@1 lib.rs:1 function ?\nfn dup() {}\n",
                Scope.symbol(cx, scope, "dup", Filter.None))
            val callers = Scope.callers(cx, scope, "dup", Filter.None)
            assertTrue(callers.startsWith("1 names ambiguous (?)") && callers.contains("[c] lib.rs  c_caller ×1 (L2) ?"), callers)
            assertEquals("[c] lib.rs::dup#function@1 lib.rs:1 function\nfn dup() {}\n", Scope.symbol(cx, at(rt, dir, "b"), "dup", Filter.None))
        }

    @Test fun oneCapAppliesToTheWholeScope() {
        val many = (0 until 300).joinToString("") { "fn c$it() { shared(); }\n" }
        world("t3294-cap", listOf(many, many, call, "")) { rt, cx, dir ->
            val out = Scope.callers(cx, at(rt, dir, "a"), "shared", Filter.None)
            assertTrue(out.lines().last().contains(" more, expand ") && out.contains("[a] "), out)
        }
    }

    @Test fun noHitInAnyProjectSaysSoOnce() = fixture("t3294-none") { rt, cx, dir ->
        val scope = at(rt, dir, "a")
        assertEquals("no definition of nothing", Scope.symbol(cx, scope, "nothing", Filter.None))
        assertEquals("no references to nothing", Scope.callers(cx, scope, "nothing", Filter.None))
    }

    private fun chain(tag: String, body: (Runtime, Ctx, String) -> Unit) =
        world(tag, listOf("fn a_top() { b_mid(); }\n", "fn b_mid() { shared(); }\n", call, "fn d_caller() { shared(); }\n"), body)

    @Test fun impactOfAFunctionInCWalksUpThroughBIntoA() = chain("t3294-impact") { rt, cx, dir ->
        val scope = at(rt, dir, "a")
        assertEquals("1  [b] lib.rs  b_mid\n2  [a] lib.rs  a_top\n", Scope.impact(cx, scope, "shared", 3, Filter.None, null))
        assertEquals("1  [b] lib.rs  b_mid\n", Scope.impact(cx, scope, "shared", 1, Filter.None, null))
        assertEquals("1  [b] lib.rs  b_mid\n", Scope.impact(cx, at(rt, dir, "b"), "shared", 3, Filter.None, null))
        val one = Scope.impact(cx, at(rt, dir, "a", "$dir/d"), "shared", 3, Filter.None, null)
        assertEquals("1  lib.rs  d_caller\n", one); assertEquals(one, Impact.impactFiltered(cx, "$dir/d", "shared", 3, Filter.None))
        assertEquals("nothing reaches a_top", Scope.impact(cx, scope, "a_top", 3, Filter.None, null))
    }

    @Test fun exploreAnswersOverTheScopeAndLabelsEachProject() = chain("t3294-explore") { rt, cx, dir ->
        val out = Scope.explore(cx, at(rt, dir, "a"), "how does b_mid reach shared", Filter.None)
        assertTrue(out.contains("= b_mid\n[b] lib.rs::b_mid") && out.contains("= shared\n[c] lib.rs::shared"), out)
        assertTrue(out.contains("impact:\nb_mid ← 1\nshared ← 1\n"), out)
    }

    @Test fun outlineReadsTheFileOfTheProjectAskedFor() = chain("t3294-outline") { rt, cx, dir ->
        assertTrue(Scope.outline(cx, at(rt, dir, "a"), "$dir/c/lib.rs").contains("shared"))
        assertEquals(2, Scope.holder(at(rt, dir, "a"), "$dir/c/lib.rs"))
    }

    @Test fun deadOverAScopeSparesWhatOnlyALinkedProjectCalls() =
        world("t3294-dead", listOf("fn a_top() { b_only(); }\n", "fn b_only() {}\nfn b_dead() {}\n", "", "")) { rt, cx, dir ->
            val out = Scope.dead(cx, at(rt, dir, "a"))
            assertTrue(out.contains("[b] lib.rs:2 function b_dead") && !out.contains("b_only"), out)
            assertTrue(Scope.deadJson(cx, at(rt, dir, "a")).contains("\"project\": \"b\""))
            assertEquals("no dead code in nowhere", Scope.deadFiltered(cx, at(rt, dir, "a"), Filter(path = "nowhere")).replace(" in nowhere", " in nowhere"))
        }

    @Test fun affectedPathBelongsToTheProjectThatHoldsTheFile() =
        world("t3295-affected-path", listOf("fn a_top() { b_fn(); }\n", "fn b_fn() {}\n", "", "")) { rt, cx, dir ->
            createDirAll("$dir/b/tests"); writeString("$dir/b/tests/b.rs", "fn test_b() { b_fn(); }\n")
            assertEquals("[b]\ntests/b.rs ← via test_b\ncargo test test_b\n", Scope.affectedPath(cx, at(rt, dir, "a"), "$dir/b/lib.rs", 3))
            assertEquals("no indexed test reaches the change; run the suite", Scope.affectedPath(cx, at(rt, dir, "a"), "", 3))
        }
}
