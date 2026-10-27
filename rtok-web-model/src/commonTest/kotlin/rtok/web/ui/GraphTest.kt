package rtok.web.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphTest {
    private val text =
        listOf(
            "root ~/GitHub/listepo/apps/rtok",
            "rows 48213",
            "files 612",
            "pending 2",
            "  [rtok] src/web/model.rs",
            "  src/tui/view.rs",
            "watch true",
            "indexed_at 2026-09-27 18:12:40",
            "",
            "dead symbols",
            " src/render.rs:212 function pad_right",
            " [ketch] src/queue.rs:41 struct LegacyRow",
            " [ketch] skipped: root is gone",
            " … 195 more, capped at 200",
            "",
        ).joinToString("\n")

    @Test fun parsesStatusPendingAndDead() {
        val v = parseGraph(text)
        assertEquals("~/GitHub/listepo/apps/rtok", v.root)
        assertEquals(48213L, v.rows)
        assertEquals(612L, v.files)
        assertTrue(v.watch)
        assertEquals("2026-09-27 18:12:40", v.indexedAt)
        assertEquals(listOf(PendingFile("rtok", "src/web/model.rs"), PendingFile(null, "src/tui/view.rs")), v.pending)
        assertEquals(DeadSymbol(null, "src/render.rs", "212", "function", "pad_right"), v.dead[0])
        assertEquals(DeadSymbol("ketch", "src/queue.rs", "41", "struct", "LegacyRow"), v.dead[1])
        assertEquals("… 195 more, capped at 200", v.deadNote)
        assertEquals(listOf(" [ketch] skipped: root is gone"), v.other)
    }

    @Test fun absentFieldsAreDashOrNull() {
        val v = parseGraph("")
        assertEquals("-", v.root)
        assertNull(v.rows)
        assertEquals(emptyList(), v.dead)
    }

    @Test fun statesAndRequests() {
        assertEquals("warn" to "files changed since the last index", projectState("stale"))
        assertEquals("muted" to "", projectState("odd"))
        assertEquals("""{"project":{"action":"select","project":"3"}}""", projectRequest("select", mapOf("project" to 3)))
        assertEquals(
            """{"project":{"action":"remove","project":"3","confirmed":true}}""",
            projectRequest("remove", mapOf("project" to 3, "confirmed" to true)),
        )
    }
}
