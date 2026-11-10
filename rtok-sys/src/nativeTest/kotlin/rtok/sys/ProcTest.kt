@file:Suppress("MagicNumber")

package rtok.sys

import kotlin.test.*

class ProcTest {
    private fun sh(
        script: String,
        cap: Long = Long.MAX_VALUE,
    ) = ProgressRunner("t", "/bin/sh", listOf("-c", script)).runCapped(cap)

    @Test fun capturesBothStreamsAndTheExitCode() {
        val (c, dropped) = sh("echo out; echo err >&2; exit 3")
        assertEquals("out\n", c.stdout.decodeToString())
        assertEquals("err\n", c.stderr.decodeToString())
        assertEquals(3, exitCode(c))
        assertEquals(0, dropped)
    }

    @Test fun capDropsTheRestAndCountsIt() {
        val (c, dropped) = sh("printf 0123456789", cap = 4)
        assertEquals("0123", c.stdout.decodeToString())
        assertEquals(6, dropped)
    }

    @Test fun aSignalIsExitOne() = assertEquals(1, exitCode(sh("kill -9 $$").first))

    @Test fun timeoutStillAppliesAfterBothOutputStreamsClose() {
        val e =
            assertFailsWith<IoException> {
                ProgressRunner("t", "/bin/sh", listOf("-c", "exec 1>&- 2>&-; exec sleep 2"))
                    .quiet()
                    .timeout(150)
                    .runCapped(10)
            }
        assertTrue(e.message.orEmpty().contains("timed out"))
    }

    @Test fun closedOutputStreamsDoNotLoseTheEventualExitCode() {
        val (c, _) = sh("exec 1>&- 2>&-; sleep 0.1; exit 7")
        assertEquals(7, c.exitCode)
    }

    @Test fun exitedParentDoesNotTimeOutWhileDrainingInheritedPipes() {
        val (c, _) =
            ProgressRunner("t", "/bin/sh", listOf("-c", "sleep 2 & printf done; exit 0"))
                .quiet()
                .timeout(300)
                .runCapped(10)
        assertEquals(0, c.exitCode)
        assertEquals("done", c.stdout.decodeToString())
    }

    @Test fun missingProgramIsNotFound() {
        val e = assertFailsWith<IoException> { ProgressRunner("t", "rtok-no-such-program", emptyList()).runCapped(10) }
        assertEquals(IoException.Kind.NotFound, e.kind)
    }

    @Test fun spinnerShownOnlyOnATtyWithoutOptOut() {
        assertTrue(Spinner.shown(true, null))
        assertFalse(Spinner.shown(false, null))
        assertFalse(Spinner.shown(true, "1"))
    }
}
