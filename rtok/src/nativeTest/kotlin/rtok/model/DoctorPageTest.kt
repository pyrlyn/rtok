// Port of src/model/mod.rs `doctor_from` tests (T945): pending before the first probe lands, then the report; a failed
// probe is null and not pending; a cached report for other doctor paths is dropped.
@file:Suppress("MagicNumber")

package rtok.model

import rtok.doctor.Report
import rtok.support.diskConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class DoctorPageTest {
    private fun <T> until(
        step: () -> T?,
    ): T {
        val end = TimeSource.Monotonic.markNow() + 30.seconds
        while (true) {
            step()?.let { return it }
            check(end.hasNotPassedNow()) { "timed out" }
            platform.posix.usleep(5_000u)
        }
    }

    @Test fun pendingThenAFailedProbeIsNullAndNotPending() {
        val cfg = diskConfig("doctor-page-fail").first
        assertTrue(cfg.doctor.settingsPath.startsWith(diskConfig("doctor-page-probe").second.substringBeforeLast('/')), cfg.doctor.settingsPath)
        val first = DoctorPage.read(cfg) { null }
        assertTrue(first.second, "pending before the first probe lands")
        val landed = until { DoctorPage.read(cfg) { null }.takeIf { !it.second } }
        assertNull(landed.first)
        val snap = Snapshot.build(cfg)
        assertEquals("doctor probe failed", (snap["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test fun anotherDoctorPathDropsTheCachedReport() {
        val a = diskConfig("doctor-page-paths").first
        until { DoctorPage.read(a) { null as Report? }.takeIf { !it.second } }
        val b = a.copy(doctor = a.doctor.copy(settingsPath = a.doctor.settingsPath + ".other"))
        assertTrue(DoctorPage.read(b) { null }.second, "a report for other paths is not shown")
    }
}
