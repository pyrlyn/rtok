@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("MagicNumber")
// Port of tests/logs.rs: `rtok logs watch` against a real process (follow, rotation, orphaning) and `[log] tspin`
// with a fake tailspin on PATH.

package rtok.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.cinterop.toKString
import rtok.sys.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private const val ORPHAN_WAIT_MS = 10_000L
private const val POLL_US = 50_000u

class LogsE2eSpec :
    FunSpec({
        fun seed(s: Sandbox) {
            createDirAll("${s.home}/logs")
            writeString("${s.home}/logs/rtok.log", "live-1\nlive-2\nlive-3\nlive-4\n")
        }

        // The watch runs in the background with stdout on a file — the non-TTY half: plain rows, no escape codes. The
        // waits allow 20 poll intervals of slack for CI; the contract is one.
        test("watch streams a line from another process and survives a rotation") {
            Sandbox("logs-watch").use { s ->
                createDirAll("${s.home}/logs")
                writeString("${s.home}/logs/rtok.log", "seed-1\nseed-2\n")
                val script =
                    """
                    "${'$'}0" logs watch --lines 5 >out 2>err </dev/null & pid=${'$'}!
                    upto() { i=0; until grep -qx "${'$'}1" out; do i=${'$'}((i+1)); [ ${'$'}i -gt 200 ] && return 1; sleep 0.02; done; }
                    upto "2 seed-1" && printf 'watch-a\n' >>rtok-home/logs/rtok.log && upto "3 watch-a" &&
                      mv rtok-home/logs/rtok.log rtok-home/logs/rtok.log.1 && printf 'watch-b\n' >>rtok-home/logs/rtok.log &&
                      upto "4 watch-b"
                    ok=${'$'}?
                    kill ${'$'}pid; wait ${'$'}pid 2>/dev/null
                    cat out
                    exit ${'$'}ok
                    """.trimIndent()
                val r = sh(listOf("sh", "-c", script, RTOK), s.root, mapOf("RTOK_HOME" to s.home, "HOME" to s.user))
                r.code shouldBe 0
                r.out shouldBe "1 seed-2\n2 seed-1\n3 watch-a\n4 watch-b\n"
                r.out shouldNotContain "\u001b"
            }
        }

        // T225.1: a fake tailspin tags each row so the pipe is visible. `off` — and `auto` off a terminal — keep rtok's.
        test("[log] tspin pipes the numbered rows through the viewer on PATH") {
            Sandbox("logs-tspin").use { s ->
                seed(s)
                val bin = "${s.root}/bin".also(::createDirAll)
                writeString("$bin/tspin", "#!/bin/sh\n[ \"$1\" = --print ] || exit 2\nsed 's/^/TSPIN /'\n")
                check(platform.posix.chmod("$bin/tspin", "755".toUInt(8).toUShort()) == 0)
                val path = "$bin:${platform.posix.getenv("PATH")?.toKString().orEmpty()}"

                fun logs(mode: String) = s.rtok("logs", "--lines", "2", env = mapOf("PATH" to path, "RTOK_LOG_TSPIN" to mode))
                logs("always").out shouldBe "TSPIN 1 live-4\nTSPIN 2 live-3\n"
                for (mode in listOf("auto", "off")) logs(mode).out shouldBe "1 live-4\n2 live-3\n"
            }
        }

        // T235.3: `sh` backgrounds the watch (stdout to /dev/null, so no failing write can end it) and exits at once;
        // the watch, reparented, must be gone within a few polls.
        test("watch exits once its parent is gone") {
            Sandbox("logs-orphan").use { s ->
                val r = sh(listOf("sh", "-c", "\"\$0\" logs watch >/dev/null 2>&1 </dev/null & echo \$!", RTOK), s.root, mapOf("RTOK_HOME" to s.home, "HOME" to s.user))
                val pid = r.out.trim().toInt()
                val deadline = TimeSource.Monotonic.markNow() + ORPHAN_WAIT_MS.milliseconds
                while (processAlive(pid) && deadline.hasNotPassedNow()) platform.posix.usleep(POLL_US)
                val alive = processAlive(pid)
                if (alive) platform.posix.kill(pid, platform.posix.SIGKILL)
                alive shouldBe false
            }
        }
    })
