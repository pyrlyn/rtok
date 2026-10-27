package rtok.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.logTail
import rtok.support.installDb
import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TWO = 2
private const val MANY = 10

class SnapshotTest {
    private fun cfg(tag: String): Pair<Config, String> {
        installDb()
        val dir = "${tempDir()}/rtok-snapshot-$tag-${platform.posix.getpid()}"
        removeDirAll(dir)
        createDirAll(dir)
        val base = Config()
        return base.copy(log = base.log.copy(path = "$dir/rtok.log"), core = base.core.copy(dbPath = "$dir/rtok.db")) to dir
    }

    @Test fun keysAreSortedAndTheEmptyStoreIsEmptyPages() {
        val (c, dir) = cfg("empty")
        try {
            val snap = Snapshot.build(c)
            assertEquals(snap.keys.sorted(), snap.keys.toList())
            assertEquals("snapshot", snap["type"]!!.jsonPrimitive.content)
            assertEquals(0, (snap["calls"] as JsonArray).size)
            assertEquals(0, (snap["sessions"] as JsonArray).size)
            assertEquals(JsonObject(emptyMap()), snap["ref_ids"])
            assertTrue("error" !in snap, snap["error"].toString())
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun logTailIsNewestFirstAcrossRotations() {
        val (c, dir) = cfg("tail")
        try {
            writeString("$dir/rtok.log", "a\nb\npartial")
            writeString("$dir/rtok.log.1", "y\nz\n")
            assertEquals(listOf("b", "a", "z", "y"), logTail(c, MANY))
            assertEquals(listOf("b", "a"), logTail(c, TWO))
            assertEquals(listOf("b", "a", "z", "y"), (Snapshot.build(c)["logs"] as JsonArray).map { it.jsonPrimitive.content })
        } finally {
            removeDirAll(dir)
        }
    }

    @Test fun nestedObjectsAreSortedToo() {
        val o = JsonObject(mapOf("b" to JsonObject(mapOf("z" to JsonArray(emptyList()), "a" to JsonArray(emptyList()))), "a" to JsonArray(emptyList())))
        assertEquals("""{"a":[],"b":{"a":[],"z":[]}}""", Snapshot.sorted(o).toString())
    }
}
