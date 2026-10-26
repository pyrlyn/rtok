@file:OptIn(kotlin.time.ExperimentalTime::class)
// Port of src/plugins/memory/mod.rs tests.

package rtok.plugins.memory

import kotlinx.serialization.json.*
import rtok.plugin.Runtime
import rtok.plugin.sdk.*
import rtok.projectName
import rtok.store.*
import rtok.support.tmpDir
import rtok.support.worktreeLayout
import rtok.sys.*
import kotlin.test.*

private fun Runtime.save(
    kind: String,
    t: String,
    b: String,
    p: String? = null,
) = memSave(this, kind, t, b, p).first

private fun prompt(
    rt: Runtime,
    p: String,
) = MemoryPlugin.promptSubmit(PromptSubmit(p), Ctx(rt))

private fun recallText(
    rt: Runtime,
    p: String,
) = prompt(rt, p)?.text ?: ""

private fun Runtime.meas(kind: String) = store.listMeasurements("memory").filter { it.kind == kind }

private const val PLANTED_TITLE = "p29-gate-arctic-tern"
private const val PLANTED_BODY = "Hooks must exit in ≤10 ms fail-open; async ORM rejected — Diesel stays sync on the hook path (D13)."
private const val PARAPHRASE = "why not use an async database library for hooks"
private const val NAMED = "src/proxy/semantic_cache.rs"

class MemoryTest {
    @Test fun aSingleLongTitleCannotBreakTheIndexCap() =
        memRt("t327-title-cap").use { rt ->
            val cx = Ctx(rt)
            val text = renderTitleIndex(cx, mutableListOf(Entry(1, "x".repeat(2000), 5u)), 30u)
            assertTrue(cx.estimate(text, Class.Prose) <= 30u, text)
            assertTrue(text.startsWith(INDEX_GUIDE))
            assertTrue("1 x" in text, text)
        }

    @Test fun rememberPrefixSavesANoteAndRepeatsSameId() =
        memRt("t695-remember").use { rt ->
            assertEquals("saved note 1", prompt(rt, "remember: hooks fail open under 10 ms")!!.text)
            assertEquals("saved note 1", prompt(rt, "remember: hooks fail open under 10 ms")!!.text)
            assertNull(prompt(rt, "just a question"))
        }

    @Test fun stripPrivateRemovesATagAndLeavesSurroundingText() {
        assertEquals("key [REDACTED] ok", stripPrivate("key <private>sk-test</private> ok"))
        assertEquals("[REDACTED] c</private>", stripPrivate("<private>a <private>b</private> c</private>"))
    }

    @Test fun stripPrivateIsCaseInsensitiveAndSpansNewlines() = assertEquals("pre [REDACTED] post", stripPrivate("pre <PRIVATE>\nsecret\n</PrIvAtE> post"))

    @Test fun memSaveStoresTheRedactedBody() =
        memRt("strip-private").use { rt ->
            assertEquals("key [REDACTED] ok", memGet(rt, rt.save("note", "topic", "key <private>sk-test</private> ok")))
            assertEquals("[REDACTED]", memGet(rt, rt.save("note", "secret title", "<private>sk</private>")))
            assertEquals("missing `title`", assertFails { rt.save("note", "   ", "kept") }.message)
            assertEquals("missing `body`", assertFails { rt.save("note", "only secret", "  \n  ") }.message)
            assertTrue(rt.store.noteBodies().none { it == "kept" })
        }

    @Test fun promptRecallIsOnByDefaultAndSkipsBodies() =
        memRt("t695-recall-on").use { rt ->
            assertEquals(5u, rt.config.plugins.memory.promptRecall)
            rt.save("note", "walrus", "the walrus journal lives here")
            assertTrue(memSearch(rt, "walrus", 5u).isNotEmpty())
            val t = prompt(rt, "walrus journal")!!.text
            assertTrue(t.startsWith(INDEX_GUIDE))
            assertTrue(" walrus" in t)
            assertTrue("t body" in t)
            assertFalse("journal lives" in t)
        }

