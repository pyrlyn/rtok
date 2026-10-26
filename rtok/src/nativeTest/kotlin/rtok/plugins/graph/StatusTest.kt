package rtok.plugins.graph

import kotlinx.serialization.json.Json
import rtok.plugin.sdk.Ctx
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*
import kotlin.test.*

class StatusTest {
    @Test fun collectCountsTheIndexAndTheTableNamesEveryField() =
        withRuntime("graph-status") { rt ->
            val dir = tmpDir("graph-status")
            try {
                val cx = Ctx(rt)
                writeString("$dir/a.rs", "fn alpha() {}\nfn beta() {}\n")
                val empty = Status.collect(cx, dir)
                assertEquals(0L, empty.rows)
                assertNull(empty.indexedAt)
                assertTrue(Status.formatTable(empty).endsWith("watch off\nindexed_at -\n"), Status.formatTable(empty))
                GraphCore.indexFor(cx, dir)
                val s = Status.collect(cx, dir)
                assertEquals(Index.canon(dir), s.root)
                assertTrue(s.rows >= 2)
                assertEquals(1L, s.files)
                assertNotNull(s.indexedAt)
                assertTrue(Status.formatTable(s).startsWith("root ${s.root}\nrows ${s.rows}\nfiles 1\npending 0\n"))
                assertTrue(Json.encodeToString(GraphStatus.serializer(), s).contains("\"indexed_at\":"))
            } finally {
                removeDirAll(dir)
            }
        }

    @Test fun cliRootRefusesAFileAndAMissingPath() {
        val dir = tmpDir("graph-cliroot")
        try {
            writeString("$dir/f", "")
            assertEquals(dir, Status.cliRoot(dir))
            assertTrue(assertFails { Status.cliRoot("$dir/f") }.message!!.endsWith("not a directory"))
            assertFails { Status.cliRoot("$dir/nope") }
        } finally {
            removeDirAll(dir)
        }
    }
}
