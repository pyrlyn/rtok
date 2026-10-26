package rtok.plugins.archive

import kotlin.test.*
import kotlinx.serialization.json.*
import rtok.plugin.sdk.*
import rtok.shortId
import rtok.MIN_PREFIX
import rtok.store.*
import rtok.support.*

internal fun big(tag: String) = (1..400).joinToString("\n") { "$tag line $it: some shell output with words" }
private fun six() = (1..6).map { big("t$it") }
/** Six kilobytes of stable JSON, the T51.1 live-zone payload. */
private fun dump(tag: String) = "[" + (1..200).joinToString(",") { """{"id":$it,"name":"$tag-item-$it","ok":true}""" } + "]"
private fun skillText(name: String) = "Base directory for this skill: /s/$name\n\n# $name\n${big(name)}"
private val blobs = compress { it.copy(liveBlobs = true) }

class ArchiveTest {
    @Test fun onlyResultsOutsideTheLiveTailAreRewritten() = withRuntime("turns", compress()) { cx ->
        val req = request(six())
        val ms = rewrite(req.toolResults(), Ctx(cx))
        assertEquals(2, ms.size)
        val v = req.values()
        assertTrue(v[0].startsWith("[archived ") && "t1 line 400" in v[0])
        for (i in 2 until 6) assertEquals(big("t${i + 1}"), v[i])
        assertEquals(1L, cx.store.countKind("plugin_run"))
        val ids = cx.store.callIdsOfKind("plugin_run"); assertEquals(1, ids.size)
        val phases = cx.store.tokenPhases(ids[0])
        assertTrue("before" in phases && "after" in phases, "$phases")
    }

    @Test fun decisionsAreDeterministicAndExpandedIdsStayOriginal() = withRuntime("repeat", compress()) { cx ->
        val first = request(six()); val ms = rewrite(first.toolResults(), Ctx(cx))
        val second = request(six()); rewrite(second.toolResults(), Ctx(cx))
        assertEquals(first.body, second.body)
        assertEquals(1, cx.store.markExpanded(ms[0].refId!!))
        val third = request(six())
        assertEquals(1, rewrite(third.toolResults(), Ctx(cx)).size)
        assertEquals(big("t1"), third.values()[0])
    }

    /** archive runs before toon and both key decisions by tool_use_id: a toon block is not archive's. */
    @Test fun aToonDecisionIsNotReplayedAsAnArchiveSaving() = withRuntime("toon-scope", compress()) { cx ->
        val id = cx.putArchive(big("t1").encodeToByteArray())
        cx.store.putArchiveDecision("tu-1", id, "s", "[toon $id]\na,b,c")
        val req = request(six())
        assertEquals(1, rewrite(req.toolResults(), Ctx(cx)).size, "only tu-2 is archive's")
        assertEquals(big("t1"), req.values()[0], "left for toon")
    }

    /** A pointer belongs to the session that made it; another session's decision is never replayed. */
    @Test fun anotherSessionsDecisionIsNotReplayed() = withRuntime("session-scope", compress()) { cx ->
        val foreign = cx.putArchive("another session's payload".encodeToByteArray())
        cx.store.putArchiveDecision("tu-1", foreign, "other-session", "[archived foreign]")
        val req = request(six())
        assertEquals(2, rewrite(req.toolResults(), Ctx(cx)).size, "both candidates are archived afresh")
        val first = req.values()[0]
        assertTrue("t1 line 400" in first && "archived foreign" !in first, first)
    }

    @Test fun tiersOffMatchesV01AndTiersOnPromotesHotBlock() {
        lateinit var off: List<String>; lateinit var msOff: List<Measurement>
        withRuntime("tiers-off", compress()) { cx ->
            val a = request(six()); msOff = rewrite(a.toolResults(), Ctx(cx))
            val b = request(six()); rewrite(b.toolResults(), Ctx(cx))
            assertEquals(a.body, b.body, "tiers off is byte-identical across replays"); off = a.values()
        }
        withRuntime("tiers-on", compress { it.copy(tiers = true) }) { cx ->
            val on = request(six()); val msOn = rewrite(on.toolResults(), Ctx(cx))
            assertTrue("t1 line 400" in off[0], "v0.1 keeps head/tail inline")
            assertFalse("t1 line 400" in on.values()[0], "cold block is L0 line-only")
            assertTrue("[tier L1:" in on.values()[1], "hot block promoted")
            assertTrue(msOff.all { it.kind == "pointer" })
            assertEquals(1, msOn.count { it.kind == "tier_l1" }); assertEquals(1, msOn.count { it.kind == "tier_l0" })
        }
    }

