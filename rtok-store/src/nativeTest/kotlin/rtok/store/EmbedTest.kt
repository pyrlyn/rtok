package rtok.store

import rtok.plugin.sdk.NoteHit
import kotlin.concurrent.AtomicInt
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

/** Port of embed.rs tests and remote_tests. */
class EmbedTest {
    private val s = Store.openInMemory()
    private val on = EmbedSettings(enabled = true, dimensions = 384u)
    private fun ids(h: List<NoteHit>) = h.map { it.id }
    private fun titles(h: List<NoteHit>) = h.map { it.title }
    private fun embeddingRows() = s.conn { it.query("SELECT note_id, dims, text_hash FROM note_embeddings ORDER BY note_id") { r -> Triple(r.int(0), r.int(1), r.string(2)) } }
    private val planted = "p29-gate-arctic-tern" to "Hooks must exit in ≤10 ms fail-open; async ORM rejected — Diesel stays sync on the hook path (D13)."
    private val decoy = "p29-decoy-etl-batch" to "Storage indexing and schema migration patterns for batch ETL pipelines in data warehouses."
    private val hooksQuery = "why not use an async database library for hooks"

    @Test fun rrfMergeBreaksScoreTiesByNoteId() {
        val fts = listOf(NoteHit(30, "c", "")); val knn = listOf(NoteHit(10, "a", ""))
        val first = ids(rrfMerge(fts, knn, 10u)); assertEquals(listOf(10, 30), first); repeat(50) { assertEquals(first, ids(rrfMerge(fts, knn, 10u))) }
    }

    @Test fun hashEmbedIsDeterministicAndUnitLength() {
        val a = hashEmbed("hello world", 384u); assertContentEquals(a, hashEmbed("hello world", 384u))
        assertTrue(abs(sqrt(a.fold(0f) { acc, x -> acc + x * x }) - 1f) < 1e-5f)
    }

    @Test fun aCyrillicNoteHasAVector() {
        val a = hashEmbed("хуки не блокируют", 64u); assertTrue(a.any { it != 0f })
        assertTrue(cosine(a, hashEmbed("Хуки НЕ блокируют", 64u)) > 0.999f); assertTrue(cosine(a, hashEmbed("миграция схемы базы", 64u)) < 0.5f)
    }

    @Test fun hookAsyncTextIsCloserThanUnrelated() {
        val qv = hashEmbed(hooksQuery, 384u); val pv = hashEmbed(noteEmbedText(planted.first, planted.second), 384u); val dv = hashEmbed(noteEmbedText(decoy.first, decoy.second), 384u)
        assertTrue(cosine(qv, pv) > cosine(qv, dv), "planted ${cosine(qv, pv)} vs decoy ${cosine(qv, dv)}")
    }

    @Test fun staleAndMissingVectorsAreEmbeddedBeforeKnn() {
        val id = s.insertNote(null, "note", "t", "alpha beta"); s.upsertNoteEmbedding(id, "t", "alpha beta", on)
        s.insertNote(null, "note", "u", "alpha gamma"); assertEquals(2, s.searchNotesEmbed("alpha", 5u, on.copy(dimensions = 8u)).size)
    }

    @Test fun aPassingMentionOfHookIsNotBoosted() {
        for ((t, b) in listOf("aa" to "hook", "bb" to "hooks path guide for new contributors on the team")) { val id = s.insertNote(null, "note", t, b); s.upsertNoteEmbedding(id, t, b, on) }
        assertEquals("bb", s.searchNotesEmbed("hooks path", 1u, on)[0].title)
    }

    @Test fun ftsPhraseUnchangedForFlagOffPath() = assertEquals("\"Diesel\" \"sync\"", ftsPhraseQuery("Diesel sync"))

    @Test fun storedHybridReturnsTheFtsListWhenTheTableIsEmpty() {
        s.insertNote(null, "note", "walrus", "the walrus journal lives here"); assertEquals(0L, s.noteEmbeddingCount())
        val fts = s.searchNotes("walrus journal", 5u); val hybrid = s.searchNotesHybridStored("walrus journal", 5u, on)
        assertEquals(ids(fts), ids(hybrid)); assertEquals(fts.map { it.snippet }, hybrid.map { it.snippet }); assertEquals(0L, s.noteEmbeddingCount())
    }

    @Test fun storedHybridHitsAStoredVectorAndDoesNotWrite() {
        val p = s.insertNote(null, "decision", planted.first, planted.second); s.upsertNoteEmbedding(p, planted.first, planted.second, on)
        val d = s.insertNote(null, "decision", decoy.first, decoy.second); s.upsertNoteEmbedding(d, decoy.first, decoy.second, on)
        val missing = s.insertNote(null, "note", "unembedded", "alpha gamma extra")
        assertTrue(s.searchNotes(hooksQuery, 10u).none { it.id == p }, "FTS must miss the planted note")
        val before = embeddingRows(); s.searchNotesHybridStored(hooksQuery, 10u, on.copy(dimensions = 8u))
        assertEquals(before, embeddingRows(), "stale vectors stay as stored"); assertTrue(before.none { it.first == missing })
        val hybrid = s.searchNotesHybridStored(hooksQuery, 10u, on); assertTrue(hybrid.any { it.id == p }, "stored vector is eligible: $hybrid")
        assertEquals(ids(hybrid), ids(s.searchNotesHybridStored(hooksQuery, 10u, on))); assertEquals(before, embeddingRows())
    }

