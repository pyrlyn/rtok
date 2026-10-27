@file:Suppress("MagicNumber")
@file:OptIn(kotlin.native.concurrent.ObsoleteWorkersApi::class)

// Port of the src/tasks/run.rs tests (the GitLab origin test waits for the gitlab adapter).

package rtok.tasks

import rtok.config.Tasks
import rtok.config.Validate
import rtok.store.Store
import rtok.store.TaskId
import rtok.store.hostId
import rtok.store.latestTaskClaim
import rtok.store.registerAgent
import rtok.store.setAgentLastSeen
import rtok.support.installDb
import rtok.support.tmpDir
import rtok.support.worktreeLayout
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.listDir
import rtok.sys.readBytes
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.writeString
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private fun id(s: String) = TaskId.parse(s)

private fun new(
    title: String,
    parent: String? = null,
) = NewTask(title, "", parent?.let(::id))

private fun checkout(name: String): String {
    val dir = tmpDir("taskrun-$name")
    createDirAll("$dir/.git")
    writeString("$dir/.git/config", "[remote \"origin\"]\n\turl = git@github.com:me/airtalk.git\n")
    return dir
}

private fun project(path: String) = TaskProject("proj", path, "R", DiskAdapter(path))

private fun store(): Store {
    installDb()
    return Store.openInMemory()
}

private fun agent(
    store: Store,
    session: String,
    seen: Long,
): String {
    val id = store.registerAgent(store.hostId("claude")!!, session, null, null, null)
    store.setAgentLastSeen(id, seen)
    return id
}

private fun fileOf(
    dir: String,
    start: String,
) = "$dir/" + listDir(dir).first { it.startsWith(start) && it.endsWith(".md") }

private fun claimJob(job: Triple<String, Store, String>): Result<ClaimOutcome> = runCatching { project(job.first).claim(job.second, id("R1"), job.third) }

class TaskProjectTest {
    @Test fun createNumbersPastTasksAlreadyOnDiskAndNextPicksALeaf() {
        val dir = checkout("create")
        val store = store()
        val p = TaskProject.open(Tasks(), dir)
        assertEquals("A", p.prefix)
        assertEquals("github.com/me/airtalk", p.key)
        // A task that came in with a pull: the counter never saw it.
        p.adapter.create(new("Pulled"), id("A7"))
        assertEquals("A8", p.create(store, new("First")).id.toString())
        val sub = p.create(store, new("Leaf", "A7"))
        assertEquals("A7.1", sub.id.toString())
        assertFailsWith<TaskException> { p.create(store, new("Orphan", "A99")) }
        assertEquals("A7.1", p.next(store)!!.id.toString())
        val shown = p.show(id("A7"))
        assertEquals(listOf(sub.id), shown.subtasks)
        assertTrue("subtasks: A7.1" in taskDetails(shown))
        assertEquals("A7      open         Pulled\n  A7.1  open         Leaf\nA8      open         First\n", taskTable(p.adapter.list(Filter())))
        removeDirAll(dir)
    }

    @Test fun aLinkedWorktreeKeepsItsOwnFilesAndSharesTheProjectKey() {
        val dir = tmpDir("t660-tasks")
        val (main, wt) = worktreeLayout(dir)
        val fromMain = TaskProject.open(Tasks(), main)
        val fromWt = TaskProject.open(Tasks(), wt)
        assertEquals(wt, fromWt.root)
        assertEquals(main, fromMain.root)
        assertEquals(fromMain.key, fromWt.key)
        assertEquals("$wt/.rtok.toml", initTasks(wt, null, null))
        assertFalse(exists("$main/.rtok.toml"))
        removeDirAll(dir)
    }

    @Test fun initWritesTasksAndKeepsTheRestOfTheFile() {
        val dir = checkout("init")
        writeString("$dir/.rtok.toml", "# mine\n[proxy]\nport = 9999\n")
        val path = initTasks(dir, null, "at")
        var text = readToString(path)
        assertTrue(text.startsWith("# mine\n[proxy]\nport = 9999\n"), text)
        assertTrue("[tasks]\nadapter = \"disk\"\nprefix = \"AT\"\n" in text, text)
        initTasks(dir, "github", null)
        text = readToString(path)
        assertTrue("adapter = \"github\"" in text && "prefix = \"AT\"" in text, text)
        assertFailsWith<TaskException> { initTasks(dir, "jira", null) }
        assertFailsWith<Exception> { initTasks(dir, null, "A1") }
        assertEquals(emptyList(), Validate.issues(path))
        removeDirAll(dir)
    }

    @Test fun twoClaimsOneWinnerUntilTheHolderIsStale() {
        val path = tmpDir("claim-race")
        val store = store()
        val now = nowSecs()
        val a = agent(store, "a", now)
        val b = agent(store, "b", now)
        project(path).adapter.create(new("Ship"), id("R1"))
        val workers = listOf(Worker.start(), Worker.start())
        val futures =
            listOf(a, b).mapIndexed { i, who ->
                workers[i].execute(TransferMode.SAFE, { Triple(path, store, who) }, ::claimJob)
            }
        val results = futures.map { it.result }
        workers.forEach { it.requestTermination().result }
        val ok = results.mapNotNull { it.getOrNull() }
        val errs = results.mapNotNull { it.exceptionOrNull() }
        assertEquals(1, ok.size, "one claim and one conflict: $results")
        assertTrue(ok[0].changed, "the winner rewrote the file")
        assertTrue(errs[0] is ClaimConflict, errs[0].toString())
        val holder = ok[0].task.assignee!!
        assertTrue(holder == a || holder == b, holder)
        val file = fileOf(path, "R1")
        val before = readBytes(file)
        assertFalse(project(path).claim(store, id("R1"), holder).changed)
        assertContentEquals(before, readBytes(file), "a re-claim does not rewrite the file")
        store.setAgentLastSeen(holder, now - STALE_SECS - 5)
        val other = if (holder == a) b else a
        val taken = project(path).claim(store, id("R1"), other)
        assertTrue(taken.changed, "a stale claim can be taken")
        assertEquals(other, taken.task.assignee)
        removeDirAll(path)
    }

