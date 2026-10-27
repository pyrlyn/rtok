// Port of the lock, backup and swap tests of src/agents/junk_kinds.rs.
package rtok.agents

import rtok.support.tmpDir
import rtok.sys.createDirAll
import rtok.sys.parent
import rtok.sys.pid
import rtok.sys.setModified
import rtok.sys.symlink
import rtok.sys.tryLockExclusive
import rtok.sys.writeBytes
import rtok.worktree.KEPT_CURRENT
import rtok.worktree.KEPT_IDLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val DAY = 86_400L
private const val TWO_DAYS = 2 * DAY
private const val THREE_DAYS = 3 * DAY
private const val HOUR = 3_600L
private const val MINUTE = 60L
private const val STALE_DAYS = 40
private const val STALER_DAYS = 80
private const val BIG = 100
private const val SMALL = 50
private const val TARGET = 200
private const val NO_SUCH_PID = 0x7fff_fff0
private const val BUILD_ROWS = 5
private const val TEMP_ROWS = 4

class JunkFindsTest {
    private val limit = LIMIT

    @Test fun aHeldLockIsKeptForItsReasonAndPackageLockfilesAreNeverListed() {
        val root = tmpDir("kinds-locks")
        for (f in listOf("Cargo.lock", "yarn.lock", "free.lock", "held.lock", "node_modules/dep.lock")) putFile("$root/$f")
        putFile("$root/db/LOCK", ByteArray(0))
        createDirAll("$root/state/.lock")
        assertNotNull(tryLockExclusive("$root/held.lock"))

        val items = foundItems(listOf(root), limit)

        assertEquals(listOf("db/LOCK", "free.lock", "held.lock", "state/.lock"), items.map { it.path.removePrefix("$root/") }.sorted())
        assertEquals(JunkKinds.HELD, junkRow(items, "held.lock").kept)
        assertEquals(JunkKinds.NOT_DOCUMENTED, junkRow(items, "free.lock").kept)
        assertTrue(items.all { it.kind == "locks" && !it.counted })
    }

    @Test fun backupCopiesAreListedReadOnlyAndRtoksOwnBackupFolderIsNotWalked() {
        val root = tmpDir("kinds-backups")
        putFile("$root/notes.txt~")
        putFile("$root/a/settings.json.bak")
        putFile("$root/.backup_configs/settings.json.bak-1")
        putFile("$root/_backup/settings.json.bak-1")

        val items = foundItems(listOf(root), limit)

        assertEquals(2, items.size, "$items")
        assertTrue(items.all { Triple(it.kind, it.cls, it.kept) == Triple("backups", "review", JunkKinds.NOT_DOCUMENTED) })
    }

    @Test fun aSwapFileOfALiveProcessIsKeptForThatAndADeadOwnerIsOnlyNotDocumented() {
        val root = tmpDir("kinds-swap")
        vimSwap("$root/.live.txt.swp", pid())
        vimSwap("$root/.dead.txt.swo", NO_SUCH_PID)
        putFile("$root/page.crswap", "no header".encodeToByteArray())
        putFile("$root/short.swp", "b0".encodeToByteArray())
        symlink("me@host.${pid()}:1700000000", "$root/.#edit.rs")

        val items = foundItems(listOf(root), limit)

        fun why(n: String) = junkRow(items, n).kept
        val live = "owner process ${pid()} is running"
        assertEquals(live, why(".live.txt.swp"))
        assertEquals(JunkKinds.NOT_DOCUMENTED, why(".dead.txt.swo"))
        assertEquals(JunkKinds.OWNER_UNKNOWN, why("page.crswap"))
        assertEquals(JunkKinds.OWNER_UNKNOWN, why("short.swp"))
        assertEquals(live, why(".#edit.rs"))
        assertTrue(items.all { it.kind == "swap" && !it.counted })
    }
}
