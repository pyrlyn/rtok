// Port of the tests of src/tui/projects.rs (T476, T477), health.rs (T481) and drill.rs (T561): the Graph tab's keys
// write what `rtok graph projects` writes, on a registry in a temp home; nothing is written before `y`.
@file:Suppress("MagicNumber", "TooManyFunctions")

package rtok.tui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.config.Config
import rtok.model.GraphPage
import rtok.plugin.Runtime
import rtok.plugins.graph.Job
import rtok.plugins.graph.Jobs
import rtok.plugins.graph.ProjectAction
import rtok.plugins.graph.Projects
import rtok.store.Origin
import rtok.store.registerProject
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.sys.canonicalize
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.removeDirAll
import rtok.sys.writeString
import tui.Buffer
import tui.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

private const val COLS = 140
private const val ROWS = 30

class TuiProjectsTest {
    private class Fixture(
        val dir: String,
        val cfg: Config,
        val roots: List<String>,
        val app: TuiApp,
    )

    private fun config(dir: String): Config {
        installDb()
        return Config().rebasePaths(dir).also { it.home = dir }
    }

    /** A registry of alpha (1), beta (2) and gamma (3) on a temp home, the app on its Graph tab. */
    private fun fixture(
        tag: String,
        body: (Fixture) -> Unit,
    ) {
        val dir = canonicalize(tmpDir("tui-graph-$tag"))!!
        try {
            val roots = listOf("alpha", "beta", "gamma").map { "$dir/roots/$it".also(::createDirAll) }
            val cfg = config("$dir/home")
            register(cfg, roots)
            val app = TuiApp("graph", TuiActions(cfg))
            read(app, cfg)
            body(Fixture(dir, cfg, roots, app))
        } finally {
            removeDirAll(dir)
        }
    }

    private fun register(
        cfg: Config,
        roots: List<String>,
    ) = Runtime.open(cfg, "t476-fixture").use { rt -> roots.forEach { rt.store.registerProject(it, Origin.Manual) } }

    /** The loop's re-read: the rows and the page text the snapshot carries. */
    private fun read(
        app: TuiApp,
        cfg: Config,
    ) {
        app.snapshot =
            JsonObject(mapOf("projects" to GraphPage.projectsJson(cfg), "graph" to JsonPrimitive("index health text")))
    }

    /** Selection and links per project id: what the registry holds, minus timestamps. */
    private fun state(cfg: Config) = Runtime.open(cfg, "t476-state").use { rt -> Projects.rows(rt).map { Triple(it.id, it.selected, it.links.map { l -> l.to }) } }

    private fun screen(a: TuiApp) = Buffer(COLS, ROWS).also { draw(it, a, { "00:00:00" }) }.lines().joinToString("\n")

    private fun press(
        f: Fixture,
        keys: String,
    ) {
        for (c in keys) {
            val k =
                when (c) {
                    '^' -> Key.Up
                    'v' -> Key.Down
                    '\n' -> Key.Enter
                    '\u001b' -> Key.Esc
                    else -> Key.Char(c.toString())
                }
            val e = f.app.key(k)
            assertTrue(e != Effect.QUIT, "$c must not quit")
            if (e == Effect.REFRESH) read(f.app, f.cfg)
        }
    }

