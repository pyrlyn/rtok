package rtok.plugins.graph

import kotlin.concurrent.AtomicInt
import kotlin.test.*
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.LinkKind
import rtok.store.Origin
import rtok.store.Project
import rtok.store.linkProjects
import rtok.store.projects
import rtok.store.registerProject
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class HealthCheckTest {
    private val ok: Probe = { null }

    /** `a` → `b` → `c`, each with one source file. */
    private fun world(tag: String, body: (Runtime, String, List<Project>) -> Unit) = withRuntime(tag) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try {
            val ps = listOf("a", "b", "c").map { n -> createDirAll("$dir/$n"); writeString("$dir/$n/lib.rs", "pub fn ${n}_one() {}\n"); rt.store.registerProject("$dir/$n", Origin.Manual) }
            rt.store.linkProjects(ps[0].id, ps[1].id, LinkKind.Manual, null); rt.store.linkProjects(ps[1].id, ps[2].id, LinkKind.Manual, null)
            body(rt, dir, ps)
        } finally { removeDirAll(dir) }
    }

    private fun alertsOf(rt: Runtime, p: Project) = Alerts.mirrored(Ctx(rt), p.root)
    private fun mv(a: String, b: String) = check(platform.posix.rename(a, b) == 0)

    @Test fun aRenamedDirectoryAlertsAfterTwoChecksAndClearsAfterTwo() = world("t32917-missing") { rt, dir, ps ->
        val (a, b) = ps
        val h = Health(); fun scope() = Health.scopeOf(rt, a.root)
        h.tick(rt, scope(), ok, 0)
        mv("$dir/b", "$dir/b.moved")
        h.tick(rt, scope(), ok, 60); assertTrue(alertsOf(rt, b).isEmpty(), "one check is not an alert")
        h.tick(rt, scope(), ok, 120)
        val raised = alertsOf(rt, b)
        assertEquals(listOf(AlertKind.Missing to 120L), raised.map { it.kind to it.since })
        assertTrue(Alerts.texts(Alerts.all(rt)).joinToString("\n").startsWith("b missing since "))
        val note = Alerts.notice(rt, null, a.root)!!
        assertTrue(note.startsWith("notice: b missing since ") && note.contains("; results exclude b\n"), note)
        val before = Ctx(rt).symbolCount(Index.canon(b.root))
        mv("$dir/b.moved", "$dir/b"); writeString("$dir/b/new.rs", "pub fn added_while_away() {}\n")
        h.tick(rt, scope(), ok, 180); assertEquals(1, alertsOf(rt, b).size, "one good check does not clear")
        h.tick(rt, scope(), ok, 240); assertTrue(alertsOf(rt, b).isEmpty())
        assertFalse(Alerts.notice(rt, null, a.root).orEmpty().contains("missing"))
        assertTrue(Ctx(rt).symbolCount(Index.canon(b.root)) > before, "recovery re-indexes")
    }

    @Test fun aBlipOfOneCheckNeverAlerts() = world("t32917-blip") { rt, dir, ps ->
        val h = Health()
        mv("$dir/b", "$dir/b.moved"); h.tick(rt, Health.scopeOf(rt, ps[0].root), ok, 0)
        mv("$dir/b.moved", "$dir/b")
        for (t in 1..3) h.tick(rt, Health.scopeOf(rt, ps[0].root), ok, t * 60L)
        assertTrue(Alerts.all(rt).isEmpty())
    }

    @Test fun severalAtOnceAreOneAlertAndAFarProjectNamesTheChain() = world("t32917-group") { rt, dir, ps ->
        val h = Health()
        mv("$dir/b", "$dir/b.moved"); mv("$dir/c", "$dir/c.moved")
        for (t in 0..1) h.tick(rt, rt.store.projects(), ok, t * 60L)
        val shown = Alerts.texts(Alerts.all(rt))
        assertEquals(1, shown.size, "$shown"); assertTrue(shown[0].startsWith("b, c missing since ") && shown[0].endsWith("(2 projects)"), "$shown")
        val note = Alerts.notice(rt, null, ps[0].root)!!
        assertEquals(1, note.lines().count { it.isNotEmpty() }, note); assertTrue(note.contains("; results exclude b, c"), note)
        mv("$dir/b.moved", "$dir/b")
        for (t in 2..3) h.tick(rt, rt.store.projects(), ok, t * 60L)
        assertTrue(Alerts.notice(rt, null, ps[0].root)!!.startsWith("notice: a to b to c: c missing since "))
    }

    @Test fun aVanishedParentIsUnreachableNotMissing() = withRuntime("t32917-unreachable") { rt ->
        val dir = canonicalize(tmpDir("t32917-unreachable"))!!
        createDirAll("$dir/disk/proj"); rt.store.registerProject("$dir/disk/proj", Origin.Manual)
        removeDirAll("$dir/disk")
        val h = Health()
        for (t in 0..1) h.tick(rt, rt.store.projects(), ok, t * 60L)
        assertEquals(AlertKind.Unreachable, Alerts.all(rt)[0].kind)
        removeDirAll(dir)
    }

    @Test fun aManifestPathThatPointsNowhereIsABrokenLink() = world("t32917-links") { rt, _, ps ->
        val h = Health { root -> if (root == ps[0].root) listOf("Cargo.toml: ../nowhere, not found", "other warning") else emptyList() }
        for (t in 0..1) h.tick(rt, listOf(ps[0]), ok, t * 60L)
        val a = alertsOf(rt, ps[0])
        assertEquals(AlertKind.LinkBroken, a.single().kind); assertEquals("Cargo.toml: ../nowhere, not found", a[0].detail)
    }

    @Test fun theLoopStopsAndAlertsOffMeansNoLoop() = world("t32917-loop") { rt, _, ps ->
        val stop = AtomicInt(1)
        Health.runEvery(rt, ps[0].root, 10, ok, stop) // returns at once when stopped
        rt.config = rt.config.copy(plugins = rt.config.plugins.copy(graph = rt.config.plugins.graph.copy(alerts = false)))
        Health.run(rt, ps[0].root, AtomicInt(0), ok) // alerts off: returns without looping
        assertNull(Alerts.notice(rt, null, ps[0].root))
    }
}
