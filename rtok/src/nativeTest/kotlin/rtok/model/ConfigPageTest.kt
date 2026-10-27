package rtok.model

import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.support.installDb
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigPageTest {
    @Test fun rowsNameTheirSourceAndNoFileIsCreated() {
        installDb()
        val dir = "${tempDir()}/rtok-configpage-${platform.posix.getpid()}"
        removeDirAll(dir)
        createDirAll(dir)
        try {
            val base = Config()
            val c = base.copy(core = base.core.copy(dbPath = "$dir/rtok.db")).also { it.home = dir }
            val text = ConfigPage.text(c)!!
            assertTrue(text.lines().any { it.startsWith("proxy.port = ") && it.endsWith("(default)") }, text)
            assertFalse(exists("$dir/config.toml"))
            writeString("$dir/config.toml", "[proxy]\nport = 9999\n")
            val set = Snapshot.build(c)["config"]!!.jsonPrimitive.content
            assertTrue(set.lines().any { it.startsWith("proxy.port = 9999 (") && !it.endsWith("(default)") }, set)
        } finally {
            removeDirAll(dir)
        }
    }
}
