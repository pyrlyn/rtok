// Port of src/plugins/memory/export.rs and import.rs tests.
package rtok.plugins.memory

import kotlin.test.*
import kotlinx.serialization.json.*
import rtok.plugin.Runtime
import rtok.plugins.memory.Import.Report
import rtok.store.*
import rtok.support.diskConfig
import rtok.sys.*

private fun export(c: rtok.config.Config, project: String?): Pair<UInt, String> { val sb = StringBuilder(); return Export.run(c, project) { sb.append(it) } to sb.toString() }

class ExportTest {
    @Test fun exportRoundTripsThroughImportWithoutCheckpoints() {
        val (a, dirA) = diskConfig("export-a")
        Runtime.open(a, "seed").use { cx ->
            for ((k, t, b) in listOf(Triple("decision", "auth", "jwt"), Triple("bug", "cache", "stale key"), Triple("note", "walrus", "journal"))) cx.store.insertNote("p", k, t, b)
            cx.store.insertNote("rtok", "checkpoint:s1", "compact", "checkpoint\n")
        }
        val (n, text) = export(a, null); assertEquals(3u, n); assertEquals(3, text.lines().count { it.isNotEmpty() })
        val v = Json.parseToJsonElement(text.lines().first()).jsonObject
        assertNotNull(v["id"]); assertNotNull(v["ts"]); assertEquals("decision", v["kind"]!!.jsonPrimitive.content); assertFalse("checkpoint" in text)
        assertEquals(0u, export(a, "q").first)
        val (b, dirB) = diskConfig("export-b"); val f = "$dirB/n.jsonl"; writeString(f, text)
        assertEquals(3u to 0u, Import.run(b, f, false).let { it.inserted to it.skipped })
        assertEquals(0u to 3u, Import.run(b, f, false).let { it.inserted to it.skipped })
        removeDirAll(dirA); removeDirAll(dirB)
    }
    @Test fun exportRoundTripsTombstoneAndPin() {
        val (a, dirA) = diskConfig("export-tombstone")
        val (retired, pinned) = Runtime.open(a, "seed").use { cx ->
            cx.store.insertNote("p", "note", "live", "still here")
            val r = cx.store.insertNote("p", "note", "gone", "body kept"); cx.store.retireNote(r, null)
            val p = cx.store.insertNote("p", "note", "pinned", "first in recall"); cx.store.setNotePinned(p, true); r to p
        }
        val (n, text) = export(a, null); assertEquals(3u, n)
        val (b, dirB) = diskConfig("export-tombstone-b"); val f = "$dirB/n.jsonl"; writeString(f, text)
        assertEquals(3u to 0u, Import.run(b, f, false).let { it.inserted to it.skipped })
        Runtime.open(b, "verify").use { cx ->
            val rr = cx.store.noteRow(retired)!!; assertNotNull(rr.retired); assertEquals("body kept", rr.body)
            assertTrue(cx.store.noteRow(pinned)!!.isPinned())
            val recall = cx.store.listNoteTitles("p", 5u)
            assertEquals(pinned, recall.first().first); assertTrue(recall.none { it.first == retired })
        }
        removeDirAll(dirA); removeDirAll(dirB)
    }
    @Test fun checkpointRowIsAbsentFromExportFile() {
        val (c, dir) = diskConfig("export-checkpoint")
        Runtime.open(c, "seed").use { cx -> cx.store.insertNote("p", "note", "keep", "yes"); cx.store.insertNote("p", "checkpoint:s1", "compact", "no"); cx.store.insertNote("p", "session:s1", "handoff", "no") }
        val (n, text) = export(c, null); assertEquals(1u, n); assertFalse("checkpoint" in text); assertFalse("session:" in text)
        removeDirAll(dir)
    }
}

private fun fifty() = (0 until 50).joinToString("\n") { """{"kind":"note","title":"t$it","body":"body-$it"}""" } + "\n"

