// Port of src/agents/junk_clear.rs tests: the class gate, the plan's filters, `--older-than`, the re-check before a
// delete, nested lockfiles (T577), the lockfile walk, a live session as a running host, and the dry run text.
package rtok.agents

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.chmod
import rtok.config.Config
import rtok.store.Store
import rtok.store.hostId
import rtok.store.registerAgent
import rtok.support.diskConfig
import rtok.support.installDb
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.fileName
import rtok.sys.isDir
import rtok.sys.isFile
import rtok.sys.listDir
import rtok.sys.parent
import rtok.sys.symlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val DAY = 86_400L
private const val WEEK_DAYS = 7
private const val TEN_DAYS = 10
private const val CLOSED = 0
private const val OPEN = 493
private const val SMALL = 3
private const val TEN = 10
private const val BIG = 100
private const val FOUR = 4
private const val FREED = 20L
private const val MINUTE = 60

private fun put(
    path: String,
    n: Int,
) = putFile(path, ByteArray(n) { 'x'.code.toByte() })

private fun item(
    kind: String,
    path: String,
    evidence: String,
    cls: String = "safe",
) = JunkItem(kind, cls, path, TEN.toLong(), evidence)

private fun row(
    name: String,
    items: List<JunkItem>,
) = AgentJunk(name, name != "rtok", true, emptyList(), 0, emptyList(), items, emptyList(), 0, 0)

private fun report(agents: List<AgentJunk>) = JunkReport(agents, emptyList(), 0, 0, 0)

private fun filter(
    agents: List<String>,
    kinds: List<String>,
) = JunkFilter(agents = agents, kinds = kinds)

private fun name(p: Planned) = fileName(p.path)!!

private fun rows(items: List<Planned>) = items.map { listOf(it.agent, it.kind, it.action, name(it)) }

private val idle: (String) -> Boolean = { false }

private fun config(tag: String): Pair<Config, String> {
    installDb()
    return diskConfig(tag)
}

class JunkClearTest {
    @Test
    fun reviewKindsNeedTheFlagExtraPathsPlanAndSnapshotsNeverDo() {
        val (cfg, dir) = config("junk-clear-review")
        val r =
            report(
                listOf(
                    row(
                        "claude",
                        listOf(
                            item("logs", "$dir/old.log", JunkCache.SECTION_22, "review"),
                            item("temp", "$dir/old.tmp", JunkReview.EXTRA),
                            item("logs", "$dir/app.log", JunkReview.EXTRA, "review"),
                            item("backups", "$dir/s.json.bak-1", JunkCache.RTOK_OWN, "review"),
                            item("snapshots", "$dir/history", JunkCache.SECTION_22, "never").copy(kept = "snapshot: never cleared"),
                        ),
                    ),
                ),
            )
        val now = Junk.nowNanos()
        val names = { f: JunkFilter, running: (String) -> Boolean -> JunkClear.plan(cfg, r, f, now, running).filter { it.planned }.map(::name) }
        assertEquals(listOf("old.tmp"), names(filter(listOf("claude"), emptyList()), idle))
        val review = filter(listOf("claude"), emptyList()).copy(includeReview = true)
        assertEquals(listOf("old.log", "old.tmp", "app.log", "s.json.bak-1"), names(review, idle))
        assertEquals(listOf("old.log", "app.log"), names(filter(emptyList(), listOf("logs")), idle))
        assertTrue(names(filter(emptyList(), listOf("snapshots")), idle).isEmpty())
        // Running: the §22 log and the `extra` paths stay, rtok's own backup does not.
        assertEquals(listOf("s.json.bak-1"), names(review) { true })
    }

