@file:Suppress("MagicNumber")
// Port of src/demon.rs's unit tests (T20.1, T597): ownership-guarded stop, escalation, quiesce, backoff, parsing,
// the status table and the service lock.

package rtok.demon

import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.pid
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class DemonStopTest {
    private val web = listOf(Service.WEB)

    @Test fun stopNeverSignalsThePidsOfAStateFromBeforeThisBoot() {
        for ((force, boot) in listOf(false to 1000L, true to 1000L, true to null)) {
            val s = store("stale")
            s.write(state(4242, 4243, 1))
            val p = Recorder(boot, listOf(4242, 4243))
            Demon(s, p) {}.stop(web, force)
            assertTrue(p.calls.isEmpty(), "force=$force boot=$boot")
            assertFalse(exists(s.file(Service.WEB, "json")), "stale state retired")
        }
    }

    @Test fun stopWithoutAStateSignalsNothing() {
        val p = Recorder(1000, listOf(1, 2))
        val lines = mutableListOf<String>()
        Demon(store("none"), p) { lines += it }.stop(web, true)
        assertTrue(p.calls.isEmpty())
        assertEquals(listOf("web not running"), lines)
    }

    @Test fun stopSignalsOnlyTheChildWhenNoSupervisorHoldsTheLock() {
        val s = store("orphan")
        s.write(state(4242, 4243, now()))
        val p = Recorder(1000, listOf(4242, 4243))
        Demon(s, p) {}.stop(web, false)
        assertEquals(listOf("term" to 4243), p.calls)
        assertFalse(exists(s.file(Service.WEB, "json")))
        assertFalse(exists(s.file(Service.WEB, "stop")), "no supervisor to read a marker")
    }

    @Test fun stopSignalsALiveSupervisorAndItsChild() {
        for ((force, op) in listOf(false to "term", true to "kill")) {
            val s = store("live")
            s.claim(Service.WEB)!!.use {
                s.write(state(4242, 4243, 1))
                val p = Recorder(1000, listOf(4242, 4243))
                Demon(s, p) {}.stop(web, force)
                assertEquals(listOf(op to 4242, op to 4243), p.calls, "force=$force")
                assertTrue(exists(s.file(Service.WEB, "stop")))
            }
        }
        val s = store("nonpositive")
        s.claim(Service.WEB)!!.use {
            s.write(state(0, -1, now()))
            val p = Recorder(1000, emptyList())
            Demon(s, p) {}.stop(web, true)
            assertTrue(p.calls.isEmpty(), "${p.calls}")
        }
    }

    @Test fun escalationIsRevalidatedAndKeepsAReplacementState() {
        val s = store("escalate")
        s.claim(Service.WEB)!!.use {
            s.write(state(4242, 4243, now()))
            val p = Recorder(1000, listOf(4242, 4243), ignoresTerm = true)
            Demon(s, p) {}.stop(web, false)
            assertEquals(listOf("term" to 4242, "term" to 4243, "kill" to 4242, "kill" to 4243), p.calls)
            assertFalse(exists(s.file(Service.WEB, "json")))
        }
        val r = store("replace")
        r.claim(Service.WEB)!!.use {
            r.write(state(4242, 4243, now()))
            val replacement = state(5252, 5253, now() + 1)
            val p = Recorder(1000, listOf(4242, 4243), ignoresTerm = true, onPause = { r.write(replacement) })
            Demon(r, p) {}.stop(web, false)
            assertEquals(listOf("term" to 4242, "term" to 4243), p.calls, "a replaced state is not escalated")
            assertEquals(replacement, r.read(Service.WEB), "the replacement survives")
        }
    }

    @Test fun aSupervisorThatReleasedItsLockIsNotKilled() {
        val s = store("released")
        val lock = s.claim(Service.WEB)!!
        s.write(state(4242, 4243, now()))
        val p = Recorder(1000, listOf(4242, 4243), ignoresTerm = true, onPause = { lock.close() })
        Demon(s, p) {}.stop(web, false)
        assertEquals(listOf("term" to 4242, "term" to 4243, "kill" to 4243), p.calls)
    }

    @Test fun quiesceRetiresALateRespawnOfThisBoot() {
        val s = store("quiesce")
        s.write(state(4242, 4243, now()))
        val p = Recorder(1000, listOf(4242, 4243))
        val lines = mutableListOf<String>()
        Demon(s, p) { lines += it }.quiesce(web)
        assertEquals(listOf("kill" to 4243), p.calls)
        assertFalse(exists(s.file(Service.WEB, "json")))
        assertTrue(lines.single().contains("retiring pid 4243"))
    }

    @Test fun quiesceNeverKillsThePidsOfAStaleStateAndIsOkWhenNothingRespawned() {
        val s = store("quiesce-stale")
        s.write(state(4242, 4243, 1))
        val p = Recorder(1000, listOf(4242, 4243))
        Demon(s, p) {}.quiesce(web)
        assertTrue(p.calls.isEmpty())
        assertFalse(exists(s.file(Service.WEB, "json")))
        Demon(store("quiesce-empty"), p) {}.quiesce(listOf(Service.MCP, Service.WEB))
    }

    @Test fun aSurfaceThatKeepsRespawningFailsTheQuiesce() {
        val s = store("quiesce-rogue")
        val p =
            object : Procs by Recorder(1000, emptyList()) {
                override fun pause(ms: Long) = s.write(state(0, 0, 1))
            }
        s.write(state(0, 0, 1))
        val e = assertFailsWith<DemonException> { Demon(s, p) {}.quiesce(web) }
        assertTrue("did not stay down" in e.message!!)
    }
}
