// Port of src/agents/junk.rs report tests over the junk kinds: caches under their agent, a shared tagged cache,
// temp/build/staging against locks and swap, and the sessions and index classes.
package rtok.agents

import rtok.store.Origin
import rtok.store.Store
import rtok.store.registerProject
import rtok.sys.createDirAll
import rtok.sys.symlink
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DAY = 86_400L
private const val TINY = 1
private const val FIVE = 5
private const val SMALL = 10
private const val COPILOT = 50
private const val LSP = 70
private const val STATE = 30
private const val PLUGIN = 40
private const val LOG = 100
private const val SNAPSHOT = 300
private const val CURSOR_CACHE = 300
private const val CURSOR_LIB = 400
private const val BAD = 500
private const val BUILD = 1000
private const val PAGE = 4096
private const val MIB = 1 shl 20
private const val RTOK_CACHE = 20
private const val AGED_DAYS = 3L
private const val SESSION_DAYS = 40L

class JunkReportKindsTest {
    @Test
    fun cachesSitUnderTheirAgentAndOnlyEvidenceCountsAsFreed() {
        val (cfg, dir) = agentsConfig("junk-cache")
        val r = roots(dir)
        val claude = "$dir/.claude"
        write("$claude/settings.json", SMALL)
        write("$claude/paste-cache/p", LOG)
        write("$claude/projects/p/target/o", BUILD)
        tagged("$claude/projects/p/target")
        write("$claude/projects/q/bad/o", BAD)
        writeString("$claude/projects/q/bad/CACHEDIR.TAG", "Signature: no")
        val cursor = "$dir/Library/Application Support/Cursor"
        write("$cursor/Code Cache/blob", CURSOR_CACHE)
        write("$dir/Library/Caches/Cursor/y", CURSOR_LIB)
        val copilot = r.resolve("{copilot_cache}")
        write("$copilot/c", COPILOT)
        val rtokCache = r.resolve("{rtok_cache}")
        write("$rtokCache/r", RTOK_CACHE)
        val project = "$dir/proj"
        write("$project/.rtok-lsp-xdg/cache/lsp", LSP)
        Store.open(cfg.core.dbPath).use { it.registerProject(project, Origin.Manual) }
        for (d in listOf(claude, cursor, copilot, rtokCache, project)) ageFiles(d, AGED_DAYS * DAY)
        val before = walkNames(dir)

        val report = Junk.reportWith(cfg, r, everyHost(), JunkCache.AGENT_SCAN_LIMIT)

        val c = agent(report, "claude")
        val want = listOf("$claude/paste-cache", "$claude/projects/p/target")
        assertEquals(want, c.items.map { it.path }, "the bad-signature dir is not cache")
        assertEquals("research.md §22" to "CACHEDIR.TAG", c.items[0].evidence to c.items[1].evidence)
        assertEquals("cache" to 2, c.kinds[0].kind to c.kinds[0].items)
        assertEquals(want.sumOf(::diskUsage), c.freedDefaultBytes)
        val cur = agent(report, "cursor")
        assertTrue(cur.items.isEmpty() && cur.kinds.isEmpty())
        assertEquals(0, cur.freedDefaultBytes)
        val codeCache = folder(cur, "$cursor/Code Cache")
        assertEquals(Triple("cache", false, diskUsage("$cursor/Code Cache")), Triple(codeCache.role, codeCache.documented, codeCache.sizeBytes))
        assertTrue(!folder(cur, "$dir/Library/Caches/Cursor").documented)
        val cp = agent(report, "copilot")
        assertEquals(copilot, cp.items[0].path)
        assertEquals(diskUsage(copilot), cp.freedDefaultBytes)
        val rtok = agent(report, "rtok")
        val own = rtok.items.map { it.path to it.evidence }
        assertTrue((rtokCache to "rtok-owned") in own, "$own")
        // The registry stores a canonical root, so `/var` and `/private/var` are one cache.
        assertTrue(own.any { (p, e) -> p.endsWith(".rtok-lsp-xdg/cache") && e == "rtok-owned" }, "$own")
        assertEquals("cache", folder(rtok, rtokCache).role)
        assertEquals(listOf(c, cp, rtok).sumOf { it.freedDefaultBytes }, report.freedDefaultBytes, "Cursor's caches add nothing")
        val text = JunkList.toList(report, JunkView(exact = true))
        assertTrue(text.contains("cache (safe): 2 items"), text)
        assertTrue(text.contains("$cursor/Code Cache  cache  ${codeCache.sizeBytes}  not documented: not cleared"), text)
        assertEquals(before, walkNames(dir), "a report changes no file")
    }

    @Test
    fun aSharedTaggedCacheIsFreedOnceAndASymlinkedCacheIsSkipped() {
        val (cfg, dir) = agentsConfig("junk-cache-shared")
        val shared = "$dir/shared"
        write("$shared/t/o", PAGE)
        tagged("$shared/t")
        ageFiles(shared, AGED_DAYS * DAY)
        write("$dir/outside/big", MIB)
        createDirAll("$dir/c")
        symlink("$dir/outside", "$dir/c/paste-cache")
        val env = mapOf("CLAUDE_CONFIG_DIR" to shared, "CODEX_HOME" to shared)
        val report = Junk.reportWith(cfg, roots(dir, env), everyHost(), JunkCache.AGENT_SCAN_LIMIT)
        for (name in listOf("claude", "codex")) assertEquals(listOf("$shared/t"), agent(report, name).items.map { it.path })
        assertEquals(diskUsage("$shared/t"), report.freedDefaultBytes)
        val linked = Junk.reportWith(cfg, roots(dir, mapOf("CLAUDE_CONFIG_DIR" to "$dir/c")), JunkOptions(all = true), JunkCache.AGENT_SCAN_LIMIT)
        assertTrue(agent(linked, "claude").items.isEmpty(), "no link is followed out")
    }