    /** An adapter that implements only what the interface requires: it stores no markers. */
    private class Bare(
        val inner: DiskAdapter,
    ) : TaskAdapter {
        override val name = "bare"

        override fun create(
            task: NewTask,
            id: TaskId,
        ) = inner.create(task, id)

        override fun list(filter: Filter) = inner.list(filter)

        override fun get(id: TaskId) = inner.get(id)

        override fun writeStatus(
            id: TaskId,
            status: Status,
        ) = inner.writeStatus(id, status)

        override fun maxId(prefix: String) = inner.maxId(prefix)
    }

    @Test fun markersAreSymmetricIdempotentAndRefusedWhereTheyCannotBeKept() {
        val path = tmpDir("claim-markers")
        val p = project(path)
        for (n in listOf("R1", "R2")) p.adapter.create(new(n), id(n))
        assertEquals(listOf(id("R2")), p.conflict(id("R1"), id("R2"), false).conflictsWith)
        assertEquals(listOf(id("R1")), p.get(id("R2")).conflictsWith)
        val file = fileOf(path, "R1 ")
        var before = readBytes(file)
        p.conflict(id("R2"), id("R1"), false)
        assertContentEquals(before, readBytes(file), "an edge already there is not rewritten")
        assertFailsWith<TaskException> { p.conflict(id("R1"), id("R1"), false) }
        assertFailsWith<TaskException> { p.conflict(id("R1"), id("R9"), false) }
        assertTrue(p.setParallel(id("R1"), true).parallel)
        before = readBytes(file)
        p.setParallel(id("R1"), true)
        assertContentEquals(before, readBytes(file))
        assertFalse(p.setParallel(id("R1"), false).parallel)
        assertTrue(p.conflict(id("R1"), id("R2"), true).conflictsWith.isEmpty())
        assertTrue(p.get(id("R2")).conflictsWith.isEmpty())

        val bare = TaskProject("proj", path, "R", Bare(DiskAdapter(path)))
        val err = assertFailsWith<TaskException> { bare.conflict(id("R1"), id("R2"), false) }
        assertTrue("bare tasks cannot store" in err.message!!, err.message)
        assertFailsWith<TaskException> { bare.setParallel(id("R1"), true) }
        assertTrue(bare.get(id("R2")).conflictsWith.isEmpty())

        // A peer whose file is gone leaves a stale edge that `--remove` must still clear.
        p.conflict(id("R1"), id("R2"), false)
        removeFile("$path/R2 - r2.md")
        assertFailsWith<TaskException> { p.conflict(id("R1"), id("R2"), false) }
        assertTrue(p.conflict(id("R1"), id("R2"), true).conflictsWith.isEmpty())
        assertTrue(p.get(id("R1")).conflictsWith.isEmpty())
        removeDirAll(path)
    }

    @Test fun aMissingAgentRowCanBeTakenAndABlockWritesNothingOnACycle() {
        val path = tmpDir("claim-block")
        val store = store()
        val live = agent(store, "live", nowSecs())
        val p = project(path)
        for ((tid, title) in listOf("R1" to "Parent", "R1.1" to "Child", "R2" to "Other")) p.adapter.create(new(title), id(tid))
        assertTrue(p.claim(store, id("R1"), "not-registered").changed)
        assertTrue(p.claim(store, id("R1"), live).changed, "no agents row is stale")
        p.release(store, id("R1"), live, false)
        val child = fileOf(path, "R1.1")
        val before = readBytes(child)
        val err = assertFailsWith<TaskException> { p.block(id("R1.1"), id("R1")) }
        assertTrue("cycle" in err.message!!, err.message)
        assertContentEquals(before, readBytes(child), "a cycle writes nothing")
        assertFailsWith<TaskException> { p.block(id("R1"), id("R1")) }
        assertFailsWith<TaskException> { p.block(id("R1"), id("R9")) }
        p.block(id("R2"), id("R1"))
        val again = readBytes(fileOf(path, "R2 "))
        p.block(id("R2"), id("R1"))
        assertContentEquals(again, readBytes(fileOf(path, "R2 ")), "a duplicate edge is not rewritten")
        val blocked = assertFailsWith<ClaimConflict> { p.claim(store, id("R2"), live) }
        assertTrue("R2 is blocked by R1" in blocked.message!!, blocked.message)
        assertTrue(p.ready(store).all { it.task.id != id("R2") })
        assertNotEquals(id("R2"), p.claim(store, null, live).task.id)
        p.status(store, id("R1"), Status.Done, true, null)
        assertTrue(store.latestTaskClaim("proj", live)?.taskId != "R1")
        removeDirAll(path)
    }

    /** T615: the stored claim names the last one claimed, a same-agent re-claim included. */
    @Test fun theStoredClaimFollowsTheLastClaimMade() {
        val path = tmpDir("claim-order")
        val store = store()
        val live = agent(store, "live", nowSecs())
        for ((n, title) in listOf("R1" to "First", "R2" to "Second")) project(path).adapter.create(new(title), id(n))
        val latest = { store.latestTaskClaim("proj", live)!!.taskId }
        project(path).claim(store, id("R1"), live)
        project(path).claim(store, id("R2"), live)
        assertEquals("R2", latest())
        project(path).claim(store, id("R1"), live)
        assertEquals("R1", latest(), "a same-agent re-claim moves to the front")
        removeDirAll(path)
    }
}
