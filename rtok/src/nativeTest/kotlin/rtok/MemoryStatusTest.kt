package rtok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.plugin.Runtime
import rtok.plugin.sdk.Measurement
import rtok.store.insertNote
import rtok.support.diskConfig
import kotlin.test.*

class MemoryStatusTest {
    /** tests/memory_status.rs `memory_status_json_matches_model_type`. */
    @Test fun memoryStatusJsonMatchesModelType() {
        val (cfg, _) = diskConfig("mem-status-json")
        Runtime.open(cfg, "mem-json").use { it.record(Measurement("memory", "recall", 12u, 4u, 3u, 1u, "sess")) }
        val status = MemoryStatusView.collect(cfg, null, "9999d")
        assertEquals(1uL, status.recall.recalls)
        val v = Json.parseToJsonElement(MemoryStatusView.json(status)).jsonObject
        assertEquals(1, v["recall"]!!.jsonObject["recalls"]!!.jsonPrimitive.int)
        assertFalse("project" in v, "a null project is skipped like serde's skip_serializing_if")
    }

    @Test fun theTableCountsNotesPerProjectAndKind() {
        val (cfg, _) = diskConfig("mem-status-table")
        Runtime.open(cfg, "mem-table").use { rt ->
            rt.store.insertNote("rtok", "decision", "one", "alpha")
            rt.store.insertNote("rtok", "note", "two", "beta")
            rt.store.insertNote(null, "note", "three", "gamma")
        }
        val t = MemoryStatusView.table(MemoryStatusView.collect(cfg, null, "1d"))
        val lines = t.lines()
        assertEquals("since 1d", lines[0])
        assertEquals("notes  live 3  pinned 0  retired 0  body 14 B", lines[1])
        assertEquals("calls  mem_search 0  mem_get 0", lines[3])
        assertTrue(lines[4].startsWith("project -  kind note  live 1"), lines[4])
        assertTrue(lines[5].startsWith("project rtok  kind decision  live 1"), lines[5])
        assertTrue(lines[6].startsWith("project rtok  kind note  live 1"), lines[6])
    }

    @Test fun aProjectFilterKeepsOnlyItsNotes() {
        val (cfg, _) = diskConfig("mem-status-project")
        Runtime.open(cfg, "mem-project").use { rt ->
            rt.store.insertNote("rtok", "note", "a", "x")
            rt.store.insertNote("other", "note", "b", "y")
        }
        val s = MemoryStatusView.collect(cfg, "rtok", null)
        assertEquals("rtok", s.project)
        assertEquals(1uL, s.notes.live)
        assertEquals("project rtok", MemoryStatusView.table(s).lines()[1])
    }
}
