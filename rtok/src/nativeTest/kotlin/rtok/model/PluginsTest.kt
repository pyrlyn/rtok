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
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BEFORE = 90u
private const val AFTER = 30u

class PluginsTest {
    @Test fun catalogueWithStatsOnlyForSavers() {
        installDb()
        val dir = "${tempDir()}/rtok-plugins-${platform.posix.getpid()}"
        removeDirAll(dir)
        createDirAll(dir)
        val base = Config()
        val c = base.copy(core = base.core.copy(dbPath = "$dir/rtok.db"))
        try {
            Store.open(c.core.dbPath).let { s ->
                try {
                    s.insertMeasurement("s", Measurement("read", "outline", 1u, 1u, BEFORE, AFTER))
                    val pages = Plugins.build(c, s).associateBy { it.id }
                    assertEquals(1L, pages.getValue("read").stats!!.rows)
                    assertEquals((BEFORE - AFTER).toLong(), pages.getValue("read").stats!!.let { it.estBefore - it.estAfter })
                    assertNull(pages.getValue("measure").stats)
                    assertTrue(pages.getValue("memory").fields.any { it.first == "notes live" })
                    assertEquals("rewrite" to "true", pages.getValue("cmd").fields.first())
                } finally {
                    s.close()
                }
            }
            val wire = Snapshot.build(c)["plugins"] as JsonArray
            val measure = wire.map { it as JsonObject }.first { it["id"]!!.jsonPrimitive.content == "measure" }
            assertEquals(JsonNull, measure["stats"])
            assertEquals(2, ((measure["fields"] as JsonArray).first() as JsonArray).size)
        } finally {
            removeDirAll(dir)
        }
    }
}
