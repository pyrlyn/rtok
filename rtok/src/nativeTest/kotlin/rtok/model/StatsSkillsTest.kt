// Port of the src/model/tests.rs Skills/Stats tests: `skills_from` joins the listing to the scan (T63.1), and the
// snapshot never waits on the transcript scan (T947); a scan for another window is not shown.
@file:Suppress("MagicNumber")

package rtok.model

import rtok.doctor.SkillRow
import rtok.doctor.SkillsAudit
import rtok.measure.stats.SortedMap
import rtok.support.diskConfig
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import rtok.measure.stats.SkillRow as ScanRow

class StatsSkillsTest {
    private fun listed(
        name: String,
        source: String,
        desc: Int,
        body: Long,
        calls: Long?,
    ) = SkillRow(name, source, desc, body, calls, warnNever = calls == 0L)

    @Test fun skillsFromJoinsListingAndResidentWithoutStatsRows() {
        val listing = SkillsAudit(rows = listOf(listed("hot", "user", 40, 100, 3), listed("cold", "project", 10, 20, 0), listed("plug", "plugin:x", 8, 50, 1)), descBytes = 58)
        val stats = SortedMap<ScanRow>()
        stats["hot"] = ScanRow(3, 100, 33, 100, 100, 25, 800)
        stats["plug"] = ScanRow(1, 50, 50, 50, 50, 12, 200)
        val page = StatsSkillsPage.skillsFrom(listing, stats, 10_000, true)
        assertEquals(listOf("hot", "plug", "cold"), page.rows.map { it.name })
        assertTrue(page.rows[2].never && page.rows[2].lastInvoked == "never")
        assertEquals(800, page.rows[0].resident)
        assertTrue("3 skills" in page.header)
        assertTrue("58 desc bytes ≈ 14 tok/req" in page.header, page.header)
        val empty = StatsSkillsPage.skillsFrom(null, null, 0, false)
        assertTrue(empty.rows.isEmpty() && empty.header.startsWith("0 skills"))
    }

    private fun <T> until(step: () -> T?): T {
        val end = TimeSource.Monotonic.markNow() + 10.seconds
        while (true) {
            step()?.let { return it }
            check(end.hasNotPassedNow()) { "the scan never landed" }
            platform.posix.usleep(5_000u)
        }
    }

    @Test fun statsSkillsNeverWaitsOnTheTranscriptScan() {
        val cache = Background<Pair<String, StatsSkills>>("stats-skills-test")
        val release = AtomicInt(0)
        val first =
            StatsSkillsPage.from(cache, "dir30d") {
                while (release.value == 0) platform.posix.usleep(1_000u)
                StatsSkills(null, 7, true, "scanned\n")
            }
        assertEquals(StatsSkillsPage.READING, first.text)
        assertFalse(first.scanned, "no scan has landed yet")
        release.value = 1
        val now = until { StatsSkillsPage.from(cache, "dir30d") { StatsSkills(null, 0, true, "rescanned\n") }.takeIf { it.scanned } }
        assertEquals(7L to "scanned\n", now.usageInput to now.text)
        val other = StatsSkillsPage.from(cache, "dir7d") { StatsSkills(null, 1, true, null) }
        assertEquals(StatsSkillsPage.READING, other.text, "a scan for another window is not shown")
    }

    @Test fun theSnapshotCarriesStatsAndSkills() {
        val snap = Snapshot.build(diskConfig("stats-skills-snap").first)
        assertTrue("skills" in snap && "stats" in snap, snap.keys.toString())
    }
}