    @Test fun promptRecallOffInjectsNothing() =
        memRt("t695-recall-off") { it.copy(promptRecall = 0u) }.use { rt ->
            rt.save("note", "alpha", "hooks fail open")
            assertNull(prompt(rt, "tell me about hooks fail open"))
        }

    @Test fun promptRecallWithNoStoredVectorsMatchesEmbedOff() {
        fun text(enabled: Boolean) =
            memRt("t454-bytes-$enabled").use { rt ->
                rt.mem { it.copy(embed = it.embed.copy(enabled = false)) }
                rt.save("note", "walrus", "the walrus journal lives here")
                rt.mem { it.copy(embed = it.embed.copy(enabled = enabled, hybrid = true)) }
                val t = recallText(rt, "walrus journal")
                assertTrue(t.isNotEmpty())
                if (enabled) assertEquals(0L, rt.store.noteEmbeddingCount())
                t
            }
        assertEquals(text(false), text(true))
    }

    @Test fun promptRecallHybridTitlesAStoredVectorFtsMissed() =
        memRt("t454-hybrid") { it.copy(embed = it.embed.copy(enabled = true, hybrid = true)) }.use { rt ->
            rt.save("decision", PLANTED_TITLE, PLANTED_BODY, "rtok")
            rt.save("decision", "p29-decoy-etl-batch", "Storage indexing and schema migration patterns for batch ETL pipelines in data warehouses.", "rtok")
            assertTrue(rt.store.searchNotes(PARAPHRASE, 10u).all { it.title != PLANTED_TITLE })
            assertEquals(2L, rt.store.noteEmbeddingCount())
            val t = recallText(rt, PARAPHRASE)
            assertTrue(PLANTED_TITLE in t, t)
            assertFalse("async ORM" in t)
            assertEquals(2L, rt.store.noteEmbeddingCount())
            assertEquals(1, rt.meas("prompt_recall").size)
        }

    @Test fun promptRecallHybridDoesNotEmbedANoteWithoutAVector() =
        memRt("t454-nowrite") { it.copy(embed = it.embed.copy(enabled = false)) }.use { rt ->
            rt.save("decision", PLANTED_TITLE, PLANTED_BODY, "rtok")
            assertEquals(0L, rt.store.noteEmbeddingCount())
            rt.mem { it.copy(embed = it.embed.copy(enabled = true, hybrid = true)) }
            assertEquals("", recallText(rt, PARAPHRASE))
            assertEquals(0L, rt.store.noteEmbeddingCount())
        }

    @Test fun saveThreeSearchHitsFirstGetFullBody() =
        memRt("t61").use { rt ->
            val a = rt.save("decision", "walrus", "the walrus journal lives here", "rtok")
            rt.save("decision", "banana", "yellow fruit unrelated", "rtok")
            rt.save("decision", "other", "nothing matching the unique token", "rtok")
            val hits = memSearch(rt, "walrus", 5u)
            assertEquals("walrus", hits[0].title)
            assertEquals(a, hits[0].id)
            assertTrue(hits[0].snippet.length <= 120)
            val body = memGet(rt, a)!!
            assertEquals("the walrus journal lives here", body)
            val rows = rt.meas("mem_get")
            assertEquals(1, rows.size)
            assertEquals(body.length.toLong(), rows[0].afterBytes)
        }

    @Test fun recallIndexNamesFetchWithoutBodies() =
        memRt("t293-index").use { rt ->
            val secret = "vault-secret-never-inject"
            val ids = listOf(rt.save("note", "alpha", secret), rt.save("note", "beta", "plain beta body"), rt.save("note", "gamma", "plain gamma body"))
            val t = recall(Ctx(rt))!!.text
            ids.forEach { assertTrue(it.toString() in t) }
            listOf("alpha", "beta", "gamma", "t body", INDEX_GUIDE).forEach { assertTrue(it in t, t) }
            assertFalse(secret in t)
            assertTrue(rt.estimate(t, Class.Prose) <= 200u)
        }

