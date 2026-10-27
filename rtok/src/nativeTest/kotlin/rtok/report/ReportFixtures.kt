// Port of src/report/mod.rs `fixtures` (T105): fixed documents for the renderer snapshots: zero rows, one row with
// very large numbers and non-finite latencies, and hostile text. Every field is a literal: no store, no clock.
@file:Suppress("MagicNumber")

package rtok.report

import rtok.doctor.Report
import rtok.model.ReportCache
import rtok.model.ReportCalls
import rtok.model.ReportCallsSection
import rtok.model.ReportExpand
import rtok.model.ReportLedgers
import rtok.model.ReportSavings
import rtok.model.ReportSavingsSection
import rtok.model.ReportSinksSection
import rtok.model.ReportWindow

object ReportFixtures {
    const val EVIL = "<script>alert(1)</script> | `tick`\nsecond line"

    private fun base() =
        Document(
            ReportLedgers(
                window = ReportWindow("30d", 0, 0, "2026-09-01", "2026-10-01", "/tmp/rtok.db", 0u, 0u, 0u, 0u),
                savings = ReportSavingsSection(emptyList(), 0u, 0, emptyList()),
                sinks = ReportSinksSection(emptyList()),
                calls = ReportCallsSection(emptyList(), 0u, 0u, emptyList()),
                cache = ReportCache(0u, 0u, 0u, emptyList(), emptyList()),
                lanes = emptyList(),
                expand = ReportExpand(0, 0, 0.0, emptyList(), 0, 0u),
            ),
            emptyList(),
            Report(),
            emptyList(),
        )

    fun zero() = base()

    fun oneRow(): Document {
        val b = base()
        val l = b.ledgers
        return b.copy(
            ledgers =
                l.copy(
                    window = l.window.copy(callsInWindow = 1u, callsTotal = 1u, measurements = ULong.MAX_VALUE, usage = ULong.MAX_VALUE),
                    savings = ReportSavingsSection(listOf(ReportSavings("cmd", ULong.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE)), ULong.MAX_VALUE, Long.MAX_VALUE, emptyList()),
                    calls = ReportCallsSection(listOf(ReportCalls("hook", ULong.MAX_VALUE, 1u, Double.NaN, Double.POSITIVE_INFINITY)), 1u, 1u, emptyList()),
                    cache = ReportCache(1u, ULong.MAX_VALUE, ULong.MAX_VALUE, listOf("tools" to ULong.MAX_VALUE), emptyList()),
                    expand = ReportExpand(Long.MAX_VALUE, Long.MAX_VALUE, 1.0, listOf("f".repeat(64)), Long.MAX_VALUE, ULong.MAX_VALUE),
                ),
            config = listOf(ConfigEntry("core.db_path", "/tmp/rtok.db", "user")),
            recommendations = listOf(Recommendation("cache-bust", "${Long.MAX_VALUE} tokens", "${ULong.MAX_VALUE} rows")),
        )
    }

    fun hostile(): Document {
        val b = base()
        val l = b.ledgers
        return b.copy(
            ledgers =
                l.copy(
                    window = l.window.copy(dbPath = EVIL),
                    savings = ReportSavingsSection(listOf(ReportSavings(EVIL, 1u, 1, 1, 0)), 1u, 0, emptyList()),
                    cache = ReportCache(1u, 1u, 0u, listOf(EVIL to 1uL), emptyList()),
                    expand = ReportExpand(1, 0, Double.NaN, listOf(EVIL), 0, 0u),
                ),
            config = listOf(ConfigEntry(EVIL, EVIL, EVIL)),
            doctor = Report(proxy = EVIL),
            recommendations = listOf(Recommendation(EVIL, EVIL, EVIL)),
        )
    }
}
