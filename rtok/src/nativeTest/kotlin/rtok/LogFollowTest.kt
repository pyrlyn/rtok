@file:Suppress("MagicNumber", "TooManyFunctions")

// Port of the src/log.rs tail/screen and `Follow` tests (T24.3, T589).

package rtok

import rtok.config.Config
import rtok.log.rotatedPath
import rtok.sys.appendString
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.removeDirAll
import rtok.sys.removeFile
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MIB = 1_048_576uL
private const val TEN = 10
private const val WAVES = 12

class LogFollowTest {
    private val dirs = mutableListOf<String>()

    @AfterTest fun cleanup() = dirs.forEach(::removeDirAll)

    private fun tmp(name: String): String {
        val dir = "${tempDir()}/rtok-log-$name-${platform.posix.getpid()}"
        removeDirAll(dir)
        createDirAll(dir)
        dirs += dir
        return dir
    }

    private fun cfgAt(
        dir: String,
        maxBytes: ULong,
        files: UInt,
    ): Config {
        val base = Config()
        return base.copy(log = base.log.copy(path = "$dir/rtok.log", maxBytes = maxBytes, files = files, level = "debug"))
    }

    private fun rename(
        a: String,
        b: String,
    ) = check(platform.posix.rename(a, b) == 0)

    private val Config.path get() = log.path

    private fun append(
        cfg: Config,
        msg: String,
    ) = appendLog(cfg, "info", "test", "f", msg)

    /** 4 lines in the live file and each of 3 rotated siblings, newest last within each: what rotation leaves. */
    private fun seedRotated(dir: String): Config {
        val cfg = cfgAt(dir, MIB, 3u)
        writeString(cfg.path, "live-1\nlive-2\nlive-3\nlive-4\n")
        for (i in 1..3) writeString(rotatedPath(cfg.path, i), (1..4).joinToString("") { "r$i-$it\n" })
        return cfg
    }

    @Test fun tailReadsBackwardsAcrossTheRotationBoundary() {
        val got = logTail(seedRotated(tmp("tail")), TEN)
        assertEquals(listOf("live-4", "live-3", "live-2", "live-1", "r1-4", "r1-3", "r1-2", "r1-1", "r2-4", "r2-3"), got)
    }

    @Test fun tailAndScreenAreEmptyWhenNothingHasBeenLogged() {
        val lines = logTail(cfgAt(tmp("empty"), MIB, 3u), TEN)
        assertTrue(lines.isEmpty())
        assertTrue(logScreen(lines).isEmpty())
    }

    @Test fun screenNumbersNewestFirstOverTheSameLinesAsTail() {
        val plain = logTail(seedRotated(tmp("screen")), TEN)
        assertEquals(plain.mapIndexed { i, p -> "${i + 1} $p" }, logScreen(plain))
    }

    @Test fun followReturnsOnlyLinesWrittenAfterTheLastPoll() {
        val cfg = cfgAt(tmp("follow-new"), MIB, 3u)
        append(cfg, "first")
        val (f, _) = Follow.primed(cfg.path)
        f.use {
            append(cfg, "second")
            append(cfg, "third")
            val got = f.poll(cfg.path)
            assertEquals(2, got.size, "$got")
            assertTrue(got[0].contains("second") && got[1].contains("third"), "$got")
            assertTrue(f.poll(cfg.path).isEmpty(), "nothing new, nothing repeated")
        }
    }

    @Test fun followReopensPathWhenRotationRenamesTheFileItWasReading() {
        val cfg = cfgAt(tmp("follow-rotate"), MIB, 3u)
        append(cfg, "before")
        Follow.primed(cfg.path).first.use { f ->
            rename(cfg.path, rotatedPath(cfg.path, 1))
            assertTrue(f.poll(cfg.path).isEmpty(), "renamed away, not yet recreated")
            append(cfg, "after")
            val got = f.poll(cfg.path)
            assertEquals(1, got.size, "$got") // the old line lives on in `.1`: no repeat
            assertTrue(got[0].contains("after"), "$got")
            assertTrue(f.poll(cfg.path).isEmpty(), "the stream continues past the rotation")
        }
    }