    @Test
    fun theClassGateAndTheT182Selection() {
        val f = filter(emptyList(), emptyList())
        assertTrue(f.wantsKind("cache", "safe") && !f.wantsKind("logs", "review"))
        assertTrue(!f.wantsKind("sessions", "explicit") && !f.wantsKind("snapshots", "never"))
        val review = f.copy(includeReview = true)
        assertTrue(review.wantsKind("logs", "review") && !review.wantsKind("sessions", "explicit"))
        val named = filter(emptyList(), listOf("sessions", "logs"))
        assertTrue(named.wantsKind("sessions", "explicit") && named.wantsKind("logs", "review"))
        assertTrue(!named.wantsKind("cache", "safe") && !named.wantsKind("snapshots", "never"))
        assertTrue(f.isT182 && filter(listOf("rtok"), emptyList()).isT182)
        assertTrue(!filter(listOf("claude"), emptyList()).isT182 && !filter(emptyList(), listOf("cache")).isT182)
        assertEquals("rtok", JunkClear.agentArg("rtok"))
        assertEquals("claude", JunkClear.agentArg("claude"))
        assertTrue(runCatching { JunkClear.agentArg("nope") }.isFailure)
    }

    @Test
    fun thePlanFollowsTheFiltersRunningHostsAndEvidence() {
        val (cfg, dir) = config("junk-clear-plan")
        val r =
            report(
                listOf(
                    row("rtok", listOf(item("cache", "$dir/rtok-cache", JunkCache.RTOK_OWN))),
                    row(
                        "claude",
                        listOf(
                            item("temp", "$dir/snap", JunkCache.SECTION_22),
                            item("cache", "$dir/cc-cache", JunkCache.SECTION_22),
                            item("build", "$dir/target", JunkCache.TAG),
                            item("locks", "$dir/x.lock", "none"),
                            item("cache", "$dir/fresh", JunkCache.TAG).copy(kept = "modified within the idle window"),
                        ),
                    ),
                    row("codex", listOf(item("cache", "$dir/cc-cache", JunkCache.SECTION_22))),
                ),
            )
        val now = Junk.nowNanos()
        val every = JunkFilter(includeReview = true)
        val all = JunkClear.plan(cfg, r, every, now, idle)
        assertEquals(listOf(listOf("rtok", "cache", "clear", "rtok-cache")), rows(all).take(1))
        assertEquals(FOUR, all.size, "shared, kept and undocumented ones drop: ${rows(all)}")
        assertTrue(JunkClear.plan(cfg, r, filter(listOf("rtok"), emptyList()), now, idle).isEmpty(), "no log or archive in the fixture")
        val cache = JunkClear.plan(cfg, r, filter(listOf("claude"), listOf("cache")), now, idle)
        assertEquals(listOf(listOf("claude", "cache", "clear", "cc-cache")), rows(cache))
        val busy = JunkClear.plan(cfg, r, filter(listOf("claude"), emptyList()), now) { it == "claude" }
        assertEquals(
            listOf(
                listOf("claude", "temp", "skip", "snap"),
                listOf("claude", "cache", "skip", "cc-cache"),
                listOf("claude", "build", "clear", "target"),
            ),
            rows(busy),
        )
        assertEquals(JunkClear.RUNNING, busy[0].note)
        assertTrue(!busy[0].planned && !busy[0].failed)
        val shared = JunkClear.plan(cfg, r, every, now) { it == "claude" }
        val cc = shared.filter { it.path.endsWith("cc-cache") }.map { it.agent to it.action }
        assertEquals(listOf("claude" to "skip"), cc, "a shared folder of a running agent stays")
    }

    @Test
    fun olderThanTakesOnlyWhatWasNotTouchedForThatLong() {
        val (cfg, dir) = config("junk-clear-older")
        put("$dir/old/a", SMALL)
        put("$dir/new/a", SMALL)
        ageFiles("$dir/old", TEN_DAYS * DAY)
        ageFiles("$dir/new", 2 * DAY)
        val r = report(listOf(row("claude", listOf(item("temp", "$dir/old", JunkCache.SECTION_22), item("temp", "$dir/new", JunkCache.SECTION_22)))))
        val f = JunkFilter(olderThan = (WEEK_DAYS * DAY).seconds)
        assertEquals(listOf(listOf("claude", "temp", "clear", "old")), rows(JunkClear.plan(cfg, r, f, Junk.nowNanos(), idle)))
        assertEquals(maxOf(cfg.core.retainCallsDays, WEEK_DAYS.toUInt()), JunkClear.retentionDays(cfg, f))
    }
}

