// Port of src/plugins/memory/extract.rs tests.
package rtok.plugins.memory

import kotlin.test.*
import rtok.plugin.Runtime
import rtok.store.searchNotes
import rtok.sys.*

private class Fake(val c: List<Candidate>) : Extractor { override fun extract(transcript: String, maxNotes: Int) = c.take(maxNotes) }
private class Spy : Extractor { var seen = ""; override fun extract(transcript: String, maxNotes: Int): List<Candidate> { seen = transcript; return emptyList() } }
private fun cand(t: String, b: String) = Candidate(t, b)
private fun rt(s: String, on: Boolean, max: UInt? = null, bytes: UInt? = null) = memRt(s) { m ->
    m.copy(extract = m.extract.copy(enabled = on, maxNotes = max ?: m.extract.maxNotes, maxInputBytes = bytes ?: m.extract.maxInputBytes))
}
private fun transcript(rt: Runtime, body: String) = (parent(rt.config.log.path)!! + "/t.jsonl").also { writeString(it, body) }

private val SESSION = """{"type":"user","message":{"content":"we picked sqlite for the cache"}}""" + "\n" +
    """{"type":"assistant","message":{"content":[{"type":"text","text":"noted <private>hunter2</private> done"},{"type":"tool_use","name":"Bash"}]}}""" + "\n" +
    """{"type":"summary"}""" + "\nnot json\n"

class ExtractTest {
    @Test fun itRefusesWhileDisabledAndSavesNothing() = rt("t666-off", false).use { r ->
        assertTrue("off" in assertFails { runExtract(r, Fake(listOf(cand("a", "b"))), transcript(r, SESSION)) }.message!!)
        assertTrue(r.store.searchNotes("b", 5u).isEmpty())
    }
    @Test fun theConfiguredRunReportsOffBeforeItLooksAtTheCommand() = rt("t666-cfg", false).use { r ->
        assertTrue("is off" in assertFails { runConfiguredExtract(r, transcript(r, SESSION)) }.message!!)
    }
    @Test fun itSavesCandidatesAsPlainNotesAndARepeatUpdatesNotDuplicates() = rt("t666-save", true).use { r ->
        val f = transcript(r, SESSION); val fake = Fake(listOf(cand("cache store", "the cache lives in sqlite")))
        assertTrue("saved 1" in runExtract(r, fake, f)); runExtract(r, fake, f)
        assertEquals(1, r.store.searchNotes("sqlite", 5u).size)
    }
    @Test fun theModelSeesOnlyChatTextWithPrivateSpansRedacted() = rt("t666-scrub", true).use { r ->
        val spy = Spy(); runExtract(r, spy, transcript(r, SESSION))
        assertTrue("user: we picked sqlite" in spy.seen, spy.seen); assertFalse("hunter2" in spy.seen); assertFalse("Bash" in spy.seen)
    }
    @Test fun theInputIsCappedToTheEndOfTheSession() = rt("t666-cap", true, bytes = 40u).use { r ->
        val spy = Spy(); runExtract(r, spy, transcript(r, SESSION))
        assertTrue(spy.seen.encodeToByteArray().size <= 40); assertTrue(spy.seen.trimEnd().endsWith("done"), spy.seen)
    }
    @Test fun aCandidateRepeatingAStoredNoteIsSkippedByTheAdmissionGate() = rt("t666-gate", true).use { r ->
        val out = runExtract(r, Fake(listOf(cand("first take", "the cache lives in sqlite"), cand("second take", "the cache lives in sqlite"))), transcript(r, SESSION))
        assertTrue("saved 1, skipped 1" in out, out)
    }
    @Test fun theNoteCountIsCappedAndAKindIsNeverTakenFromTheModel() = rt("t666-max", true, max = 2u).use { r ->
        val out = runExtract(r, Fake(listOf(cand("one", "alpha topic"), cand("two", "beta topic"), cand("three", "gamma topic"))), transcript(r, SESSION))
        assertTrue("saved 2" in out, out); assertTrue(r.store.searchNotes("gamma", 5u).isEmpty())
    }
    @Test fun parseSkipsBadLinesBlankFieldsAndCapsSizes() {
        val long = "x".repeat(5000)
        val got = parseCandidates("{\"title\":\"a\",\"body\":\"b\"}\nnot json\n{\"title\":\" \",\"body\":\"b\"}\n{\"title\":\"$long\",\"body\":\"$long\"}\n", 5)
        assertEquals(2, got.size); assertEquals(120, got[1].title.length); assertEquals(MAX_BODY, got[1].body.length)
    }
    @Test fun tailBytesNeverSplitsAChar() { assertEquals("b", tailBytes("aéb", 2)); assertEquals("abc", tailBytes("abc", 10)) }
    @Test fun anUnsetCommandIsAnErrorNotASilentNoOp() { assertFails { CommandExtractor(emptyList()) }; assertFails { CommandExtractor(listOf(" ")) } }
    @Test fun aCommandExtractorPipesTheTranscriptAndReadsJsonLines() {
        assertEquals(listOf(cand("t", "b")), CommandExtractor(listOf("sh", "-c", "cat >/dev/null; echo '{\"title\":\"t\",\"body\":\"b\"}'")).extract("hello", 3))
        assertFails { CommandExtractor(listOf("sh", "-c", "exit 3")).extract("x", 3) }
    }
}
