// Port of the tests of src/tui/compare.rs (T485): a committed project in a temp dir, compared through the Graph tab's
// `c` panel; the counts are the ones `rtok graph diff` prints for the same scope.
@file:Suppress("MagicNumber", "TooManyFunctions")

package rtok.tui

import kotlinx.serialization.json.JsonObject
import rtok.config.Config
import rtok.model.GraphPage
import rtok.plugin.Runtime
import rtok.plugins.graph.DiffQuery
import rtok.plugins.graph.ExportLevel
import rtok.plugins.graph.ExportQuery
import rtok.plugins.graph.ExportView
import rtok.plugins.graph.GraphDiffs
import rtok.plugins.graph.Scope
import rtok.store.Origin
import rtok.store.registerProject
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.sys.ProgressRunner
import rtok.sys.canonicalize
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.writeString
import tui.Buffer
import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OLD =
    "fn keep() {}\nfn changed() {\n    one();\n}\nfn gone() {\n    bye();\n}\nfn helper() {\n    work();\n}\nfn caller() {\n    changed();\n    gone();\n}\n"
private const val NEW = "fn keep() {}\nfn changed() {\n    two();\n}\nfn extra() {\n    hello();\n    world();\n}\nfn caller() {\n    changed();\n}\n"

class TuiCompareTest {
    private class Fixture(
        val dir: String,
        val cfg: Config,
        val root: String,
        val app: TuiApp,
    )