    // ---- remote_tests (T668): a stub stands in for the HTTP embedder ----

    /** Concept axes: "car" and "automobile" share one, which FTS cannot see. */
    private class Stub : TextEmbedder {
        var down = false; var notesDown = false; val calls = AtomicInt(0)
        override fun embed(text: String): FloatArray? {
            calls.incrementAndGet(); if (down || ('\n' in text && notesDown)) return null
            val t = text.lowercase(); fun axis(vararg w: String) = if (w.any { it in t }) 1f else 0f
            return floatArrayOf(axis("car", "automobile"), axis("banana", "fruit"), 0.1f)
        }
    }
    private fun setup(stub: Stub): EmbedSettings {
        for ((t, b) in listOf("fruit bowl" to "a ripe banana", "garage" to "the car is red")) s.insertNote(null, "note", t, b)
        return EmbedSettings(enabled = true, dimensions = 3u, remote = stub)
    }

    @Test fun remoteVectorsRankASynonymThatFtsMisses() {
        val cfg = setup(Stub()); assertTrue(s.searchNotes("automobile", 5u).isEmpty())
        assertEquals(listOf("garage"), titles(s.searchNotesEmbed("automobile", 1u, cfg))); assertEquals(listOf("garage"), titles(s.searchNotesHybrid("automobile", 1u, cfg)))
    }

    @Test fun aFailingRemoteServesThePlainNoteSearchAndWritesNothing() {
        val stub = Stub(); val cfg = setup(stub); stub.down = true
        for (q in listOf("car", "automobile")) { val plain = titles(s.searchNotes(q, 5u)); assertEquals(plain, titles(s.searchNotesEmbed(q, 5u, cfg))); assertEquals(plain, titles(s.searchNotesHybrid(q, 5u, cfg))) }
        assertEquals(0L, s.noteEmbeddingCount(), "no hash vector either"); assertEquals(4, stub.calls.value)
    }

    @Test fun indexingStopsAtTheFirstFailureAndResumesLater() {
        val stub = Stub(); val cfg = setup(stub); stub.notesDown = true
        s.searchNotesEmbed("car", 5u, cfg); assertEquals(2, stub.calls.value); assertEquals(0L, s.noteEmbeddingCount())
        stub.notesDown = false; s.searchNotesEmbed("car", 5u, cfg); assertEquals(2L, s.noteEmbeddingCount())
    }

    @Test fun theHookLegNeverCallsTheRemote() {
        val stub = Stub(); val cfg = setup(stub); s.searchNotesEmbed("car", 5u, cfg); val calls = stub.calls.value
        assertEquals(titles(s.searchNotes("car", 5u)), titles(s.searchNotesHybridStored("car", 5u, cfg))); assertEquals(calls, stub.calls.value)
    }

    @Test fun aVectorOfTheWrongLengthIsDropped() {
        val cfg = setup(Stub()).copy(dimensions = 8u); s.searchNotesEmbed("car", 5u, cfg); assertEquals(0L, s.noteEmbeddingCount())
    }

    @Test fun remoteAndHashVectorsNeverShareAKey() {
        val cfg = setup(Stub()); val hash = cfg.copy(remote = null)
        assertEquals("all-MiniLM-L6-v2+hash2", modelKey(hash), "unchanged for hash"); assertNotEquals(modelKey(cfg), modelKey(hash))
    }

    @Test fun disabledEmbeddingsNeverReachTheRemote() {
        val stub = Stub(); val cfg = setup(stub).copy(enabled = false); s.upsertNoteEmbedding(1, "garage", "the car is red", cfg)
        assertEquals(0, stub.calls.value); assertEquals(0L, s.noteEmbeddingCount())
    }

    @Test fun hashEmbedFollowsTheSlotAndSignScheme() {
        // The Rust scheme, restated (sha256 of the lowercased token, four LE u64 slices, h % dims, sign = top bit).
        val v = hashEmbed("ab", 16u); val d = rtok.sys.Sha256.digest("ab".encodeToByteArray()); val want = FloatArray(16)
        for (c in 0 until 4) { var h = 0uL; for (b in 7 downTo 0) h = (h shl 8) or d[c * 8 + b].toUByte().toULong(); want[(h % 16uL).toInt()] += if (h shr 63 == 0uL) 1f else -1f }
        val n = sqrt(want.fold(0f) { a, x -> a + x * x }); for (i in want.indices) want[i] /= n
        assertContentEquals(want, v)
    }
}