    @Test fun aRotationBetweenPollsCarriesTheLinesNotYetSeen() {
        val cfg = cfgAt(tmp("follow-carry"), MIB, 3u)
        append(cfg, "already-emitted-1")
        append(cfg, "already-emitted-2")
        Follow.primed(cfg.path).first.use { f ->
            append(cfg, "missed-by-poll")
            rename(cfg.path, rotatedPath(cfg.path, 1))
            append(cfg, "after-rename")
            val got = f.poll(cfg.path)
            assertEquals(2, got.size, "$got")
            assertTrue(got[0].contains("missed-by-poll") && got[1].contains("after-rename"), "$got")
            assertTrue(got.none { it.contains("already-emitted") }, "no line twice: $got")
        }
    }

    /** T589: a new generation that opens with the same line, and as many lines, is still a new file. */
    @Test fun aRotationIsNotMissedWhenBothGenerationsStartAlike() {
        val path = "${tmp("follow-same-first")}/rtok.log"
        appendString(path, "same\nold-shown\n")
        Follow.primed(path).first.use { f ->
            appendString(path, "old-unseen\n")
            rename(path, rotatedPath(path, 1))
            appendString(path, "same\nnew-unseen\n")
            assertEquals(listOf("old-unseen", "same", "new-unseen"), f.poll(path))
            assertTrue(f.poll(path).isEmpty())
        }
    }

    /** T589: an empty replacement between polls must not make the next poll replay the old file. */
    @Test fun anEmptyReplacementKeepsTheGenerationBoundary() {
        val path = "${tmp("follow-empty-gen")}/rtok.log"
        appendString(path, "same\nold-shown\n")
        Follow.primed(path).first.use { f ->
            appendString(path, "old-unseen\n")
            rename(path, rotatedPath(path, 1))
            writeString(path, "")
            assertEquals(listOf("old-unseen"), f.poll(path))
            assertTrue(f.poll(path).isEmpty())
            appendString(path, "same\n")
            assertEquals(listOf("same"), f.poll(path), "no replay of the rotated file")
            appendString(path, "half-written")
            assertTrue(f.poll(path).isEmpty(), "a line is a line once its newline lands")
        }
    }

    /** T589: the unread tail of the old file survives retention deleting it; a file truncated in place restarts. */
    @Test fun theOldTailSurvivesDeletionAndTruncationRestarts() {
        val path = "${tmp("follow-deleted")}/rtok.log"
        appendString(path, "a\nb\n")
        Follow.primed(path).first.use { f ->
            appendString(path, "c\n")
            removeFile(path) // rotated off and deleted before the next poll
            appendString(path, "d\n")
            assertEquals(listOf("c", "d"), f.poll(path))
            appendString(path, "d2\n")
            assertEquals(listOf("d2"), f.poll(path))
            writeString(path, "e\n") // truncated in place: same file, fewer lines
            assertEquals(listOf("e"), f.poll(path))
        }
    }

    @Test fun aRotationStormBetweenPollsPrintsEveryLineOnce() {
        val cfg = cfgAt(tmp("follow-storm"), 150uL, 5u) // a line is ~40 B: the burst rotates every few writes
        Follow.primed(cfg.path).first.use { f ->
            // primed on a not-yet-existing file
            for (i in 0 until WAVES) append(cfg, "wave-$i")
            assertTrue(exists(rotatedPath(cfg.path, 1)), "the burst actually rotated")
            val got = f.poll(cfg.path)
            assertEquals(WAVES, got.size, "$got")
            for (i in 0 until WAVES) assertEquals(1, got.count { it.endsWith("wave-$i") }, "wave-$i: $got")
            assertTrue(got.last().endsWith("wave-11"), "arrival order: $got")
        }
    }
}
