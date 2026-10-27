// Port of src/agents/junk.rs report tests: archive retention, host folders and the documented mark, VS Code's
// CachedData, a shared folder, `--all`, a scan past its limit and an unreadable folder. The hard-link case of
// `disk_usage` is rtok-sys DiskUsageTest.
package rtok.agents

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.chmod
import rtok.config.HOSTS
import rtok.store.Store
import rtok.store.insertCall
import rtok.store.insertCallIo
import rtok.store.setCallTs
import rtok.store.upsertSession
import rtok.support.diskConfig
import rtok.support.installDb
import rtok.sys.createDirAll
import rtok.sys.diskUsageUntil
import rtok.sys.listDir
import rtok.sys.setModified
import rtok.sys.symlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

private const val KIB = 1024
private const val MIB = 1 shl 20
private const val SMALL = 10
private const val LOG = 100
private const val BODY_KIB = 70
private const val INLINE_KIB = 64
private const val HALF_MIB = 512 * 1024L
private const val SHARED_BLOB = 64 * 1024
private const val FRESH_SECS = 60L
private const val OLD_DAYS = 40L
private const val DAY = 86_400L
private const val NANOS = 1_000_000_000L
private const val BLOCK = 4096L
private const val CLOSED = 0
private const val OPEN = 493

class JunkReportTest {
    @Test
    fun archiveRetentionKeepsTheReferencedOneAndClearsTheOrphan() {
        installDb()
        val (cfg, _) = diskConfig("junk-archive-retention")
        Store.open(cfg.core.dbPath).use { store ->
            store.upsertSession("s1", 1, null, null, "proxy")

            fun spill(
                byte: Byte,
                ts: Long?,
            ) {
                val call = store.insertCall("s1", "proxy", "api_request", 1, null, null, null, null)
                // Distinct bodies: archives are content-addressed, so two identical bodies would dedupe.
                store.insertCallIo(call, ByteArray(BODY_KIB * KIB) { byte }, null, INLINE_KIB * KIB, cfg.core.archiveDir)
                ts?.let { store.setCallTs(call, it) }
            }
            spill('a'.code.toByte(), null)
            spill('b'.code.toByte(), 0)
        }
        val archives = JunkOwn.run(cfg, true).filter { it.kind == "archive" }
        assertEquals(1, archives.size, "only the orphan is reported")
        assertEquals("removed", archives[0].note)
        assertEquals(1, listDir(cfg.core.archiveDir).size, "the referenced archive survives")
    }

    @Test
    fun hostRowsListTheirFoldersWithSizesAndTheDocumentedMark() {
        installDb()
        val (cfg, dir) = diskConfig("junk-hosts")
        write("$dir/outside/big", MIB)
        val claude = "$dir/.claude"
        write("$claude/settings.json", SMALL)
        write("$claude/debug/a.log", LOG)
        symlink("$dir/outside", "$claude/link")
        write("$dir/.cursor/hooks.json", SMALL)
        val cursorData = "$dir/Library/Application Support/Cursor"
        write("$cursorData/state", SMALL)
        write("$dir/.codex/log/codex.log", SMALL)
        write("$dir/.codex/config.toml", SMALL)
        // The hosts here are folders only, not installed apps (T426).
        val report = Junk.reportWith(cfg, roots(dir), JunkOptions(all = true), JunkCache.AGENT_SCAN_LIMIT)
        val c = agent(report, "claude")
        val home = folder(c, claude)
        assertEquals("data" to true, home.role to home.documented)
        assertEquals(diskUsage(claude), home.sizeBytes)
        assertTrue(home.sizeBytes < HALF_MIB, "the link out of the folder was followed: ${home.sizeBytes}")
        val logs = folder(c, "$claude/debug")
        assertEquals("logs" to true, logs.role to logs.documented)
        assertEquals(diskUsage("$claude/debug"), logs.sizeBytes)
        // The debug log is inside the data home, so the agent total does not add it twice.
        assertEquals(home.sizeBytes, c.totalBytes)
        val cur = agent(report, "cursor")
        assertTrue(cur.folders.size >= 2, "${cur.folders}")
        assertTrue(cur.folders.none { it.documented }, "${cur.folders}")
        val app = folder(cur, cursorData)
        assertEquals(diskUsage(cursorData), app.sizeBytes)
        assertTrue(folder(cur, "$dir/.cursor").sizeBytes > 0)
        val x = agent(report, "codex")
        assertEquals("logs", folder(x, "$dir/.codex/log").role)
        assertTrue(folder(x, "$dir/.codex").documented)
        val text = JunkList.toList(report, JunkView(exact = true))
        assertTrue(text.contains("$cursorData  data  ${app.sizeBytes}  not documented: not cleared"), text)
        assertTrue(!text.contains("\u001b]8"), "no link off a terminal: $text")
        assertTrue(JunkList.toList(report, JunkView(exact = true, links = true)).contains("\u001b]8;;file://"))
    }

