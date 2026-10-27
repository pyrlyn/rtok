// Port of src/model/mod.rs `doctor_for_snapshot` / `doctor_from` (T15.11, T945): the Doctor page's report for the
// snapshot. The probes (MCP spawns, the transcript read share) run on a Background worker, never on the tick; the
// snapshot ships the last known report for 30 s, or pending before the first one lands. A cached report for other
// doctor paths is dropped, not shown. `rtok doctor` itself still probes uncached.
package rtok.model

import kotlinx.serialization.json.JsonObject
import rtok.config.Config
import rtok.doctor.DoctorJson
import rtok.doctor.Page
import rtok.doctor.Report
import rtok.sys.currentExe
import kotlin.time.Duration.Companion.seconds

object DoctorPage {
    /** DOCTOR_SNAPSHOT_TTL: far longer than the tick, so MCP spawns are not the tick. */
    val TTL = 30.seconds

    /** The doctor paths a report was read for, and the report (null when the probe failed). */
    class Read(
        val key: String,
        val report: Report?,
    )

    private val cache = Background<Read>("web-doctor")

    /** `DoctorRead`: the report (null when the probe failed) and whether no probe has landed yet. */
    fun read(
        cfg: Config,
        probe: (Config) -> Report? = ::probe,
    ): Pair<Report?, Boolean> {
        val key = with(cfg.doctor) { "$settingsPath\u0000$claudeJson\u0000$mcpJson" }
        val got = cache.get(TTL) { Read(key, probe(cfg)) }
        return when {
            got == null -> null to true
            got.key != key -> (null to true).also { cache.forget() }
            else -> got.report to false
        }
    }

    /** The snapshot's `doctor` field (`Report` as the page reads it) and `doctor_pending`. */
    fun get(
        cfg: Config,
        probe: (Config) -> Report? = ::probe,
    ): Pair<JsonObject?, Boolean> = read(cfg, probe).let { (r, pending) -> r?.let(DoctorJson::of) to pending }

    private fun probe(cfg: Config): Report? = runCatching { Page.page(cfg, ownExe()) }.getOrNull()

    // Rust probes its own binary as the `rtok` MCP server, but not under test: there current_exe is the test binary.
    private fun ownExe() = currentExe()?.takeIf { it.substringAfterLast('/') == "rtok" }
}
