@file:Suppress("MagicNumber", "EmptyFunctionBlock")

package rtok

import rtok.StderrLog.Level
import rtok.cli.CliIo
import rtok.cli.taskWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StderrLogTest {
    @Test fun rustLogDirectivesPickTheLongestTarget() {
        assertFalse(StderrLog.enabled(Level.Warn, "rtok::tasks", null), "unset is off")
        assertFalse(StderrLog.enabled(Level.Error, "rtok::tasks", "off"))
        assertTrue(StderrLog.enabled(Level.Warn, "rtok::tasks", "warn"))
        assertFalse(StderrLog.enabled(Level.Info, "rtok::tasks", "warn"))
        assertTrue(StderrLog.enabled(Level.Warn, "rtok::tasks::github", "rtok=debug"))
        assertTrue(StderrLog.enabled(Level.Trace, "rtok::tasks", "rtok"), "a bare target enables every level")
        assertFalse(StderrLog.enabled(Level.Warn, "rtok::tasks", "error,rtok::tasks=error"))
        assertTrue(StderrLog.enabled(Level.Warn, "rtok::tasks", "error,rtok::tasks=warn"))
        assertFalse(StderrLog.enabled(Level.Warn, "rtokx", "rtok=warn"), "a prefix stops at `::`")
    }

    @Test fun theLineIsEnvLoggersDefault() {
        assertEquals("[2026-10-27T07:35:19Z WARN  rtok::tasks] hi", StderrLog.line(Level.Warn, "rtok::tasks", "hi", 1_793_086_519))
    }

    @Test fun taskWarningsReachStderrOnlyWhenEnabled() {
        val err = StringBuilder()
        val io =
            object : CliIo {
                override fun out(text: String) {}

                override fun err(text: String) {
                    err.append(text)
                }
            }
        taskWarning(io, "skipped", null)
        assertEquals("", err.toString())
        taskWarning(io, "skipped", "rtok=warn")
        assertTrue(err.endsWith(" WARN  rtok::tasks] skipped\n"), err.toString())
    }
}
