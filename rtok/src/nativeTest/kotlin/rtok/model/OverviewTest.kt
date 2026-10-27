package rtok.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.plugin.sdk.Measurement
import rtok.store.Store
import rtok.store.insertMeasurement
import rtok.support.installDb
import rtok.sys.Zone
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BEFORE = 100u
private const val AFTER = 40u
private const val SAVED = 60L

class OverviewTest {
    private fun cfg(tag: String): Pair<Config, String> {
        installDb()
        val dir = "${tempDir()}/rtok-overview-$tag-${platform.posix.getpid()}"
        removeDirAll(dir)
        createDirAll(dir)
        val base = Config()
        return base.copy(core = base.core.copy(dbPath = "$dir/rtok.db")) to dir
    }

    @Test fun emptyStoreIsZerosAndFourteenGapDays() {
        val (c, dir) = cfg("empty")
        try {
            val u = Snapshot.build(c)["usage"] as JsonObject
            assertEquals("0", u["input"]!!.jsonPrimitive.content)
            assertEquals("0", u["ctt"]!!.jsonPrimitive.content)
            assertTrue("alerts" !in u)
            val days = u["savings"] as JsonArray
            assertEquals(Overview.SAVINGS_DAYS, days.size)
            assertEquals(JsonNull, (days.last() as JsonObject)["saved"])
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun todaysMeasurementLandsOnTheLastDay() {
        val (c, dir) = cfg("trend")
        try {
            val store = Store.open(c.core.dbPath)
            try {
                store.insertMeasurement("s", Measurement("compress", "output", 1u, 1u, BEFORE, AFTER))
                val tz = Zone.named("UTC") ?: Zone.system()
                val days = Overview.savingsTrend(store, rtok.log.now(), tz)
                assertEquals(Overview.SAVINGS_DAYS, days.size)
                assertEquals(SAVED, days.last().saved)
                assertEquals(mapOf("compress" to SAVED), days.last().plugins)
                assertNull(days.first().saved)
                assertTrue(days.zipWithNext().all { (a, b) -> a.day < b.day })
            } finally {
                store.close()
            }
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun plainProxyAlertNamesTheSwitch() {
        val base = Config()
        assertEquals(emptyList(), Overview.alerts(base))
        val off = base.copy(proxy = base.proxy.copy(enabled = false))
        assertTrue(Overview.alerts(off).single().contains("Set [proxy] enabled = true"))
    }
}