    @Test fun recallMeasurementSumsTheBodiesItReplaced() =
        memRt("t418-sizes").use { rt ->
            val bodies = listOf("short body", "a longer body, still plain ascii text")
            bodies.forEachIndexed { i, b -> rt.save("note", "n$i", b) }
            recall(Ctx(rt))!!
            val row = rt.meas("recall").first()
            assertEquals(bodies.sumOf { it.length }.toLong(), row.beforeBytes)
            assertEquals(bodies.sumOf { rt.estimate(it, Class.Prose).toInt() }, row.estBefore)
        }

    @Test fun recallEmptyProjectNamesTheKey() =
        memRt("t293-empty").use { rt ->
            val dir = tmpDir("mem-empty")
            createDirAll("$dir/.git")
            rt.cwd = dir
            rt.save("note", "elsewhere", "other project body", "other")
            val t = recall(Ctx(rt))!!.text
            assertEquals("memory project ${components(dir).last()}: no notes", t)
            assertTrue(rt.estimate(t, Class.Prose) <= 200u)
            removeDirAll(dir)
        }

    @Test fun memGetRecordsMeasurementAndReturnsFullBody() =
        memRt("t293-get").use { rt ->
            val id = rt.save("note", "t", "verbatim body")
            memUpdate(rt, id, true, null, null)
            assertEquals(0L, rt.store.measurementCount("memory"))
            val body = memGet(rt, id)!!
            assertTrue(body.startsWith("retired "))
            assertTrue("verbatim body" in body)
            val rows = rt.meas("mem_get")
            assertEquals(1, rows.size)
            assertEquals(body.length.toLong(), rows[0].afterBytes)
            assertTrue(rows[0].estAfter > 0)
        }

    @Test fun reviseSupersedesAndRetiresTheOldNote() =
        memRt("t691-revise").use { rt ->
            val old = rt.save("decision", "auth model", "sessions forever", "p")
            val (new, retired) = memRevise(rt, old, "auth model v2", "jwt tokens now")
            assertEquals(old, retired)
            assertNotEquals(old, new)
            assertEquals(0, memSearch(rt, "sessions", 5u).size)
            assertEquals(new, memSearch(rt, "jwt", 5u)[0].id)
            val body = memGet(rt, old)!!
            assertTrue(body.startsWith("retired "))
            assertTrue(", superseded by $new" in body)
            assertTrue("sessions forever" in body)
            assertEquals(new to null, memRevise(rt, new, "auth model v2", "jwt tokens v2"))
            assertTrue(memGet(rt, new)!!.startsWith("jwt tokens v2"))
        }

    @Test fun revisingAGlobalNoteKeepsItGlobal() =
        memRt("t539-revise-global").use { rt ->
            val global = rt.store.insertNote(null, "note", "shared", "global body")
            val other = rt.save("note", "shared", "project body", "other")

            fun projectOf(id: Int) = rt.store.noteRow(id)!!.project
            assertEquals(global to null, memRevise(rt, global, "shared", "global v2"))
            assertNull(projectOf(global))
            val (new, retired) = memRevise(rt, global, "renamed", "global v3")
            assertEquals(global, retired)
            assertNull(projectOf(new))
            assertEquals("other", projectOf(other))
            assertTrue(memGet(rt, other)!!.startsWith("project body"))
        }

    @Test fun retireRemovesFromRecallAndKeepsTheBody() =
        memRt("t691-retire").use { rt ->
            val id = rt.save("note", "stale fact", "wrong under new light")
            assertEquals("retired note $id", memUpdate(rt, id, true, null, null))
            recall(Ctx(rt))?.let { assertFalse("stale fact" in it.text) }
            val body = memGet(rt, id)!!
            assertTrue(body.startsWith("retired "))
            assertTrue("wrong under new light" in body)
            assertTrue(memSearch(rt, "wrong", 5u).isEmpty())
            assertFails { memUpdate(rt, 999, true, null, null) }
            assertEquals("right again", memGet(rt, rt.save("note", "stale fact", "right again")))
        }

