package rtok.plugins.graph

import kotlin.test.*
import rtok.plugin.Runtime
import rtok.store.Origin
import rtok.store.registerProject
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class ProjectsTest {
    private fun project(tag: String, body: (Runtime, String) -> Unit) = withRuntime(tag) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try {
            createDirAll("$dir/proj"); writeString("$dir/proj/lib.rs", "pub fn alpha() {}\npub fn beta() {}\n")
            rt.store.registerProject("$dir/proj", Origin.Manual)
            body(rt, "$dir/proj")
        } finally { removeDirAll(dir) }
    }

    private fun only(rt: Runtime) = Projects.rows(rt)[0]

    private fun waitDone(root: String) {
        repeat(3000) { if (Jobs.get(root) != Job.Running) return; platform.posix.usleep(20_000u) }
        fail("the index worker never finished")
    }

    @Test fun nothingRegisteredSaysHowToAdd() {
        assertTrue(Projects.render(emptyList(), false).contains("graph projects add"))
        assertEquals("[]\n", Projects.render(emptyList(), true))
    }

    @Test fun resolvePrefersAKnownIdThenAPath() = project("gp-resolve") { rt, root ->
        val id = only(rt).id
        assertEquals(id, Projects.resolve(rt.store, id.toString()).id)
        assertEquals(id, Projects.resolve(rt.store, root).id)
        assertFails { Projects.resolve(rt.store, "9999") }
    }

    @Test fun theRowShowsIndexingThenFailedThenClears() = project("t32954-states") { rt, _ ->
        val key = only(rt).root
        assertEquals("not indexed", only(rt).state)
        Projects.run(rt, ProjectAction.Reindex("1"), false)
        val before = only(rt).index!!.rows; assertTrue(before >= 2)
        Jobs.put(key, Job.Running)
        assertEquals("indexing", only(rt).state); assertEquals(before, only(rt).index!!.rows)
        Jobs.put(key, null); assertEquals("ok", only(rt).state)
        Jobs.put(key, Job.Running)
        Jobs.finish(key, Result.failure(IllegalStateException("disk full")))
        assertEquals("failed" to "disk full", only(rt).state to only(rt).error)
        assertTrue(Projects.render(Projects.rows(rt), true).contains("\"error\": \"disk full\""))
        Jobs.finish(key, Result.success(""))
        assertEquals("ok", only(rt).state); assertFalse(Projects.render(Projects.rows(rt), true).contains("\"error\""))
    }

    @Test fun startIndexesOnAWorker() {
        rtok.support.installDb()
        val dir = canonicalize(tmpDir("t32954-worker"))!!
        val rt = Runtime.open(rtok.config.Config().rebasePaths("$dir/home"), "t32954-worker")
        try {
            createDirAll("$dir/proj"); writeString("$dir/proj/lib.rs", "pub fn alpha() {}\npub fn beta() {}\n")
            rt.store.registerProject("$dir/proj", Origin.Manual)
            assertTrue(Jobs.start(rt, "1").startsWith("indexing "))
            waitDone(only(rt).root)
            val r = only(rt); assertEquals("ok", r.state, "$r"); assertTrue(r.index!!.rows >= 2)
        } finally { rt.close(); removeDirAll(dir) }
    }

    @Test fun aSecondStartWhileRunningAndAMissingRootAreRefused() = project("t32954-refuse") { rt, root ->
        val key = only(rt).root
        Jobs.put(key, Job.Running)
        assertTrue(assertFails { Jobs.start(rt, "1") }.message!!.contains("already being indexed"))
        Jobs.put(key, null)
        removeDirAll(root)
        assertTrue(assertFails { Jobs.start(rt, "1") }.message!!.contains("no longer exists"))
        assertNull(Jobs.get(key))
        assertEquals("missing", only(rt).state)
    }

    @Test fun tableListsTheProjectAndRemoveHasADryRun() = project("gp-table") { rt, root ->
        val text = Projects.run(rt, ProjectAction.List, false)
        assertTrue(text.lines()[0].startsWith("  id name origin state"), text)
        assertTrue(text.lines()[1].endsWith(root) && text.lines()[1].contains("manual"), text)
        assertTrue(Projects.preview(rt, ProjectAction.Remove("1"), false).startsWith("would remove $root (id 1)\n"))
        assertFails { Projects.preview(rt, ProjectAction.List, false) }
        assertEquals("removed $root (index rows dropped, files untouched)\n", Projects.run(rt, ProjectAction.Remove("1"), false))
        assertTrue(Projects.rows(rt).isEmpty())
    }
}
