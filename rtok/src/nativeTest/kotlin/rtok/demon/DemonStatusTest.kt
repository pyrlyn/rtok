@file:Suppress("MagicNumber")
// Port (status, parsing, backoff, lock) of src/demon.rs's unit tests (T20.1, T597): ownership-guarded stop, escalation, quiesce, backoff, parsing,
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

class DemonStatusTest {
    @Test fun theFirstCrashWaitsBackoffMsThenDoublesToTheCap() {
        var cur = 1000L
        val waits =
            List(4) {
                val (w, next) = nextBackoff(cur, false, 1000, 3000)
                cur = next
                w
            }
        assertEquals(listOf(1000L, 2000L, 3000L, 3000L), waits)
        assertEquals(1000L, nextBackoff(cur, true, 1000, 3000).first, "a healthy run resets")
    }

    @Test fun aServiceNameIsNeverAnArgv() {
        assertEquals(Service.PROXY, Service.parse("PROXY"))
        assertEquals(listOf("hook", "--serve"), Service.HOOK.args)
        val e = assertFailsWith<DemonException> { Service.parse("rm -rf ~") }
        assertTrue("[demon] services" in e.message!!, e.message)
    }

    @Test fun targetsPreferNamedThenRunningThenConfigured() {
        val s = store("targets")
        val d = Demon(s, Recorder(null, emptyList())) {}
        assertEquals(listOf(Service.MCP), d.targets(listOf(Service.MCP), listOf("proxy"), true))
        assertEquals(listOf(Service.PROXY), d.targets(emptyList(), listOf("proxy"), true))
        s.write(state(1, 2, 3))
        assertEquals(listOf(Service.WEB), d.targets(emptyList(), listOf("proxy"), true))
        assertEquals(listOf(Service.PROXY), d.targets(emptyList(), listOf("proxy"), false))
    }

    @Test fun statusTableKeepsAGapBetweenServiceAndState() {
        val row = Row("proxy", false, null, null, null, null, null, "/tmp/proxy.log")
        val body = table(listOf(row)).lines()[1]
        assertFalse("proxystopped" in body)
        assertTrue(body.substringAfter("proxy").substringBefore("stopped").isBlank())
        assertTrue(body.endsWith("/tmp/proxy.log"))
    }

    @Test fun theProxyRowNamesItsConfiguredEndpointStoppedOrNot() {
        val s = store("endpoint")
        val rows = s.rows(emptyList(), "127.0.0.2:8123")
        val proxy = rows.first { it.service == "proxy" }
        assertFalse(proxy.running)
        assertEquals("127.0.0.2:8123", proxy.endpoint)
        assertNull(rows.first { it.service == "mcp" }.endpoint, "mcp is stdio, it has no address")
        val text = table(rows)
        assertTrue("endpoint" in text && "127.0.0.2:8123" in text, text)
    }

    @Test fun aRunningRowComesFromTheLockNotTheStateFile() {
        val s = store("rows")
        s.write(State("mcp", 10, 11, 100, 2))
        assertFalse(s.rows(listOf(Service.MCP), "x", now = 160).single().running, "no lock: stopped")
        s.claim(Service.MCP)!!.use {
            val r = s.rows(listOf(Service.MCP), "x", now = 160).single()
            assertEquals(Row("mcp", true, null, 10, 11, 60, 2, s.file(Service.MCP, "log")), r)
        }
    }

    @Test fun aSecondClaimForOneServiceFailsUntilTheFirstDrops() {
        val s = store("claim")
        val held = assertNotNull(s.claim(Service.WEB))
        assertNull(s.claim(Service.WEB))
        assertTrue(s.held(Service.WEB))
        held.close()
        assertFalse(s.held(Service.WEB), "released on close")
    }

    @Test fun aGroupPidIsNeverSignalledAndTheBootTimeParses() {
        assertTrue(OsProcs.alive(pid()))
        assertFalse(OsProcs.alive(0) || OsProcs.alive(-1))
        assertEquals(1700000000L, parseBootTime("{ sec = 1700000000, usec = 0 } Tue Nov 14"))
        assertTrue((OsProcs.bootTime() ?: 0) in 1..now())
    }
}