    @Test fun pinnedNoteLeadsRecallByteStable() =
        memRt("t691-pin").use { rt ->
            val keep = rt.save("note", "keep me first", "pinned body")
            assertEquals("pinned note $keep", memUpdate(rt, keep, false, null, true))
            repeat(20) { rt.save("note", "newer-$it", "body-$it") }
            val a = recall(Ctx(rt))!!
            val b = recall(Ctx(rt))!!
            assertEquals(a.text, b.text)
            assertTrue("$keep keep me first" in a.text)
            assertTrue(INDEX_GUIDE in a.text)
            assertEquals("unpinned note $keep", memUpdate(rt, keep, false, null, false))
        }

    @Test fun mcpSurfaceStaysWithinSixtyDescriptionTokens() =
        memRt("t691-surface").use { rt ->
            val total = MemoryPlugin.mcpTools().sumOf { rt.estimate(it.description, Class.Prose).toLong() }
            assertTrue(total <= 60, "memory tool surface is $total tokens")
        }

    @Test fun sameProjectKindTitleUpdatesInPlace() =
        memRt("t671").use { rt ->
            val (a, first) = memSave(rt, "decision", "auth model", "sessions", "p")
            val (b, second) = memSave(rt, "decision", "auth model", "jwt", "p")
            assertEquals(Triple(false, true, true), Triple(first, second, a == b))
            val c = rt.save("note", "auth model", "other kind", "p")
            val d = rt.save("decision", "auth model", "other project", "q")
            assertTrue(a != c && a != d && c != d)
            assertEquals(2, rt.store.listNotes("p", true).size)
            assertEquals(a, memSearch(rt, "jwt", 5u)[0].id)
            assertTrue(memSearch(rt, "sessions", 5u).isEmpty())
            assertEquals(listOf(Triple(1, "auth model", "sessions")), rt.store.noteVersions(a))
            val r = recall(Ctx(rt))!!.text
            assertFalse("sessions" in r)
            assertFalse("jwt" in r)
        }

    @Test fun historyReturnsTheOldBodyAndRecallStaysOnTheCurrent() =
        memRt("t472-history").use { rt ->
            val id = rt.save("decision", "topic", "alpha body")
            rt.save("decision", "topic", "beta body")
            assertEquals("beta body", memGet(rt, id))
            val h = memHistory(rt, id)
            assertTrue("alpha body" in h)
            assertFalse("beta body" in h)
            val r = recall(Ctx(rt))!!.text
            assertTrue("topic" in r)
            assertFalse("alpha body" in r)
            assertFalse("beta body" in r)
            assertFails { memHistory(rt, 999) }
            Unit
        }

    @Test fun twentyNotesRecallFiveTitlesUnderBudgetStable() =
        memRt("t62").use { rt ->
            repeat(20) { rt.save("note", "title-$it", "body-$it secret") }
            val a = recall(Ctx(rt))!!
            val b = recall(Ctx(rt))!!
            assertEquals(a.text, b.text)
            assertFalse("secret" in a.text)
            assertTrue(INDEX_GUIDE in a.text)
            assertTrue("t body" in a.text)
            assertEquals(6, a.text.lines().size)
            assertTrue(rt.estimate(a.text, Class.Prose) <= 200u)
            assertEquals(10u.toUByte(), a.priority)
            assertEquals(2, rt.meas("recall").size)
        }

    @Test fun recallFiltersByHookCwdNotProcessCwd() =
        memRt("mem-cwd").use { rt ->
            val dir = tmpDir("mem-cwd")
            createDirAll("$dir/.git")
            rt.cwd = dir
            rt.save("note", "other-note", "body from elsewhere", "elsewhere")
            rt.save("note", "cwd-note", "visible under hook cwd", components(dir).last())
            val t = recall(Ctx(rt))!!.text
            assertTrue("cwd-note" in t)
            assertFalse("other-note" in t)
            removeDirAll(dir)
        }

    @Test fun noteSavedFromAWorktreeRecallsFromTheMainCheckout() =
        memRt("t133-recall").use { rt ->
            val dir = tmpDir("t133-recall")
            val (main, wt) = worktreeLayout(dir)
            rt.cwd = wt
            prompt(rt, "remember: worktree note")!!
            rt.cwd = main
            assertTrue("worktree note" in recall(Ctx(rt))!!.text)
            assertEquals("repo", projectName(wt))
            removeDirAll(dir)
        }

