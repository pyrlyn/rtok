// Port of src/worktree/clean.rs tests (`kept_because`) and of the list.rs walk the junk kinds rely on.
package rtok.worktree

import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val NANOS = 1_000_000_000L
private const val IDLE = 100
private const val NOW = 1_000L
private const val RECENT = 950L
private const val OBJ = 10
private const val TAG_LEN = 44L
private const val SRC_LEN = 3L
private const val BIG = 100
private const val WAIT = 10

class CleanTest {
    private fun at(secs: Long) = secs * NANOS

    private val policy = Policy(IDLE.seconds, at(NOW))

    @Test fun onlyAnIdleCacheOutsideTheCurrentWorktreeGoes() {
        val cases =
            listOf(
                Triple(0L, false to false, null),
                Triple(0L, true to true, null),
                Triple(-1L, false to false, AGE_UNKNOWN),
                Triple(RECENT, false to false, KEPT_IDLE),
                Triple(RECENT, true to true, KEPT_IDLE),
                Triple(0L, true to false, KEPT_CURRENT),
            )
        for ((mod, flags, want) in cases) {
            val cache = Cache("/w/x/target", 1, if (mod < 0) null else at(mod))
            assertEquals(want, keptBecause(cache, flags.first, flags.second, policy), "$mod $flags")
        }
    }

    @Test fun anIncompleteWalkNeverReadsAsIdle() {
        assertEquals(AGE_UNKNOWN, keptBecause(Cache("/w/x/target", 1, at(0), incomplete = true), false, false, policy))
        assertEquals(KEPT_IDLE, keptBecause(Cache("/w/x/target", 1, at(RECENT), incomplete = true), false, false, policy))
    }

    @Test fun theWalkSplitsTaggedCacheFromSourceAndSkipsNestedCheckouts() {
        val root = tmpDir("wt-usage")
        createDirAll("$root/target/debug")
        writeString("$root/target/CACHEDIR.TAG", "Signature: 8a477f597d28d172789f06886806bc55\n")
        writeString("$root/target/debug/o", "x".repeat(OBJ))
        writeString("$root/src.rs", "x".repeat(SRC_LEN.toInt()))
        createDirAll("$root/sub/.git")
        writeString("$root/sub/big", "x".repeat(BIG))
        createDirAll("$root/fake")
        writeString("$root/fake/CACHEDIR.TAG", "Signature: nope\n")
        val (u, cut) = usageUntil(root, TimeSource.Monotonic.markNow() + WAIT.seconds)
        assertFalse(cut)
        assertEquals(listOf("$root/target"), u.caches.map { it.path })
        assertEquals(OBJ + TAG_LEN, u.cache)
        assertEquals(SRC_LEN + "Signature: nope\n".length, u.source)
        assertTrue(u.modified != null && !u.incomplete)
        assertTrue(usageUntil(root, TimeSource.Monotonic.markNow()).second)
    }
}