    @Test
    fun tempBuildAndStagingCountWhileLocksAndSwapAreListedOnly() {
        val (cfg, dir) = agentsConfig("junk-kinds")
        val claude = "$dir/.claude"
        write("$claude/settings.json", SMALL)
        write("$claude/shell-snapshots/old.sh", SNAPSHOT)
        write("$claude/shell-snapshots/new.sh", FIVE)
        write("$claude/session.lock", TINY)
        write("$claude/Cargo.lock", TINY)
        write("$claude/x/.edit.swp", TINY)
        write("$claude/plugins/cache/rtok/rtok/0.1.0/p", PLUGIN)
        write("$claude/plugins/cache/rtok/rtok/0.2.0/p", PLUGIN)
        val used = "$claude/plugins/cache/rtok/rtok/0.2.0"
        writeString("$claude/plugins/installed_plugins.json", """{"plugins":{"rtok@rtok":[{"installPath":"$used"}]}}""")
        val wt = "$dir/wt"
        write("$wt/target/o", BUILD)
        tagged("$wt/target")
        write("$wt/dist/app.js", SMALL)
        ageFiles("$claude/shell-snapshots", AGED_DAYS * DAY)
        ageFiles("$claude/plugins", AGED_DAYS * DAY)
        ageFiles(wt, AGED_DAYS * DAY)
        // Fresh again: only the old one may go.
        write("$claude/shell-snapshots/new.sh", FIVE)
        // The scan opens the store, as the cache fixture does by registering a project.
        Store.open(cfg.core.dbPath).close()
        val before = walkNames(dir)

        val report = Junk.reportWith(cfg, roots(dir), everyHost(listOf("claude" to wt)), JunkCache.AGENT_SCAN_LIMIT)

        val c = agent(report, "claude")
        assertEquals(listOf("temp" to 1, "build" to 1), c.kinds.map { it.kind to it.items })
        val want = diskUsage("$claude/shell-snapshots/old.sh") + diskUsage("$wt/target")
        assertEquals(want, c.freedDefaultBytes)
        val listed = c.items.filter { !it.counted }.map { it.kind to it.path.substringAfterLast('/') }
        for (row in listOf("locks" to "session.lock", "swap" to ".edit.swp", "build" to "dist")) assertTrue(row in listed, "$listed")
        assertTrue(c.items.none { it.path.endsWith("Cargo.lock") })
        val stale = "$claude/plugins/cache/rtok/rtok/0.1.0"
        val paths = agent(report, "rtok").items.filter { it.counted }.map { it.path }
        assertTrue(stale in paths, "$paths")
        assertTrue(paths.none { it.endsWith("0.2.0") })
        assertEquals(want + diskUsage(stale), report.freedDefaultBytes)
        val text = JunkList.toList(report, JunkView(exact = true))
        assertTrue(text.contains("temp (safe): 1 items"), text)
        assertTrue(text.contains("locks: 1 listed"), text)
        assertTrue(text.contains(".edit.swp") && text.contains("(owner unknown)"), text)
        assertEquals(before, walkNames(dir), "a report changes no file")
    }

    @Test
    fun sessionsAndIndexRowsFollowTheirClasses() {
        val (cfg, dir) = agentsConfig("junk-sessions-index")
        val id = "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0"
        val claude = "$dir/.claude"
        write("$claude/projects/p/$id.jsonl", LOG)
        write("$claude/projects/p/state.db-wal", SMALL)
        write("$claude/projects/p/.rust-analyzer/db", SMALL)
        val project = "$dir/proj"
        write("$project/.rtok-lsp-xdg/data/idx", LSP)
        write("$project/.rtok-lsp-xdg/state/s", STATE)
        Store.open(cfg.core.dbPath).use { it.registerProject(project, Origin.Manual) }
        for (d in listOf(claude, project)) ageFiles(d, SESSION_DAYS * DAY)

        val report = Junk.reportWith(cfg, roots(dir), everyHost(), JunkCache.AGENT_SCAN_LIMIT)

        val c = agent(report, "claude")
        val sessions = c.kinds.first { it.kind == "sessions" }
        assertEquals("explicit" to 1, sessions.cls to sessions.items)
        assertEquals(0, c.freedReviewBytes, "an explicit kind is in no total")
        val index = c.items.filter { it.kind == "index" && !it.counted }
        assertEquals(2, index.size, "${c.items}")
        assertTrue(index.all { it.kept == JunkKinds.NOT_DOCUMENTED })
        assertEquals("old sessions: not touched for more than 30 days", c.notes[0])
        assertTrue(c.notes[1].startsWith("host retention: cleanupPeriodDays"))
        val own = agent(report, "rtok")
        val ownIndex = own.kinds.first { it.kind == "index" }
        assertEquals("review" to 2, ownIndex.cls to ownIndex.items)
        assertEquals(0, own.freedDefaultBytes)
        assertTrue(own.freedReviewBytes >= LOG)
        val text = JunkList.toList(report, JunkView(exact = true))
        assertTrue(text.contains("sessions (explicit): 1 items"), text)
        assertTrue(text.contains("index (review): 2 items"), text)
    }
}