    private fun linkedFixture(tag: String): Pair<Runtime, String> {
        val dir = tmpDir("t374-$tag")
        createDirAll("$dir/src/proxy")
        writeString("$dir/$NAMED", "x")
        return memRt("t374-$tag").also { it.cwd = dir } to dir
    }

    private fun equalPair(rt: Runtime) =
        rt.save("note", "plain", "eviction policy of the proxy src/proxy/something_other.rs") to
            rt.save("note", "linked", "eviction policy of the proxy src/proxy/semantic_cache.rs")

    private fun recallOrder(
        rt: Runtime,
        p: String,
    ): List<String> {
        val t = prompt(rt, p)?.text ?: return emptyList()
        return listOf("plain", "linked").mapNotNull { n -> t.indexOf(" $n (").takeIf { it >= 0 }?.let { it to n } }.sortedBy { it.first }.map { it.second }
    }

    private fun linkedWithoutText(
        rt: Runtime,
        count: Int,
    ) = repeat(count) { rt.store.setNoteFiles(rt.save("note", "lone$it", "quartz$it lichen"), listOf(NAMED)) }

    private fun loneTitles(t: String) = (0 until 8).count { " lone$it (" in t }

    @Test fun aSaveLinksTheExistingFilesItsBodyNames() {
        val (rt, dir) = linkedFixture("save")
        rt.use {
            val (plain, linked) = equalPair(rt)

            fun hit(id: Int) = rt.store.notesForFiles(null, listOf(NAMED), 5u).any { it.id == id }
            assertTrue(hit(linked) && !hit(plain))
            rt.save("note", "linked", "nothing about files now")
            assertFalse(hit(linked))
        }
        removeDirAll(dir)
    }

    @Test fun aSaveLinksTheCheckpointPathsUnderTheRoot() {
        val (rt, dir) = linkedFixture("ckpt")
        rt.use {
            rt.store.upsertNote(null, "checkpoint:t374-ckpt", "compact", "path $dir/$NAMED\npath /elsewhere/x.rs\n")
            val id = rt.save("note", "about it", "no path in this body")
            assertEquals(listOf(id), rt.store.notesForFiles(null, listOf(NAMED), 5u).map { it.id })
            assertTrue(rt.store.notesForFiles(null, listOf("/elsewhere/x.rs", "x.rs"), 5u).isEmpty())
        }
        removeDirAll(dir)
    }

    @Test fun aNoteLinkedToAReadFileOutranksAnEqualTextMatch() {
        val (rt, dir) = linkedFixture("rank")
        rt.use {
            equalPair(rt)
            assertEquals(listOf("plain", "linked"), recallOrder(rt, "eviction policy"))
            rt.store.putReadCache("t374-rank", "$NAMED\tfull\t", "h", null)
            assertEquals(listOf("linked", "plain"), recallOrder(rt, "eviction policy"))
        }
        removeDirAll(dir)
    }

    @Test fun aFileNamedInThePromptBoostsItsNotesToo() {
        val (rt, dir) = linkedFixture("prompt")
        rt.use {
            equalPair(rt)
            assertEquals("linked", recallOrder(rt, "eviction policy in $NAMED:40").firstOrNull())
        }
        removeDirAll(dir)
    }

    @Test fun aReadFileOnlyReranksTextHitsAndNeverRecallsAlone() {
        val (rt, dir) = linkedFixture("only")
        rt.use {
            equalPair(rt)
            rt.store.putReadCache("another-session", NAMED, "h", null)
            assertTrue(recallOrder(rt, "zebra crossing").isEmpty())
            rt.store.putReadCache("t374-only", "read\t$NAMED", "h", null)
            assertTrue(recallOrder(rt, "zebra crossing").isEmpty())
        }
        removeDirAll(dir)
    }

