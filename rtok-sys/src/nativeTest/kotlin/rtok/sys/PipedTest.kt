package rtok.sys

import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val OVER_A_PIPE = 200_000

class PipedTest {
    private fun soon(ms: Long) = TimeSource.Monotonic.markNow() + ms.milliseconds

    @Test fun echoesWhatItIsSent() {
        val c = PipedChild.spawn("cat", emptyList())
        assertTrue(c.send("hi\n".encodeToByteArray()))
        assertEquals("hi\n", c.read(soon(2000))?.decodeToString())
        c.kill()
        assertNotNull(c.tryWait())
    }

    @Test fun drainInputWritesMoreThanThePipeHolds() {
        val c = PipedChild.spawn("wc", listOf("-c"))
        assertTrue(c.send(ByteArray(OVER_A_PIPE)))
        assertTrue(c.drainInput())
        assertEquals(0, c.pending)
        c.finishInput()
        c.drainInput()
        assertEquals("$OVER_A_PIPE", c.read(soon(2000))?.decodeToString()?.trim())
        assertEquals(0, c.waitExit())
    }

    @Test fun aSilentPeerTimesOut() {
        val c = PipedChild.spawn("sleep", listOf("3"))
        val t0 = TimeSource.Monotonic.markNow()
        assertNull(c.read(soon(100)))
        assertTrue(t0.elapsedNow() < 1.seconds)
        c.kill()
    }

    @Test fun aPeerThatStopsReadingDoesNotBlockTheSend() {
        val c = PipedChild.spawn("sleep", listOf("3"))
        val t0 = TimeSource.Monotonic.markNow()
        assertTrue(c.send(ByteArray(1 shl 20)))
        assertTrue(c.pending > 0)
        assertNull(c.read(soon(100)))
        c.kill()
        assertTrue(t0.elapsedNow() < 1.seconds)
    }

    @Test fun runsInCwdWithExtraEnv() {
        val dir = join(tempDir(), "rtok-piped-${pid()}")
        createDirAll(dir)
        val c = PipedChild.spawn("/bin/sh", listOf("-c", "pwd -P; echo \$RTOK_PIPED_T"), cwd = dir, extraEnv = mapOf("RTOK_PIPED_T" to "v"))
        val out = StringBuilder()
        while (true) {
            val b = c.read(soon(2000)) ?: break
            if (b.isEmpty()) break
            out.append(b.decodeToString())
        }
        assertEquals("${canonicalize(dir)}\nv\n", out.toString())
        c.kill()
        removeDirAll(dir)
    }

    @Test fun endOfFileIsAnEmptyRead() {
        val c = PipedChild.spawn("true", emptyList())
        assertEquals(0, c.read(soon(2000))?.size)
        c.kill()
        assertEquals(0, c.tryWait())
    }
}
