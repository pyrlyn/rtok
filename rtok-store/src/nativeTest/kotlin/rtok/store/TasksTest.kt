package rtok.store

import kotlin.test.*

/** Port of task_claims.rs + task_counters.rs tests. */
class TasksTest {
    @Test fun theNewestClaimIsTheOneAHookReads() {
        val s = Store.openInMemory()
        s.upsertTaskClaim("p", "R1", "agent", "First"); s.upsertTaskClaim("p", "R2", "agent", "Second\nline"); s.upsertTaskClaim("p", "R1", "agent", "First again")
        val latest = s.latestTaskClaim("p", "agent")!!; assertEquals("R1", latest.taskId); assertEquals("First again", latest.title)
        assertNull(s.latestTaskClaim("p", "other"))
        s.clearTaskClaim("p", "R1"); assertEquals("R2", s.latestTaskClaim("p", "agent")!!.taskId); s.clearTaskClaim("p", "R2"); assertNull(s.latestTaskClaim("p", "agent"))
    }

    @Test fun claimsInOneSecondResolveByClaimOrder() {
        val s = Store.openInMemory()
        fun latest(agent: String) = s.latestTaskClaim("p", agent)?.let { it.taskId to it.title }
        fun claim(id: String, title: String) = s.upsertTaskClaim("p", id, "agent", title)
        claim("R1", "one"); claim("R2", "two"); assertEquals("R2" to "two", latest("agent"))
        claim("R1", "one again"); assertEquals("R1" to "one again", latest("agent"))
        claim("R2", "two again"); assertEquals("R2" to "two again", latest("agent"))
        claim("R9", "nine"); claim("R3", "three"); assertEquals("R3" to "three", latest("agent"))
        s.clearTaskClaim("p", "R3"); assertEquals("R9", latest("agent")!!.first)
        s.upsertTaskClaim("q", "R100", "agent", "elsewhere"); s.upsertTaskClaim("p", "R0", "someone", "theirs")
        assertEquals("R9", latest("agent")!!.first); assertEquals("R0", latest("someone")!!.first)
    }

    private fun id(s: String) = TaskId.parse(s)

    @Test fun idsCountUpPerProjectAndPerParent() {
        val s = Store.openInMemory()
        fun next(project: String, parent: TaskId?) = s.allocateTaskId(project, "R", parent).toString()
        assertEquals("R1", next("a", null)); assertEquals("R2", next("a", null)); assertEquals("R1", next("b", null))
        assertEquals("R2.1", next("a", id("R2"))); assertEquals("R2.2", next("a", id("R2"))); assertEquals("R1.1", next("a", id("R1"))); assertEquals("R3", next("a", null))
    }

    @Test fun aNewPrefixKeepsTheNumbering() { val s = Store.openInMemory(); s.allocateTaskId("a", "R", null); assertEquals("AT2", s.allocateTaskId("a", "at", null).toString()) }

    @Test fun refusalsBurnNoNumber() {
        val s = Store.openInMemory()
        assertFails { s.allocateTaskId("a", "R2", null) }; assertFails("depth") { s.allocateTaskId("a", "R", id("R1.1")) }
        val err = assertFails { s.allocateTaskId("a", "R", id("R1")) }; assertTrue(err.message!!.contains("never allocated"), err.message)
        assertEquals("R1", s.allocateTaskId("a", "R", null).toString())
    }

    @Test fun seedingOnlyRaisesTheCounter() {
        val s = Store.openInMemory()
        assertEquals(40u, s.seedTaskCounter("a", null, 40u)); assertEquals(40u, s.seedTaskCounter("a", null, 7u)); assertEquals("R41", s.allocateTaskId("a", "R", null).toString())
        s.seedTaskCounter("a", id("R41"), 3u); assertEquals("R41.4", s.allocateTaskId("a", "R", id("R41")).toString())
    }

    @Test fun taskIdsParseAndPrint() {
        assertEquals("AT1.2.3", id(" at1.2.3 ").toString()); assertEquals(id("R1"), id("R1.1").parent()); assertNull(id("R1").parent())
        for (bad in listOf("R", "R0", "R01", "R1..2", "R1.2.3.4", "1", "R-1")) assertFails(bad) { id(bad) }
        assertTrue(id("R2") < id("R10")); assertTrue(id("R2") < id("R2.1"))
    }
}