    @Test
    fun vscodeCodeCacheOffersOldCommitFoldersAndCursorNone() {
        val (cfg, dir) = agentsConfig("junk-code-cache")
        val code = "$dir/.config/Code/CachedData"
        val now = Junk.nowNanos()
        for ((name, secs) in listOf("new" to FRESH_SECS, "old" to OLD_DAYS * DAY)) {
            val f = "$code/$name/f.cache"
            write(f, LOG)
            for (p in listOf(f, "$code/$name")) setModified(p, now - secs * NANOS)
        }
        write("$dir/.config/Cursor/CachedData/x/f", LOG)
        val report = Junk.reportWith(cfg, roots(dir), everyHost(), JunkCache.AGENT_SCAN_LIMIT)
        val v = agent(report, "vscode")
        assertEquals(listOf("$code/old"), v.items.map { it.path })
        assertEquals(null, v.items[0].kept, "${v.items}")
        assertTrue(folder(v, code).documented)
        assertTrue(agent(report, "cursor").items.isEmpty())
    }

    @Test
    fun aFolderTwoAgentsShareIsSizedOnceAndMarkedShared() {
        val (cfg, dir) = agentsConfig("junk-shared")
        val shared = "$dir/shared"
        write("$shared/blob", SHARED_BLOB)
        createDirAll("$dir/.claude")
        createDirAll("$dir/.codex")
        val env = mapOf("CLAUDE_CONFIG_DIR" to shared, "CODEX_HOME" to shared)
        val report = Junk.reportWith(cfg, roots(dir, env), JunkOptions(all = true), JunkCache.AGENT_SCAN_LIMIT)
        val c = folder(agent(report, "claude"), shared)
        val x = folder(agent(report, "codex"), shared)
        assertEquals(listOf("codex"), c.sharedWith)
        assertEquals(listOf("claude"), x.sharedWith)
        assertEquals(c.sizeBytes, x.sizeBytes)
        val both = agent(report, "claude").totalBytes + agent(report, "codex").totalBytes
        assertTrue(report.totalBytes < both, "${report.totalBytes} !< $both")
        assertTrue(JunkList.toList(report, JunkView()).contains("shared with codex"))
    }

    @Test
    fun allAddsHostsThatAreNotInstalledAndTheDefaultSkipsThem() {
        installDb()
        val (cfg, dir) = diskConfig("junk-all")
        val r = roots(dir)
        val some = Junk.reportWith(cfg, r, JunkOptions(), JunkCache.AGENT_SCAN_LIMIT)
        val every = Junk.reportWith(cfg, r, JunkOptions(all = true), JunkCache.AGENT_SCAN_LIMIT)
        assertEquals(HOSTS.size + 1, every.agents.size, "rtok plus every host")
        assertTrue(some.agents.size < every.agents.size)
        val absent = every.agents.first { it.host && !it.installed }
        assertTrue(absent.folders.isEmpty())
        assertTrue(JunkList.toList(every, JunkView()).contains("${absent.name} (not installed)"))
    }

    @Test
    fun aScanPastItsLimitIsReportedNotFatal() {
        installDb()
        val (cfg, dir) = diskConfig("junk-timeout")
        write("$dir/.claude/settings.json", SMALL)
        val report = Junk.reportWith(cfg, roots(dir), JunkOptions(all = true), Duration.ZERO)
        val note = folder(agent(report, "claude"), "$dir/.claude").note
        assertTrue(note?.contains("scan stopped") == true, "$note")
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun anUnreadableFolderIsReportedNotFatal() {
        val (_, dir) = diskConfig("junk-denied")
        val locked = "$dir/locked"
        write("$locked/f", SMALL)
        chmod(locked, CLOSED.toUShort())
        val usage = diskUsageUntil(locked, null)
        chmod(locked, OPEN.toUShort())
        // A superuser reads it anyway, which is not the case under test.
        if (!usage.denied) assertTrue(usage.bytes > 0)
        assertTrue(usage.denied || usage.bytes > BLOCK)
    }
}