    @Test fun aFileNamedInThePromptRecallsItsNoteWithoutATextMatch() {
        val (rt, dir) = linkedFixture("named")
        rt.use {
            linkedWithoutText(rt, 1)
            assertTrue(" lone0 (" in recallText(rt, "please look at $NAMED:40 today"))
        }
        removeDirAll(dir)
    }

    @Test fun aPromptNamingNoFileRecallsNothingExtra() {
        val (rt, dir) = linkedFixture("nofile")
        rt.use {
            linkedWithoutText(rt, 2)
            for (p in listOf("please look at the cache", "please look at src/proxy/other.rs", "please look at ../semantic_cache.rs")) assertEquals("", recallText(rt, p), p)
        }
        removeDirAll(dir)
    }

    @Test fun atMostTwoNamedFileNotesAreAdded() {
        val (rt, dir) = linkedFixture("cap")
        rt.use {
            linkedWithoutText(rt, 4)
            assertEquals(2, loneTitles(recallText(rt, "please look at $NAMED")))
        }
        removeDirAll(dir)
    }

    @Test fun namedFileNotesTakeSlotsFromTheBudgetAndNeverEnlargeIt() {
        val (rt, dir) = linkedFixture("budget")
        rt.use {
            repeat(7) { rt.save("note", "hit$it", "eviction policy text") }
            linkedWithoutText(rt, 3)
            val p = "eviction policy of $NAMED"
            val hits = recallHits(Ctx(rt), p, rt.store.searchNotes("eviction policy", 10u), 5)
            assertEquals(5, hits.size)
            assertEquals(2, hits.count { it.title.startsWith("lone") })
            assertTrue(Ctx(rt).estimate(recallText(rt, p), Class.Prose) <= 200u)
        }
        removeDirAll(dir)
    }

    @Test fun namedFileRecallIsByteStable() {
        fun run(): String {
            val (rt, dir) = linkedFixture("stable")
            return rt
                .use {
                    equalPair(rt)
                    linkedWithoutText(rt, 3)
                    recallText(rt, "eviction policy of $NAMED")
                }.also { removeDirAll(dir) }
        }
        val a = run()
        assertTrue(" lone" in a, a)
        assertEquals(a, run())
    }

    @Test fun aReadNoteLeadsAnUnreadTwin() =
        memRt("t454-rank").use { rt ->
            val old = rt.save("note", "alpha-rank", "body alpha")
            rt.save("note", "beta-rank", "body beta")
            repeat(5) { rt.store.touchNote(old) }
            assertEquals(old, rt.store.listNoteTitles(null, 1u)[0].first)
        }

    private fun bashEv(resp: String) = PostToolUse("Bash", buildJsonObject { put("command", "cargo test") }, JsonPrimitive(resp))

    @Test fun postToolStoresAScrubbedObservationOnce() =
        memRt("t454-obs").use { rt ->
            val ev = bashEv("sk-ant-abcdefghijklmnopqrstuvwxyz")
            assertNull(MemoryPlugin.postTool(ev, Ctx(rt)))
            assertNull(MemoryPlugin.postTool(ev, Ctx(rt)))
            val hits = rt.store.searchObservations(null, "cargo", 5u)
            assertEquals(1, hits.size)
            val body = obsGet(rt, hits[0].id)!!
            assertTrue("cargo test" in body)
            assertFalse("sk-ant-" in body)
            assertTrue(body.length <= 400)
            assertEquals(0, rt.meas("observe").size)
            assertEquals(1, rt.meas("obs_get").size)
            assertTrue(rt.store.measurementTotals().none { it.kind == "observe" })
        }

    @Test fun aCallDeliveredAgainAfterTheDedupWindowIsObservedOnce() =
        memRt("t501-obs").use { rt ->
            rt.once = "PostToolUse:toolu_t501"
            val ev = bashEv("ok")
            assertNull(MemoryPlugin.postTool(ev, Ctx(rt)))
            rt.store.setObservationTs("t501-obs", 0)
            assertNull(MemoryPlugin.postTool(ev, Ctx(rt)))
            assertEquals(1, rt.store.searchObservations(null, "cargo", 5u).size)
        }