class ImportTest {
    @Test fun anUnreadableFileIsAnErrorNamingThePath() {
        val (c, dir) = diskConfig("unreadable")
        for (p in listOf("$dir/missing.jsonl", dir)) assertTrue(assertFails { Import.run(c, p, false) }.message!!.startsWith(p))
        writeString("$dir/empty.jsonl", ""); assertEquals(Report(), Import.run(c, "$dir/empty.jsonl", false)); removeDirAll(dir)
    }
    @Test fun fiftyThenReimportThenMalformedExitsOk() {
        val (c, dir) = diskConfig("fifty"); val p = "$dir/n.jsonl"; writeString(p, fifty())
        assertEquals(Report(50u, 0u, 0u), Import.run(c, p, false)); assertEquals(Report(0u, 50u, 0u), Import.run(c, p, false))
        writeString(p, fifty() + "not json\n"); assertEquals(Report(0u, 50u, 1u), Import.run(c, p, false)); removeDirAll(dir)
    }
    @Test fun aLineWhoseKeyExistsLocallyWithADifferentBodyIsSkipped() {
        val (c, dir) = diskConfig("key-collision")
        Runtime.open(c, "seed").use { it.store.insertNote("p", "note", "t", "local body") }
        val p = "$dir/n.jsonl"; writeString(p, """{"kind":"note","title":"t","body":"import body","project":"p"}""" + "\n")
        val dry = Import.run(c, p, true); assertEquals(Report(0u, 1u, 0u), dry); assertEquals(dry, Import.run(c, p, false))
        Runtime.open(c, "verify").use { assertEquals(listOf(ListedNote("p", "note", "t", "local body")), it.store.listNotes("p", true)) }
        removeDirAll(dir)
    }
    @Test fun aSkippedTopicDoesNotReserveItsBodyForALaterFreeTopic() {
        val a = """{"kind":"note","title":"A","body":"new","project":"p"}"""; val b = """{"kind":"note","title":"B","body":"new","project":"p"}"""
        for ((name, lines) in listOf("ab" to listOf(a, b), "ba" to listOf(b, a))) for (dry in listOf(true, false)) {
            val (c, dir) = diskConfig("skip-reserve-$name-$dry")
            Runtime.open(c, "seed").use { it.store.insertNote("p", "note", "A", "old") }
            val p = "$dir/n.jsonl"; writeString(p, lines.joinToString("\n") + "\n")
            assertEquals(Report(1u, 1u, 0u), Import.run(c, p, dry), "$name dry=$dry")
            if (!dry) {
                Runtime.open(c, "verify").use { assertEquals(listOf("A" to "old", "B" to "new"), it.store.listNotes("p", true).sortedBy { r -> r.title }.map { r -> r.title to r.body }) }
                assertEquals(0u, Import.run(c, p, false).inserted)
            }
            removeDirAll(dir)
        }
    }
    @Test fun oldFourFieldLineStillImports() {
        val (c, dir) = diskConfig("legacy-import"); val p = "$dir/n.jsonl"
        writeString(p, """{"kind":"note","title":"legacy","body":"from old export","project":"p"}""" + "\n")
        assertEquals(Report(1u, 0u, 0u), Import.run(c, p, false))
        Runtime.open(c, "verify").use { val rows = it.store.listNotes("p", false); assertEquals(1, rows.size); assertEquals("from old export", rows[0].body) }
        removeDirAll(dir)
    }
    @Test fun privateTagsAreStrippedBeforeInsert() {
        val (c, dir) = diskConfig("strip-import"); val p = "$dir/n.jsonl"
        writeString(p, "{\"kind\":\"note\",\"title\":\"topic\",\"body\":\"key <private>sk-test</private> ok\"}\n{\"kind\":\"note\",\"title\":\"   \",\"body\":\"kept\"}\n")
        assertEquals(Report(1u, 1u, 0u), Import.run(c, p, false))
        Runtime.open(c, "verify").use { val rows = it.store.listNotes(null, false); assertEquals(1, rows.size); assertEquals("topic", rows[0].title); assertEquals("key [REDACTED] ok", rows[0].body) }
        removeDirAll(dir)
    }
}