    private fun git(
        dir: String,
        vararg args: String,
    ) {
        val (c, _) =
            ProgressRunner("", "git", listOf("-C", dir, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args)
                .quiet()
                .runCapped(1L shl 20)
        check(c.exitCode == 0) { "git ${args.toList()}: ${c.stderr.decodeToString()}" }
    }

    /** A committed project `p` in a temp dir and its registry row; the working tree still equals HEAD. */
    private fun fixture(
        tag: String,
        register: Boolean = true,
        body: (Fixture) -> Unit,
    ) {
        val dir = canonicalize(tmpDir("tui-compare-$tag"))!!
        try {
            installDb()
            val cfg = Config().rebasePaths("$dir/home").also { it.home = "$dir/home" }
            val root = "$dir/p"
            createDirAll(root)
            writeString("$root/lib.rs", OLD)
            git(root, "init", "-q", "-b", "main")
            git(root, "add", "-A")
            git(root, "commit", "-q", "-m", "c")
            if (register) Runtime.open(cfg, "t485-fixture").use { it.store.registerProject(root, Origin.Manual) }
            val app = TuiApp("graph", TuiActions(cfg))
            app.snapshot = JsonObject(mapOf("projects" to GraphPage.projectsJson(cfg)))
            body(Fixture(dir, cfg, root, app))
        } finally {
            removeDirAll(dir)
        }
    }

    /** A changed (and its caller), a removed, an added and a moved symbol. */
    private fun edit(root: String) {
        writeString("$root/lib.rs", NEW)
        writeString("$root/util.rs", "fn helper() {\n    work();\n}\n")
    }

    private fun type(
        a: TuiApp,
        text: String,
    ) = text.forEach { assertTrue(a.key(Key.Char(it.toString())) != Effect.QUIT, "$it quit") }

    private fun compare(
        a: TuiApp,
        text: String,
    ) {
        type(a, "c")
        type(a, text)
        a.key(Key.Enter)
    }

    private fun screen(
        a: TuiApp,
        cols: Int,
    ) = Buffer(cols, 60).also { draw(it, a, { "00:00:00" }) }.lines().joinToString("\n")

    @Test fun theReportRendersWithMarksAndTheCliCounts() =
        fixture("report") { f ->
            edit(f.root)
            compare(f.app, "")
            val r = Runtime.open(f.cfg, "t485-report").use { GraphDiffs.pageOf(it, "1", emptyList(), null, null) }
            val d = r.projects[0]
            assertTrue(d.changed.isNotEmpty() && d.added.size == 1 && d.removed.size == 1)
            assertEquals(1, d.moved.size, "the helper moved to util.rs")
            val cli =
                Runtime.open(f.cfg, "t485-cli").use { rt ->
                    GraphDiffs.run(rt, Scope.resolve(rt.store, "1", f.root), DiffQuery(emptyList(), null, false, null))
                }
            val counts = "${d.changed.size} changed · ${d.added.size} added · ${d.removed.size} removed · ${d.renamed.size} renamed · ${d.moved.size} moved"
            assertTrue(counts in cli, cli)
            val shown = screen(f.app, 100)
            listOf(
                "p vs HEAD → working",
                "+ ${d.added.size} added",
                "− ${d.removed.size} removed",
                "~ ${d.changed.size + d.renamed.size} changed",
                "→ ${d.moved.size} moved",
                "changed (${d.changed.size})",
                "[body]",
                "called by",
                "removed (1)",
                "− gone",
                "added (1)",
                "+ extra",
                "moved (1)",
                "→ helper",
                "lib.rs → util.rs",
            ).forEach { assertTrue(it in shown, "lacks $it:\n$shown") }
        }

    @Test fun typedKeysStayInTheInputAndEscReturnsToThePage() =
        fixture("typing") { f ->
            type(f.app, "c")
            type(f.app, "r?q3")
            assertTrue("compare p with: r?q3" in screen(f.app, 100))
            assertFalse(f.app.help)
            assertEquals("graph", f.app.page)
            f.app.key(Key.Backspace)
            assertTrue("compare p with: r?q▏" in screen(f.app, 100))
            f.app.key(Key.Esc)
            val shown = screen(f.app, 260)
            assertFalse("compare p with" in shown, shown)
            assertTrue("c compare" in shown, shown)
            assertEquals(Effect.QUIT, f.app.key(Key.Esc), "with the page back, Esc is the shell's again")
        }

    @Test fun aShownReportKeepsTheShellsKeysAndHidesThePageKeys() =
        fixture("shown") { f ->
            edit(f.root)
            compare(f.app, "HEAD")
            f.app.key(Key.Char("s"))
            assertFalse("select p" in screen(f.app, 100).lines().dropLast(1).joinToString("\n"), "s would plan a select on the page behind the panel")
            f.app.key(Key.Char("?"))
            assertTrue(f.app.help)
            f.app.key(Key.Char("?"))
            listOf(Key.Down, Key.PageDown, Key.Home).forEach { f.app.key(it) }
            assertEquals(Effect.QUIT, f.app.key(Key.Char("q")))
            f.app.key(Key.Right)
            assertTrue(f.app.page != "graph")
        }

    @Test fun aRefThatDoesNotExistIsALineNotACrash() =
        fixture("noref") { f ->
            compare(f.app, "no-such-ref")
            assertTrue('✕' in screen(f.app, 100))
            type(f.app, "c")
            assertTrue("compare p with:" in screen(f.app, 100), "compare again from the error")
        }

    @Test fun aSavedExportTypedAsAPathIsTheOldSide() =
        fixture("saved") { f ->
            val text =
                Runtime.open(f.cfg, "t485-export").use { rt ->
                    ExportView.run(rt, Scope.resolve(rt.store, "1", f.root), ExportQuery(ExportLevel.Symbols, null, 2, redact = false, pretty = false))
                }
            val file = "${f.dir}/saved.json"
            writeString(file, text)
            edit(f.root)
            compare(f.app, file)
            val shown = screen(f.app, 100)
            assertTrue("added (2)" in shown && "+ extra" in shown, shown)
            assertTrue("removed (2)" in shown && "− gone" in shown, shown)
            assertTrue("against an export" in shown, shown)
            val junk = "${f.dir}/junk.json"
            writeString(junk, "{}")
            compare(f.app, junk)
            assertTrue('✕' in screen(f.app, 100), "a file that is not an export is refused with the reason")
        }

    @Test fun noProjectRowMeansNoCompare() =
        fixture("none", register = false) { f ->
            type(f.app, "c")
            assertTrue("no project to compare" in screen(f.app, 100))
        }
}
