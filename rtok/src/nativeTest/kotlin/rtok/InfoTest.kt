@file:Suppress("MagicNumber")

// Port of the src/info.rs tests (T43).

package rtok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.support.installDb
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InfoTest {
    private fun home(tag: String): String {
        installDb()
        val dir = "${tempDir()}/rtok-info-$tag-${platform.posix.getpid()}"
        removeDirAll(dir)
        return dir
    }

    @Test fun errorCountReadsTheLevelNotTheText() {
        val lines =
            listOf(
                "2026-09-09 15:04:05 error t/n: boom",
                "2026-09-09 15:04:06 info t/n: mentions error loudly",
                "2026-09-09 15:04:07 warn t/n: an error occurred",
                "garbage",
            )
        assertEquals(4L to 1L, Info.countErrors(lines))
    }

    @Test fun missingFilesRenderDashAndJsonKeepsNulls() {
        val dir = home("missing")
        try {
            val cfg = Config.loadFrom(dir)
            // Fresh home: config was just written, the DB was never opened before `collect` stats it — so it prints `-`.
            val info = Info.collect(cfg, null)
            assertTrue(info.version.isNotEmpty())
            assertEquals(cfg.home, info.home)
            assertNotNull(info.config.bytes)
            assertNull(info.db.bytes)
            assertFalse(info.otel.enabled)
            val text = info.toText()
            assertTrue(text.contains("rtok ") && text.contains("errors"), text)
            val v = Json.parseToJsonElement(info.toJson()).jsonObject
            assertEquals(JsonNull, v["db"]!!.jsonObject["bytes"])
            assertEquals(cfg.proxy.port.toInt(), v["proxy"]!!.jsonObject["port"]!!.jsonPrimitive.int)
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun errorLinesInTheLogAreCounted() {
        val dir = home("err")
        try {
            // An explicit path under the scratch home: the test must never write the developer's real log.
            val path = "$dir/logs/rtok.log"
            val cfg = Config.loadFrom(dir).let { it.copy(log = it.log.copy(path = path)) }
            createDirAll(path.substringBeforeLast('/'))
            writeString(path, "2026-09-09 15:04:05 error t/n: boom\n2026-09-09 15:04:06 info t/n: ok\n")
            val info = Info.collect(cfg, null)
            assertEquals(2L to 1L, info.log.lines to info.log.errors)
            assertTrue(info.toText().contains("1 errors"))
        } finally {
            removeDirAll(dir)
        }
    }
}