class JunkClearApplyTest {
    @Test
    fun applyRechecksEveryItemAndKeepsWhatChangedOrIsProtected() {
        val (cfg, dir) = config("junk-clear-apply")
        val p = { n: String -> "$dir/$n" }
        put(p("cache/blob"), BIG)
        put(p("cache/sub/blob"), BIG)
        tagged(p("cache"))
        put(p("temp/old"), TEN)
        put(p("busy/f"), TEN)
        put(p("lock/Cargo.lock"), TEN)
        put(cfg.core.dbPath, TEN)
        put(p("outside/important"), TEN)
        for (d in listOf("cache", "temp", "lock")) ageFiles(p(d), 2 * DAY)
        symlink(p("outside"), p("link"))
        val dbDir = parent(cfg.core.dbPath)!!
        val items =
            listOf(
                item("cache", p("cache"), JunkCache.TAG),
                item("temp", p("temp/old"), JunkCache.SECTION_22),
                item("temp", p("busy/f"), JunkCache.SECTION_22),
                item("temp", p("lock/Cargo.lock"), JunkCache.SECTION_22),
                item("cache", dbDir, JunkCache.RTOK_OWN),
                item("temp", p("gone"), JunkCache.SECTION_22),
                item("temp", p("link"), JunkCache.SECTION_22),
            )
        val planned = JunkClear.plan(cfg, report(listOf(row("claude", items))), filter(listOf("claude"), emptyList()), Junk.nowNanos(), idle)
        val before = planned.size
        JunkClear.apply(cfg, planned, JunkFilter(), Junk.nowNanos())
        assertEquals(before, planned.size)
        val note = { n: String -> planned.first { it.path.endsWith(n) }.let { Triple(it.action, it.note, it.failed) } }
        assertEquals(Triple("clear", "removed", false), note("cache"))
        assertTrue(isDir(p("cache")) && isFile(p("cache/CACHEDIR.TAG")))
        assertTrue(!exists(p("cache/blob")) && !exists(p("cache/sub")))
        assertEquals(Triple("clear", "removed", false), note("old"))
        assertFalse(exists(p("temp/old")))
        assertEquals(Triple("skip", "modified in the last minute", true), note("f"))
        assertEquals(Triple("skip", "is or holds a protected file (store, settings, lockfile)", true), note("Cargo.lock"))
        assertEquals("skip", note(fileName(dbDir)!!).first)
        assertTrue(isFile(cfg.core.dbPath) && isFile(p("lock/Cargo.lock")))
        assertEquals(Triple("skip", "already gone", false), note("gone"))
        assertEquals(Triple("skip", "a symlink: never followed", true), note("link"))
        assertTrue(isFile(p("outside/important")))
        val c = JunkClear.summed(planned, true)
        assertTrue(c.failed)
        assertEquals(FREED, c.freedBytes)
        val text = JunkClear.toText(c, JunkView())
        assertTrue(text.contains("claude cache: freed 10 B of 10 B planned"), text)
        assertTrue(text.endsWith("Freed 20 B of 60 B planned\n"), text)
    }