    @Test fun keysWriteWhatTheCliCommandsWrite() =
        fixture("write") { f ->
            val before = state(f.cfg)
            press(f, "vs")
            assertTrue("select beta" in screen(f.app), "the plan comes first")
            assertEquals(before, state(f.cfg), "nothing is written before y")
            press(f, "y")
            assertTrue("select beta: done" in screen(f.app), screen(f.app))

            // gamma is the second candidate once alpha is first; both ways, then confirm.
            press(f, "lvb\n")
            val plan = screen(f.app)
            assertTrue("link beta <-> gamma" in plan, plan)
            assertTrue("gamma was never indexed" in plan, plan)
            press(f, "y")
            assertTrue("linked beta -> gamma" in screen(f.app), screen(f.app))

            // Only beta's link to gamma goes; gamma's link back stays.
            press(f, "u\ny")
            assertTrue("unlinked beta -> gamma" in screen(f.app), screen(f.app))

            // The same three actions through the function `rtok graph projects` runs.
            val other = config("${f.dir}/other")
            register(other, f.roots)
            Runtime.open(other, "t476-cli").use { rt ->
                Projects.run(rt, ProjectAction.Select("2"), false)
                Projects.run(rt, ProjectAction.Link("3", "2", true, null), false)
                Projects.run(rt, ProjectAction.Unlink("3", "2", false), false)
            }
            assertEquals(state(other), state(f.cfg))
            assertEquals(listOf(Triple(1, false, emptyList()), Triple(2, true, emptyList()), Triple(3, false, listOf(2))), state(f.cfg))
        }

    @Test fun decliningWritesNothing() =
        fixture("decline") { f ->
            val before = state(f.cfg)
            press(f, "sn")
            assertTrue("declined; nothing written" in screen(f.app))
            press(f, "lvb\n\u001b")
            assertTrue("declined" in screen(f.app))
            press(f, "ls")
            assertTrue("link alpha -> beta" in screen(f.app), "s is a picker key while a target is chosen")
            press(f, "\u001bu")
            assertTrue("alpha has no links" in screen(f.app))
            press(f, "sq")
            assertEquals(before, state(f.cfg))
        }

    @Test fun aRefusedWriteShowsTheReasonOnTheStatusLine() =
        fixture("refused") { f ->
            removeDirAll(f.roots[0])
            read(f.app, f.cfg)
            val before = state(f.cfg)
            press(f, "sy")
            assertTrue("no longer exists" in screen(f.app), screen(f.app))
            assertEquals(before, state(f.cfg))
        }

    @Test fun reindexRunsOnAWorkerAndReportsItsEnd() =
        fixture("reindex") { f ->
            writeString("${f.roots[0]}/lib.rs", "pub fn one() {}\npub fn two() {}\n")
            assertTrue("not indexed" in screen(f.app))
            press(f, "i")
            assertFalse("declined" in screen(f.app), "a re-index asks for no confirmation")
            val root = projectEntries(f.app.snapshot)[0].root
            repeat(3000) {
                if (Jobs.get(root) != Job.Running) return@repeat
                platform.posix.usleep(20_000u)
            }
            read(f.app, f.cfg)
            val shown = screen(f.app)
            if ("indexed alpha" !in shown) fail(shown)
            assertTrue((projectEntries(f.app.snapshot)[0].index?.rows ?: 0) >= 2)
        }

    @Test fun removeShowsThePlanAndOnlyTheConfirmKeyDropsTheProject() =
        fixture("remove") { f ->
            writeString("${f.roots[0]}/lib.rs", "pub fn one() {}\n")
            val before = state(f.cfg)
            press(f, "x")
            val plan = screen(f.app)
            assertTrue("would remove" in plan && "files untouched" in plan, plan)
            assertEquals(before, state(f.cfg), "nothing is removed before y")
            press(f, "n")
            assertTrue("declined; nothing written" in screen(f.app))
            press(f, "xy")
            assertTrue("removed" in screen(f.app), screen(f.app))
            assertEquals(listOf(2, 3), state(f.cfg).map { it.first })
            assertTrue(exists("${f.roots[0]}/lib.rs"), "no file is deleted")
        }

    @Test fun healthAndDrillShowUnderTheTable() =
        fixture("drill") { f ->
            val s = screen(f.app)
            assertTrue("health" in s && "scope" in s && "alpha indexing" in s, s)
            assertTrue("index health text" in s, "the page text stays below: $s")
            press(f, "d")
            assertTrue("alpha: not indexed" in screen(f.app), screen(f.app))
            assertEquals(1, f.app.drill.project)
            press(f, "d")
            assertEquals(null, f.app.drill.project, "d on the open project closes the view")
        }
}
