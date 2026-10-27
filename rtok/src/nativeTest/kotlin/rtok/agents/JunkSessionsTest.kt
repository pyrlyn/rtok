// Port of src/agents/junk_sessions.rs tests.
package rtok.agents

import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.symlink
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86_400L
private const val MINUTE = 60L
private const val ID = "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0"
private const val THRESHOLD = 30
private const val OLD = 31L
private const val YOUNG = 29L
private const val ANCIENT = 90L
private const val BUSY_MINUTES = 5L
private const val FUTURE_DAYS = 5L
private const val ALL_PARTS = 6
private const val ONE_SESSION = 3

class JunkSessionsTest {
    private fun put(
        path: String,
        ageSecs: Long,
    ) {
        putFile(path)
        ageBy(path, ageSecs)
    }

    private fun roots(home: String) = JunkRoots(home, { null })

    private fun items(
        home: String,
        days: Int,
    ) = sessionItems(roots(home), days, JunkCtx(home, JunkCache.DEFAULT_IDLE, nowNanos()), LIMIT)

    /** One session [age] days old: transcript, subagent folder, restore points. */
    private fun writeSession(
        home: String,
        id: String,
        secs: Long,
    ) {
        put("$home/.claude/projects/p/$id.jsonl", secs)
        put("$home/.claude/projects/p/$id/subagents/a.jsonl", secs)
        put("$home/.claude/file-history/$id/f@v1", secs)
    }

    @Test fun anOldSessionGoesWholeAndTheHostsMemoryAndAYoungOneStay() {
        val home = tmpDir("sessions-old")
        writeSession(home, ID, OLD * DAY)
        writeSession(home, "11111111-2222-3333-4444-555555555555", YOUNG * DAY)
        put("$home/.claude/projects/p/memory/MEMORY.md", ANCIENT * DAY)
        put("$home/.claude/projects/p/notes.jsonl", ANCIENT * DAY)

        val found = items(home, THRESHOLD)

        assertEquals(ALL_PARTS, found.size, "$found")
        val (old, kept) = found.partition { it.counted }
        assertEquals(ONE_SESSION, old.size)
        assertTrue(old.all { ID in it.path && it.kind == "sessions" && it.cls == "explicit" })
        assertTrue(kept.all { it.kept == "touched within the 30-day threshold" })
        assertTrue(found.none { "memory" in it.path })
    }

    @Test fun theThresholdIsStrictAndZeroStillSparesASessionInUse() {
        val home = tmpDir("sessions-threshold")
        val near = "22222222-2222-3333-4444-555555555555"
        val busy = "33333333-2222-3333-4444-555555555555"
        writeSession(home, ID, THRESHOLD * DAY + MINUTE)
        put("$home/.claude/projects/p/$near.jsonl", THRESHOLD * DAY - MINUTE)
        put("$home/.claude/projects/p/$busy.jsonl", BUSY_MINUTES * MINUTE)

        val found = items(home, THRESHOLD)

        fun counted(id: String) = found.first { id in it.path }.counted
        assertTrue(counted(ID) && !counted(near) && !counted(busy))

        val all = items(home, 0)

        fun state(id: String) = all.first { id in it.path }
        assertTrue(state(near).counted)
        assertTrue("in use" in state(busy).kept!!)
    }

    @Test fun aFutureTimestampCountsAsJustTouchedAndALinkedSessionIsIgnored() {
        val home = tmpDir("sessions-future")
        val t = "$home/.claude/projects/p/$ID.jsonl"
        put(t, -FUTURE_DAYS * DAY)
        symlink(t, "$home/.claude/projects/p/44444444-2222-3333-4444-555555555555.jsonl")

        val found = items(home, 0)

        assertEquals(1, found.size, "$found")
        assertTrue(found[0].kept!!.startsWith("future timestamp"))
    }

    @Test fun theHostsOwnRetentionIsReadWithItsDefault() {
        val home = tmpDir("sessions-retention")
        val r = roots(home)
        assertEquals("host retention: cleanupPeriodDays = 30 (default)", retention(r, "claude"))
        createDirAll("$home/.claude")
        writeString("$home/.claude/settings.json", """{"cleanupPeriodDays":7}""")
        createDirAll("$home/.gemini")
        writeString("$home/.gemini/settings.json", """{"general":{"sessionRetention":{"maxAge":"14d"}}}""")
        assertEquals("host retention: cleanupPeriodDays = 7", retention(r, "claude"))
        assertEquals("host retention: general.sessionRetention.maxAge = 14d", retention(r, "gemini"))
        assertNull(retention(r, "codex"))
    }

    @Test fun aSessionIdIsAUuid() {
        assertTrue(JunkSessions.isSessionId(ID))
        assertTrue(!JunkSessions.isSessionId("notes") && !JunkSessions.isSessionId(ID.replace('-', 'x')))
    }
}
