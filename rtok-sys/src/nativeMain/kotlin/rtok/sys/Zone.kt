// IANA time zones over the C library (`localtime_r`/`mktime` with `TZ` set for the call), the part of jiff's
// `TimeZone` that day and month buckets need. The zone files are the system's tzdata (`$TZDIR`, else
// /usr/share/zoneinfo); nothing is bundled. `TZ` is process-wide, so a call swaps it and puts it back.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package rtok.sys

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.getenv
import platform.posix.localtime_r
import platform.posix.mktime
import platform.posix.setenv
import platform.posix.time_tVar
import platform.posix.tm
import platform.posix.tzset
import platform.posix.unsetenv

/** A calendar day (proleptic Gregorian), printed `YYYY-MM-DD` like jiff's `Date`. */
data class CivilDate(
    val year: Int,
    val month: Int,
    val day: Int,
) {
    override fun toString() = "${year.toString().padStart(YEAR_DIGITS, '0')}-${pad(month)}-${pad(day)}"

    companion object {
        private const val YEAR_DIGITS = 4
        private const val MONTHS = 12
        private const val FEB = 2
        private val DAYS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        private val SHAPE = Regex("""(\d{4})-(\d{2})-(\d{2})""")

        private fun pad(n: Int) = n.toString().padStart(2, '0')

        private const val LEAP = 4
        private const val CENTURY = 100
        private const val QUAD_CENTURY = 400

        private fun leap(y: Int) = (y % LEAP == 0 && y % CENTURY != 0) || y % QUAD_CENTURY == 0

        fun daysIn(
            year: Int,
            month: Int,
        ) = if (month == FEB && leap(year)) DAYS[1] + 1 else DAYS[month - 1]

        /** `2026-09-30`; null for any other shape or a day the month does not have. */
        fun parse(s: String): CivilDate? {
            val (y, m, d) = SHAPE.matchEntire(s)?.destructured ?: return null
            val date = CivilDate(y.toInt(), m.toInt(), d.toInt())
            return date.takeIf { it.month in 1..MONTHS && it.day in 1..daysIn(it.year, it.month) }
        }
    }

    fun tomorrow(): CivilDate =
        when {
            day < daysIn(year, month) -> copy(day = day + 1)
            month < MONTHS -> CivilDate(year, month + 1, 1)
            else -> CivilDate(year + 1, 1, 1)
        }
}

/** A time zone: a tzdata [name], or null for the system zone (`TZ`, else /etc/localtime). */
class Zone private constructor(
    private val name: String?,
) {
    companion object {
        private const val TM_YEAR = 1900
        private const val MAGIC = "TZif"

        fun system() = Zone(null)

        /** The zone named [tz] (`Europe/Kyiv`); null when the system's tzdata has no such zone. */
        fun named(tz: String): Zone? = Zone(tz).takeIf { known(tz) }

        private fun dir() = getenv("TZDIR")?.toKString()?.takeIf { it.isNotEmpty() } ?: "/usr/share/zoneinfo"

        private fun known(tz: String) =
            tz.isNotEmpty() && !tz.startsWith('/') && ".." !in components(tz) &&
                readPrefix(join(dir(), tz), MAGIC.length.toLong())?.decodeToString() == MAGIC
    }

    /** jiff `iana_name`: the zone's tzdata name; for the system zone, `TZ` or the /etc/localtime link, else null. */
    val iana: String?
        get() =
            name ?: getenv("TZ")?.toKString()?.removePrefix(":")?.takeIf(::known)
                ?: readLink("/etc/localtime")?.substringAfter("zoneinfo/", "")?.takeIf(::known)

    /** The day [epoch] (unix seconds) falls on in this zone. */
    fun date(epoch: Long): CivilDate =
        within {
            memScoped {
                val t = alloc<time_tVar>().apply { value = epoch.convert() }
                val out = alloc<tm>()
                localtime_r(t.ptr, out.ptr)
                CivilDate(out.tm_year + TM_YEAR, out.tm_mon + 1, out.tm_mday)
            }
        }

    /** The first second of [day] in this zone (a midnight skipped by a DST jump resolves as the C library does). */
    fun startOf(day: CivilDate): Long =
        within {
            memScoped {
                val t = alloc<tm>()
                t.tm_year = day.year - TM_YEAR
                t.tm_mon = day.month - 1
                t.tm_mday = day.day
                t.tm_isdst = -1
                mktime(t.ptr).convert()
            }
        }

    private fun <T> within(body: () -> T): T {
        if (name == null) return body().also { tzset() }
        val was = getenv("TZ")?.toKString()
        setenv("TZ", name, 1)
        tzset()
        try {
            return body()
        } finally {
            if (was == null) unsetenv("TZ") else setenv("TZ", was, 1)
            tzset()
        }
    }
}
