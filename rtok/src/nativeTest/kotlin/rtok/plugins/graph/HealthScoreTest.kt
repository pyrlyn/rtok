package rtok.plugins.graph

import kotlin.test.*
import rtok.plugin.Runtime
import rtok.plugin.sdk.Ctx
import rtok.store.LinkKind
import rtok.store.Origin
import rtok.store.Project
import rtok.store.linkProjects
import rtok.store.registerProject
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class HealthScoreTest {
    private fun scope(tag: String, body: (Runtime, String) -> Unit) = withRuntime(tag) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try { body(rt, dir) } finally { removeDirAll(dir) }
    }

    private fun project(rt: Runtime, dir: String, name: String, files: Int, index: Boolean = true): Project {
        val root = "$dir/$name"; createDirAll(root)
        for (i in 0 until files) writeString("$root/f$i.rs", "pub fn ${name}_$i() {}\n")
        val p = rt.store.registerProject(root, Origin.Manual)
        if (index) Index.run(Ctx(rt), p.root, false)
        return p
    }

    private fun scored(rt: Runtime, p: Project) = Score.of(rt, p, Status.collect(Ctx(rt), p.root))

    @Test fun anIndexedProjectWithIntactLinksScores100() = scope("t32919-full") { rt, dir ->
        val a = project(rt, dir, "a", 4); val b = project(rt, dir, "b", 1)
        rt.store.linkProjects(a.id, b.id, LinkKind.Manual, null)
        val s = scored(rt, a)
        assertEquals(100 to Level.Good, s.score to s.level, "$s"); assertTrue(s.reasons.isEmpty())
    }

    @Test fun thirtyPercentPendingDropsBelow80WithTheReasonAndFix() = scope("t32919-pending") { rt, dir ->
        val a = project(rt, dir, "a", 10)
        for (i in 0 until 3) writeString("${a.root}/f$i.rs", "pub fn changed_to_something_longer() {}\n")
        val s = scored(rt, a)
        assertEquals(0.0, s.components.freshness); assertEquals(60 to Level.Warn, s.score to s.level, "$s")
        assertEquals("3 files pending (30%)", s.reasons[0].text); assertTrue(s.reasons[0].fix.startsWith("run `rtok graph index "))
    }

    @Test fun aBrokenLinkLowersTheLinksComponent() = scope("t32919-link") { rt, dir ->
        val a = project(rt, dir, "a", 2); val b = project(rt, dir, "b", 1); val c = project(rt, dir, "c", 1)
        rt.store.linkProjects(a.id, b.id, LinkKind.Manual, null); rt.store.linkProjects(a.id, c.id, LinkKind.Manual, null)
        removeDirAll(b.root)
        val s = scored(rt, a)
        assertEquals(0.5 to 85, s.components.links to s.score, "$s")
        assertTrue(s.reasons[0].text.startsWith("link to b broken"), "$s"); assertTrue(s.reasons[0].fix.contains("projects unlink"))
    }

    @Test fun aMissingProjectScores0AndAFirstIndexHasNoScoreYet() = scope("t32919-missing") { rt, dir ->
        val a = project(rt, dir, "a", 1); removeDirAll(a.root)
        val m = scored(rt, a)
        assertEquals(0 to Level.Missing, m.score to m.level); assertTrue(m.reasons[0].fix.contains("projects remove"))
        val b = project(rt, dir, "b", 1, index = false)
        val s = scored(rt, b)
        assertEquals(null to Level.Indexing, s.score to s.level); assertTrue(s.reasons[0].text.startsWith("indexing"))
    }

    @Test fun reachWalksLinksAndKeepsAMissingProject() = scope("t32917-reach") { rt, dir ->
        val a = project(rt, dir, "a", 1, false); val b = project(rt, dir, "b", 1, false); val c = project(rt, dir, "c", 1, false)
        rt.store.linkProjects(a.id, b.id, LinkKind.Manual, null); rt.store.linkProjects(b.id, c.id, LinkKind.Manual, null)
        removeDirAll(c.root)
        val r = Alerts.reach(rt.store, a)
        assertEquals(listOf(listOf("a"), listOf("a", "b"), listOf("a", "b", "c")), r.map { it.chain })
    }

    @Test fun alertsGroupByKindAndTheNoticeNamesTheEffect() = scope("t32917-notice") { rt, dir ->
        val a = project(rt, dir, "a", 1); val b = project(rt, dir, "b", 1)
        rt.store.linkProjects(a.id, b.id, LinkKind.Manual, null)
        val cx = Ctx(rt)
        Alerts.store(cx, b.root, listOf(Alert(AlertKind.Missing, "b", b.root, 0, "directory does not exist")))
        assertEquals(1, Alerts.mirrored(cx, b.root).size)
        val t = Alerts.texts(Alerts.all(rt))
        assertEquals(1, t.size); assertTrue(t[0].startsWith("b missing since ") && t[0].endsWith("(directory does not exist)"), t[0])
        val n = Alerts.notice(rt, a.id.toString(), dir)!!
        assertTrue(n.startsWith("notice: b missing since") && n.contains("; results exclude b\n"), n)
        assertEquals(1, n.lines().count { it.isNotEmpty() }, n)
        val two = Alerts.describe(AlertKind.Missing, listOf(Alert(AlertKind.Missing, "x", "/x", 0, "") to emptyList(), Alert(AlertKind.Missing, "y", "/y", 0, "") to emptyList()), false)
        assertTrue(two.startsWith("x, y missing since") && two.endsWith("(2 projects)"), two)
    }
}
