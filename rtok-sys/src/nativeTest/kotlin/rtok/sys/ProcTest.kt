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