    @Test fun preCompactNamesANoteAndStaysEmptyWithoutOne() {
        memRt("t454-compact-empty").use { assertNull(compactContext(Ctx(it))) }
        memRt("t454-compact").use { rt ->
            rt.save("note", "keep-compact", "body")
            val t = compactContext(Ctx(rt))!!.text
            assertTrue("keep-compact" in t)
            assertTrue(rt.estimate(t, Class.Prose) <= 200u)
        }
    }

    @Test fun fiveObservationsInOneSessionYieldTheOtherSessionFirst() =
        memRt("t454-div").use { rt ->
            repeat(5) { rt.store.insertObservation(NewObservation("session-aaa", null, "command_run", "busy-$it", "walrus cargo test output", "dedup-a-$it", emptyList())) }
            rt.store.insertObservation(NewObservation("session-bbb", null, "command_run", "other-session", "walrus cargo test output", "dedup-b", emptyList()))
            val inj = prompt(rt, "walrus cargo")!!
            val obs = inj.text.lines().filter { it.startsWith("obs ") }
            assertTrue(obs.isNotEmpty() && obs.size <= 2, "$obs")
            val mixed = diversifyBySession(rt.store.searchObservations(null, "walrus", 10u), 4, MAX_PER_SESSION)
            assertTrue(mixed.any { it.sessionId == "session-bbb" })
            assertTrue(mixed.count { it.sessionId == "session-aaa" } <= MAX_PER_SESSION)
            assertTrue(rt.estimate(inj.text, Class.Prose) <= 200u)
        }

    private fun gated(
        rt: Runtime,
        t: String,
        b: String,
    ) = memSaveGated(rt, "note", t, b, "t671")

    @Test fun aRepeatedBodyUnderANewTitleIsRefusedWithTheStoredId() =
        memRt("t671-dup").use { rt ->
            val id = (gated(rt, "cache", "The walrus cache evicts the oldest entry.") as Saved.Stored).id
            assertEquals(Saved.Refused("duplicate", id), gated(rt, "cache again", "the walrus  cache evicts the OLDEST entry."))
            assertEquals(1, rt.store.listNoteTitles("t671", 10u).size)
        }

    @Test fun aRevisionUnderTheSameTopicKeyStillUpdates() =
        memRt("t671-rev").use { rt ->
            gated(rt, "cache", "The walrus cache evicts the oldest entry.")
            assertEquals(true, (gated(rt, "cache", "The walrus cache evicts the oldest entry first.") as Saved.Stored).updated)
        }

    @Test fun aBodyUnderTheFloorIsRefusedAndAnOrdinaryNoteIsStored() =
        memRt("t671-short").use { rt ->
            assertEquals(Saved.Refused("too_short", null), gated(rt, "tiny", "ok"))
            assertEquals(false, (gated(rt, "real", "Hooks exit zero within ten milliseconds.") as Saved.Stored).updated)
        }

    @Test fun theGateIsPerProjectAndCanBeSwitchedOff() =
        memRt("t671-off").use { rt ->
            val body = "The walrus cache evicts the oldest entry."
            gated(rt, "cache", body)
            assertIs<Saved.Stored>(memSaveGated(rt, "note", "cache", body, "elsewhere"))
            rt.mem { it.copy(admit = false) }
            assertEquals(false, (gated(rt, "cache twin", body) as Saved.Stored).updated)
        }

    private fun firstTitle(
        rt: Runtime,
        q: String,
    ) = rt.store.searchNotes(q, 5u)[0].title

    @Test fun aReadNoteLeadsAnEqualTextTwinInSearch() =
        memRt("t669-used").use { rt ->
            rt.save("note", "rank-aaa", "quokka ledger entry")
            val used = rt.save("note", "rank-bbb", "quokka ledger entry")
            assertEquals("rank-aaa", firstTitle(rt, "quokka"))
            repeat(10) { rt.store.touchNote(used) }
            assertEquals("rank-bbb", firstTitle(rt, "quokka"))
        }