    @Test fun textBlocksJoinAndImagesAreSkipped() {
        assertEquals("a\nb", blockText(Json.parseToJsonElement("""[{"type":"text","text":"a"},{"type":"text","text":"b"}]""")))
        assertNull(blockText(Json.parseToJsonElement("""[{"type":"image"}]""")))
        assertEquals("[archived abc: 2 lines · 3 tokens · expand(abc)]\na\nb", pointer("a\nb", "abc", 3u, 8, 4))
    }

    /** One 20k-char line came back whole under the pointer line: a longer "saving". */
    @Test fun aPointerOverOneHugeLineIsShorterThanTheLine() {
        val text = "é".repeat(20_000); val bytes = text.utf8Len().toInt()
        val p = pointer(text, "abc123", 5000u, 8, 4)
        assertTrue(p.utf8Len().toInt() < bytes / 10, "${p.length}"); assertTrue("expand(abc123)" in p, p)
        assertTrue(l1Extract(text, 8, 4).utf8Len().toInt() < bytes / 10)
    }

    /** T909: the printed id is 8 hex chars; the ledger keeps the full id and the prefix resolves to it. */
    @Test fun pointerPrintsAn8CharIdAndTheLedgerKeepsTheFullOne() = withRuntime("short-id", compress { it.copy(keepTurns = 1u, minTokens = 1u) }) { cx ->
        val body = big("t1")
        val req = request(listOf(body, "live"))
        val ms = rewrite(req.toolResults(), Ctx(cx)); assertEquals(1, ms.size)
        val full = ms[0].refId!!; assertEquals(64, full.length, full)
        val short = shortId(full); assertEquals(MIN_PREFIX, short.length)
        val shown = req.values()[0]
        assertTrue("[archived $short:" in shown && "expand($short)" in shown && full !in shown, shown)
        assertEquals(listOf(full), cx.store.archiveIdsWithPrefix(short, 2))
        assertEquals(body, cx.store.getArchive(full, cx.config.core.archiveDir)!!.decodeToString())
    }

    /** A pointer written before the short id is replayed as stored, so a cached prefix stays byte-stable. */
    @Test fun aStoredFullIdPointerIsReplayedVerbatim() = withRuntime("replay-old", compress { it.copy(keepTurns = 0u, minTokens = 1u) }) { cx ->
        val body = big("old"); val full = Ctx(cx).putArchive(body.encodeToByteArray())
        val old = "[archived ${full.take(12)}: 400 lines · 9 tokens · expand($full)]"
        cx.store.putArchiveDecision("tu-1", full, "s", old)
        val req = request(listOf(body))
        val ms = rewrite(req.toolResults(), Ctx(cx))
        assertEquals(1, ms.size); assertEquals(old, req.values()[0]); assertEquals(full, ms[0].refId)
    }

    @Test fun aShortL1ExtractPrintsEachLineOnce() =
        assertEquals("[tier L1: 3 lines · lossless extract]\nL1: a\nL2: b\nL3: c", l1Extract("a\nb\nc", 2, 2))

    @Test fun liveBlobsStayWholeWhileTheFlagIsOff() = withRuntime("blobs-off", compress()) { cx ->
        val req = request(List(6) { dump("d") })
        assertTrue(rewriteBlobs(req.liveBlobs(), Ctx(cx)).isEmpty())
        assertTrue(req.values().all { it.startsWith("[") }); assertEquals(0L, cx.store.measurementCount("archive"))
    }