    @Test
    fun aFolderHoldingANestedLockfileIsNeitherPlannedNorEmptied() {
        val (cfg, dir) = config("junk-clear-nested-lock")
        val p = { n: String -> "$dir/$n" }
        put(p("direct/Cargo.lock"), TEN)
        put(p("deep/a/b/yarn.lock"), TEN)
        put(p("deep/blob"), TEN)
        put(p("clean/sub/blob"), TEN)
        put(p("late/blob"), TEN)
        val dirs = listOf("direct", "deep", "clean", "late")
        dirs.forEach { ageFiles(p(it), 2 * DAY) }
        val r = report(listOf(row("claude", dirs.map { item("cache", p(it), JunkReview.EXTRA) })))
        val planned = JunkClear.plan(cfg, r, filter(listOf("claude"), emptyList()), Junk.nowNanos(), idle)
        val by = { n: String -> planned.first { it.path.endsWith(n) }.let { Triple(it.action, it.planned, it.note) } }
        val why = "holds a package-manager lockfile"
        for ((d, lock) in listOf("direct" to "Cargo.lock", "deep" to "a/b/yarn.lock")) {
            val (action, isPlanned, note) = by(d)
            assertEquals("skip" to false, action to isPlanned, d)
            assertTrue(note.startsWith(why) && note.contains(lock), note)
        }
        assertEquals("clear", by("clean").first)
        put(p("late/x/Cargo.lock"), TEN)
        ageFiles(p("late"), 2 * DAY)
        JunkClear.apply(cfg, planned, JunkFilter(), Junk.nowNanos())
        assertEquals(Triple("clear", true, "removed"), by("clean"))
        assertTrue(!exists(p("clean/sub")) && isDir(p("clean")))
        val late = by("late")
        assertEquals("skip" to true, late.first to late.second)
        assertTrue(late.third.startsWith(why), late.third)
        for (kept in listOf("direct/Cargo.lock", "deep/a/b/yarn.lock", "deep/blob")) assertTrue(isFile(p(kept)), kept)
        assertTrue(isFile(p("late/blob")) && isFile(p("late/x/Cargo.lock")))
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun theLockfileWalkIgnoresLinksAndRefusesWhatItCannotRead() {
        val (_, dir) = config("junk-clear-lock-walk")
        put("$dir/outside/Cargo.lock", 1)
        put("$dir/cache/blob", 1)
        symlink("$dir/outside", "$dir/cache/link")
        val far = TimeSource.Monotonic.markNow() + MINUTE.seconds
        assertEquals(null, JunkClearCheck.lockfileUnder("$dir/cache", far).getOrThrow())
        val closed = "$dir/cache/closed"
        createDirAll(closed)
        chmod(closed, CLOSED.toUShort().convert())
        val readable = runCatching { listDir(closed) }.isSuccess
        val res = JunkClearCheck.lockfileUnder("$dir/cache", far)
        chmod(closed, OPEN.toUShort().convert())
        // Running as root reads everything; the refusal is only observable otherwise.
        assertTrue(readable || res.isFailure, "$res")
        assertTrue(JunkClearCheck.lockfileUnder("$dir/cache", TimeSource.Monotonic.markNow()).isFailure)
    }

    @Test
    fun aLiveRtokSessionMarksItsHostRunning() {
        val (cfg, _) = config("junk-clear-live")
        Store.open(cfg.core.dbPath).use { store ->
            val claude = store.hostId("claude")!!
            store.registerAgent(claude, "s", null, null, null)
        }
        assertTrue(JunkClear.running(cfg)("claude"))
    }

    @Test
    fun aDryRunChangesNothingAndSaysSo() {
        val (cfg, dir) = config("junk-clear-dry")
        put("$dir/cache/blob", TEN)
        val r = report(listOf(row("claude", listOf(item("cache", "$dir/cache", JunkCache.SECTION_22)))))
        val planned = JunkClear.plan(cfg, r, filter(listOf("claude"), emptyList()), Junk.nowNanos(), idle)
        val text = JunkClear.toText(JunkClear.summed(planned, false), JunkView())
        assertTrue(isFile("$dir/cache/blob"))
        assertTrue(text.contains("claude cache: 10 B planned"), text)
        assertTrue(text.endsWith("dry run: 1 items, 10 B to free, nothing changed; rerun with --yes\n"), text)
        assertEquals("nothing to clear", JunkClear.toText(JunkClear.summed(emptyList(), true), JunkView()).lines().last { it.isNotEmpty() })
    }
}