    @Test fun aPinnedNoteLeadsAnEqualTextTwinInSearch() =
        memRt("t669-pin").use { rt ->
            rt.save("note", "rank-aaa", "quokka ledger entry")
            rt.store.setNotePinned(rt.save("note", "rank-bbb", "quokka ledger entry"), true)
            assertEquals("rank-bbb", firstTitle(rt, "quokka"))
        }

    private fun aged(
        rt: Runtime,
        kind: String,
        title: String,
        days: Long,
    ): Int =
        rt.store.insertPortableNoteIfAbsent(
            PortableNote(
                null,
                kind,
                title,
                "stale body text",
                ts =
                    kotlin.time.Clock.System
                        .now()
                        .epochSeconds - days * 86_400,
            ),
        )!!

    @Test fun forgetRetiresOnlyOldUnreadUnpinnedNotesAndOnlyOnApply() =
        memRt("t669-forget").use { rt ->
            val stale = aged(rt, "note", "stale", 200)
            val read = aged(rt, "note", "read", 200)
            val pinned = aged(rt, "note", "pinned", 200)
            val others = listOf(read, pinned, aged(rt, "decision", "decision", 200), aged(rt, "user", "user", 200), aged(rt, "note", "fresh", 3))
            rt.store.touchNote(read)
            rt.store.setNotePinned(pinned, true)
            val dry = memForget(rt, null, 90u, false)
            assertTrue(dry.startsWith("would retire 1 notes"), dry)
            assertTrue("$stale stale" in dry)
            assertNull(rt.store.noteRow(stale)!!.retired)
            assertTrue(memForget(rt, null, 90u, true).startsWith("retired 1 notes"))
            assertNotNull(rt.store.noteRow(stale)!!.retired)
            others.forEach { assertNull(rt.store.noteRow(it)!!.retired, "$it was retired") }
            assertEquals("stale body text", rt.store.getNoteBody(stale))
        }

    @Test fun forgetIsOffWithoutDaysWhenTheConfigSaysZero() =
        memRt("t669-off") { it.copy(forgetAfterDays = 0u) }.use { rt ->
            assertTrue(memForget(rt, null, null, true).startsWith("forget is off"))
        }

    private fun firstIn(
        rt: Runtime,
        q: String,
        p: String?,
    ) = memSearchIn(rt, q, 5u, p)[0].title

    @Test fun theCurrentProjectLeadsAnEqualTextTwinFromAnother() =
        memRt("t667-project").use { rt ->
            rt.save("note", "twin-beta", "quokka ledger entry", "beta")
            rt.save("note", "twin-alpha", "quokka ledger entry", "alpha")
            assertEquals("twin-beta", firstIn(rt, "quokka", null))
            assertEquals("twin-alpha", firstIn(rt, "quokka", "alpha"))
            assertEquals("twin-beta", firstIn(rt, "quokka", "beta"))
        }

    @Test fun aProjectNoteFarDownTheTextRankingDoesNotBeatTheBestMatch() =
        memRt("t667-far").use { rt ->
            val filler = "unrelated words about caches and hooks ".repeat(8)
            rt.save("note", "best", "quokka quokka quokka quokka", "beta")
            for ((t, h) in listOf("mid1" to "quokka quokka quokka", "mid2" to "quokka quokka")) rt.save("note", t, "$filler $h", "beta")
            rt.save("note", "weak", "$filler quokka", "alpha")
            assertEquals("best", firstIn(rt, "quokka", "alpha"))
        }

    @Test fun aPinnedWeakMatchDoesNotBeatAClearlyBetterOne() =
        memRt("t669-strong").use { rt ->
            val filler = "unrelated words about caches and hooks ".repeat(8)
            val weak = rt.save("note", "rank-weak", "$filler quokka")
            for ((t, h) in listOf("rank-mid" to "quokka quokka", "rank-mid2" to "quokka quokka quokka", "rank-mid3" to "quokka quokka quokka quokka")) rt.save("note", t, "$filler $h")
            rt.save("note", "rank-best", "quokka quokka quokka quokka")
            rt.store.setNotePinned(weak, true)
            assertEquals("rank-best", firstTitle(rt, "quokka"))
        }
}
