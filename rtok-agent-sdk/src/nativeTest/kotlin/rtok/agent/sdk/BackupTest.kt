package rtok.agent.sdk

import rtok.sys.EntryKind
import rtok.sys.createDirAll
import rtok.sys.entryKind
import rtok.sys.join
import rtok.sys.listDir
import rtok.sys.pid
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.symlink
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private fun tmp(name: String): String {
    val dir = join(tempDir(), "rtok-agent-sdk-backup-$name-${pid()}")
    removeDirAll(dir)
    createDirAll(dir)
    return dir
}

class BackupTest {
    @Test
    fun writeKeepsASymlinkedConfigAndWritesItsTarget() {
        val dir = tmp("link")
        val real = join(dir, "real.json")
        val link = join(dir, "cfg.json")
        writeString(real, "{}")
        symlink(real, link)
        write(Apply(), link, "{\"a\":1}", "+ a")
        assertEquals(EntryKind.Symlink, entryKind(link))
        assertEquals("{\"a\":1}", readToString(real))
        assertEquals(listOf("cfg.json", "real.json"), listDir(dir).sorted())
        removeDirAll(dir)
    }

    @Test
    fun backupAtSkipsASlotTakenMeanwhile() {
        val dir = tmp("slot")
        val cfg = join(dir, "settings.json")
        val baks = join(dir, BACKUP_DIR)
        createDirAll(baks)
        // Not a regular file, so the slot count does not skip it and the create must step past it.
        createDirAll(join(baks, "settings.json.bak-7"))
        writeString(cfg, "one")
        val first = assertNotNull(backupAt(cfg, 7uL))
        writeString(cfg, "two")
        val second = assertNotNull(backupAt(cfg, 7uL))
        assertEquals(join(baks, "settings.json.bak-7-1"), first)
        assertEquals("one", readToString(first))
        assertEquals("two", readToString(second))
        removeDirAll(dir)
    }
}
