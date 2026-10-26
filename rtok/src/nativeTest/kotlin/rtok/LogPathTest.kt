package rtok

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import rtok.config.Config
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression: an unfinished `Config()` used to write `./~/.rtok/logs/rtok.log` relative to the working directory. */
@OptIn(ExperimentalForeignApi::class)
class LogPathTest {
    @Test fun unfinishedConfigLogsUnderRtokHomeNeverALiteralTilde() {
        val home = "${tempDir()}/rtok-logpath-${platform.posix.getpid()}"
        val saved = getenv("RTOK_HOME")?.toKString()
        setenv("RTOK_HOME", home, 1)
        try {
            val log = Config().fileLog()
            assertEquals("$home/logs/rtok.log", log.path)
            appendLog(Config(), "error", "test", "logpath", "probe")
            assertTrue(access(log.path, F_OK) == 0, "log written under RTOK_HOME")
            assertFalse(access("~", F_OK) == 0, "no literal ./~ directory in the working directory")
        } finally {
            if (saved == null) unsetenv("RTOK_HOME") else setenv("RTOK_HOME", saved, 1)
        }
    }
}
