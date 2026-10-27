package rtok.model

import kotlinx.serialization.json.JsonArray
import rtok.config.Config
import rtok.support.installDb
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphPageTest {
    @Test fun unindexedRootShowsStatusAndNoDeadRows() {
        installDb()
        val dir = "${tempDir()}/rtok-graphpage-${platform.posix.getpid()}"
        removeDirAll(dir)
        createDirAll("$dir/src")
        val base = Config()
        val c = base.copy(core = base.core.copy(dbPath = "$dir/rtok.db"))
        try {
            val text = GraphPage.text(c, "$dir/src")!!
            assertTrue(text.startsWith("root "), text)
            assertTrue("\ndead symbols\n none\n" in text, text)
            assertEquals(0, (Snapshot.build(c)["projects"] as JsonArray).size)
        } finally {
            removeDirAll(dir)
        }
    }
}