    @Test fun liveBlobsShrinkStablyOutsideTheWorkingEdge() = withRuntime("blobs-on", blobs) { cx ->
        val json = dump("j"); val code = "fn main() {\n    println!(\"hi\");\n}\n".repeat(200); val prose = "just some words ".repeat(600)
        assertTrue(code.length > 4000 && prose.length > 4000)
        val vals = listOf(json, json, json, json, code, prose)
        val req = request(vals)
        val ms = rewriteBlobs(req.liveBlobs(), Ctx(cx))
        assertEquals(4, ms.size, "turns 5..2 shrink; turns 1,0 stay"); assertTrue(ms.all { it.kind == "live_blob" })
        req.values().take(4).forEach { assertTrue(it.startsWith("[archived ") && "expand(" in it, it.take(80)) }
        assertEquals(code, req.values()[4], "code is not a dump"); assertEquals(prose, req.values()[5], "prose is not a dump")
        val again = request(vals); rewriteBlobs(again.liveBlobs(), Ctx(cx))
        assertEquals(req.body, again.body, "same bytes in → byte-identical pointers out")
        assertEquals(json, Ctx(cx).getArchive(ms[0].refId!!)!!.decodeToString())
    }

    @Test fun liveBlobsCoverDataUrisAndBase64AndExpands() = withRuntime("blobs-data", blobs) { cx ->
        val uri = "data:image/png;base64," + "aB3dE5g7".repeat(900); val bare = "aB3dE5g7".repeat(900)
        val req = request(listOf(uri, bare, "small", "also-small", uri, "live-two"))
        val ms = rewriteBlobs(req.liveBlobs(), Ctx(cx))
        assertEquals(2, ms.size, "two stable blobs at turns 5,4")
        assertTrue(req.values()[0].startsWith("[archived ")); assertTrue(req.values()[1].startsWith("[archived "))
        assertEquals("small", req.values()[2], "under min_tokens")
        assertEquals(1, cx.store.markExpanded(ms[0].refId!!))
        val again = request(listOf(uri, bare, "small", "x", "y", "z"))
        assertEquals(1, rewriteBlobs(again.liveBlobs(), Ctx(cx)).size, "the expanded blob stays original")
        assertTrue(again.values()[0].startsWith("data:"))
    }

    @Test fun skillBodiesArchiveOutsideKeepTurnsStably() = withRuntime("skills-on", compress { it.copy(skills = true, keepTurns = 1u) }) { cx ->
        val body = skillText("pixel")
        val req = request(List(3) { body })
        val ms = rewriteSkills(req.skills(), Ctx(cx))
        assertEquals(2, ms.size, "turns 2 and 1 (keep_turns=1); turn 0 stays"); assertTrue(ms.all { it.kind == "skill" })
        val shown = req.values()[0]; val full = ms[0].refId!!
        assertTrue(shown.startsWith("[archived ") && "skill pixel" in shown && "expand(${shortId(full)})" in shown && full !in shown, shown)
        assertTrue(req.values()[1].startsWith("[archived ")); assertEquals(body, req.values()[2], "live edge stays whole")
        val again = request(List(3) { body }); val ms2 = rewriteSkills(again.skills(), Ctx(cx))
        assertEquals(req.body, again.body, "byte-identical pointers on replay"); assertEquals(ms[0].refId, ms2[0].refId, "the body never re-archives")
        assertEquals(body, Ctx(cx).getArchive(full)!!.decodeToString())
    }

    /** A non-ASCII tool_use_id is shortened on a char boundary. */
    @Test fun aNonAsciiSkillIdIsShortenedOnACharBoundary() = withRuntime("skills-utf8-id", compress { it.copy(skills = true, keepTurns = 0u) }) { cx ->
        val req = WireRequest(ListWire(), JsonArray(listOf(JsonPrimitive(skillText("pixel")))))
        val ms = rewriteSkills(listOf(SkillRef(req, listOf(0), 1, "tu-ключи-1", "pixel")), Ctx(cx))
        assertEquals(1, ms.size)
        assertTrue(req.values()[0].startsWith("[archived tu-ключи-1: skill pixel"), req.values()[0])
    }

    @Test fun skillBodiesStayWholeWhileTheFlagIsOff() = withRuntime("skills-off", compress { it.copy(skills = false, keepTurns = 0u) }) { cx ->
        val body = skillText("pixel"); val req = request(listOf(body))
        assertTrue(rewriteSkills(req.skills(), Ctx(cx)).isEmpty()); assertEquals(body, req.values()[0])
    }
}
