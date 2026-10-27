@file:Suppress("MagicNumber")

package rtok.agents

import rtok.config.Config
import rtok.config.Setup
import rtok.sys.ProgressRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Port of the with_restart / run_ok tests in src/agents/restart.rs (T141, T434, T629).
@Suppress("TooManyFunctions")
class RestartRunTest {
    private class FakeProcs(
        names: List<String> = emptyList(),
        val failQuit: Boolean = false,
        /** `quit` succeeds but the app keeps running, as when it shows a quit prompt. */
        val stuckQuit: Boolean = false,
        val failOpen: Boolean = false,
    ) : Procs {
        val running = names.toMutableSet()
        val calls = mutableListOf<String>()

        override fun appRunning(name: String) = name in running

        override fun binRunning(bin: String) = bin in running

        override fun quit(name: String) {
            calls += "quit:$name"
            if (failQuit) throw ProcError("boom")
            if (!stuckQuit) running -= name
        }

        override fun open(
            name: String,
            path: String,
        ) {
            calls += "open:$name"
            if (failOpen) throw ProcError("open -a $name: exit status: 1")
        }
    }

    private fun req(vararg hosts: String) = Request(hosts.toList(), Mode.Install, cli = false, desktop = false, all = true)

    private fun restart(
        procs: FakeProcs,
        cfg: Config = Config(),
        hosts: List<String> = listOf("windsurf"),
        noRestart: Boolean = false,
        warnings: MutableList<String> = mutableListOf(),
        wouldChange: (Config, Agent) -> Boolean = { _, _ -> true },
        write: (Config) -> String = { "" },
    ) = RestartFlow.withRestart(cfg, req(*hosts.toTypedArray()), noRestart, procs, wouldChange, write, warnings::add, quitTimeoutMs = 300)

    private fun callLoggingWrite(
        procs: FakeProcs,
        warnings: MutableList<String> = mutableListOf(),
    ) = restart(procs, warnings = warnings) {
        procs.calls += "write"
        ""
    }

    @Test fun runningAndChangedQuitsWritesThenReopensInOrder() {
        val procs = FakeProcs(listOf("Windsurf"))
        callLoggingWrite(procs)
        assertEquals(listOf("quit:Windsurf", "write", "open:Windsurf"), procs.calls)
    }

    @Test fun runningButUnchangedDoesNothing() {
        val procs = FakeProcs(listOf("Windsurf"))
        restart(procs, wouldChange = { _, _ -> false })
        assertTrue(procs.calls.isEmpty())
    }

    @Test fun notRunningWritesOnly() {
        val procs = FakeProcs()
        restart(procs)
        assertTrue(procs.calls.isEmpty())
    }

    @Test fun dryRunPrintsWouldCloseAndMakesNoCalls() {
        val procs = FakeProcs(listOf("Windsurf"))
        val out = restart(procs, cfg = Config(setup = Setup(dryRun = true)))
        assertTrue(procs.calls.isEmpty())
        assertTrue("would close/reopen Windsurf" in out, out)
    }

    @Test fun noRestartFlagWritesOnly() {
        val procs = FakeProcs(listOf("Windsurf"))
        restart(procs, noRestart = true)
        assertTrue(procs.calls.isEmpty())
    }

    @Test fun quitFailureStillWritesAndWarnsInsteadOfErroring() {
        val procs = FakeProcs(listOf("Windsurf"), failQuit = true)
        val warnings = mutableListOf<String>()
        val out = callLoggingWrite(procs, warnings)
        assertEquals(listOf("quit:Windsurf", "write"), procs.calls)
        assertEquals("", out)
        assertTrue(warnings.single().startsWith("could not quit Windsurf: boom"), warnings.toString())
    }

    /** T434: an app still running after the quit timeout (a quit prompt) is not reopened. */
    @Test fun appThatNeverQuitsIsWrittenButNotReopened() {
        val procs = FakeProcs(listOf("Windsurf"), stuckQuit = true)
        val out = callLoggingWrite(procs)
        assertEquals(listOf("quit:Windsurf", "write"), procs.calls)
        assertEquals("", out)
    }

    /** T434: a failed reopen warns and the install still succeeds. */
    @Test fun reopenFailureWarnsInsteadOfErroring() {
        val procs = FakeProcs(listOf("Windsurf"), failOpen = true)
        val warnings = mutableListOf<String>()
        val out = callLoggingWrite(procs, warnings)
        assertEquals(listOf("quit:Windsurf", "write", "open:Windsurf"), procs.calls)
        assertEquals("", out)
        assertTrue(warnings.single().startsWith("could not reopen Windsurf"), warnings.toString())
    }

    /** T434: a command that starts but exits non-zero is an error carrying its stderr. */
    @Test fun runOkFailsOnANonZeroExit() {
        val err = assertFailsWith<ProcError> { runOk(ProgressRunner("", "sh", listOf("-c", "echo nope >&2; exit 3")).quiet(), "probe") }
        val msg = err.message.orEmpty()
        assertTrue("probe" in msg && "nope" in msg, msg)
        runOk(ProgressRunner("", "true", emptyList()).quiet(), "probe")
    }

    @Test fun writeFailureStillReopensThenPropagatesTheError() {
        val procs = FakeProcs(listOf("Windsurf"))
        val err = assertFailsWith<IllegalStateException> { restart(procs) { error("disk full") } }
        assertEquals(listOf("quit:Windsurf", "open:Windsurf"), procs.calls)
        assertTrue("disk full" in err.message.orEmpty())
    }

    /** T629: a host that cannot be planned must be found out before any app is closed. */
    @Test fun aFailingPreflightOnAnyHostClosesNothingAndWritesNothing() {
        val hosts = listOf("windsurf", "cursor", "devin")
        for (bad in hosts) {
            val procs = FakeProcs(listOf("Windsurf", "Cursor", "Devin"))
            var wrote = false
            val err =
                assertFailsWith<IllegalStateException> {
                    restart(procs, hosts = hosts, wouldChange = { _, a -> check(a.id != bad) { "cannot read $bad config" }.let { true } }) {
                        wrote = true
                        ""
                    }
                }
            assertTrue(bad in err.message.orEmpty(), err.message)
            assertTrue(procs.calls.isEmpty(), "$bad: ${procs.calls}")
            assertFalse(wrote, "$bad: the write must not run")
        }
    }

    /** T629 control: each running app is closed, the write runs once, then each is reopened. */
    @Test fun twoRunningDesktopHostsAreBothClosedThenBothReopened() {
        val procs = FakeProcs(listOf("Windsurf", "Cursor"))
        restart(procs, hosts = listOf("windsurf", "cursor")) {
            procs.calls += "write"
            ""
        }
        val calls = procs.calls
        assertEquals(5, calls.size, calls.toString())
        assertTrue(calls.take(2).all { it.startsWith("quit:") }, calls.toString())
        assertEquals("write", calls[2])
        assertTrue(calls.drop(3).all { it.startsWith("open:") }, calls.toString())
    }

    @Test fun cliOnlyHostRunningPrintsMessageAndIsNeverKilled() {
        val procs = FakeProcs(listOf("aider"))
        val out = restart(procs, hosts = listOf("aider"))
        assertTrue(procs.calls.isEmpty())
        assertTrue("aider: restart your aider session" in out, out)
    }
}
