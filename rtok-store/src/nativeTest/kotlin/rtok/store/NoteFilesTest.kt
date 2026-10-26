package rtok.store

import kotlin.test.*

/** Port of note_files.rs tests, plus host_usage's fixture round trip. */
class NoteFilesTest {
    private val s = Store.openInMemory()
    private fun note(title: String, project: String? = null) = s.upsertNote(project, "note", title, "x").first
    private fun ids(project: String?, vararg paths: String) = s.notesForFiles(project, paths.toList(), 10u).map { it.id }

    @Test fun linksRoundTripAndAResaveReplacesThem() {
        val a = s.upsertNote(null, "note", "a", "body").first
        s.setNoteFiles(a, listOf("src/a.rs", "src/a.rs", "src/b.rs")); assertEquals(listOf(a), ids(null, "src/b.rs"))
        s.setNoteFiles(a, listOf("src/c.rs")); assertTrue(ids(null, "src/b.rs").isEmpty()); assertEquals(1, ids(null, "src/c.rs").size)
        assertTrue(ids(null).isEmpty())
    }

    @Test fun mostLinkedFirstThenNewestAndRetiredNotesAreSkipped() {
        val one = note("one"); val two = note("two"); val both = note("both"); val gone = note("gone")
        s.setNoteFiles(one, listOf("a")); s.setNoteFiles(two, listOf("a")); s.setNoteFiles(both, listOf("a", "b")); s.setNoteFiles(gone, listOf("a", "b"))
        s.retireNote(gone, null); assertEquals(listOf(both, two, one), ids(null, "a", "b"))
    }

    @Test fun aProjectScopeKeepsOtherProjectsNotesOut() {
        val mine = note("m", "rtok"); val loose = note("l"); val other = note("o", "other")
        for (id in listOf(mine, loose, other)) s.setNoteFiles(id, listOf("src/main.rs"))
        assertEquals(listOf(mine, loose), ids("rtok", "src/main.rs").sorted()); assertEquals(listOf(mine, loose, other), ids(null, "src/main.rs").sorted())
    }

    @Test fun readCacheKeysAreScopedToTheSession() {
        s.putReadCache("s1", "src/a.rs\tfull\t", "h", null); s.putReadCache("s2", "src/b.rs\tfull\t", "h", null)
        assertEquals(listOf("src/a.rs\tfull\t"), s.readCacheKeys("s1"))
    }

    @Test fun hostMessagesAreReadReadOnlyOldestFirstFromTheSince() {
        val dir = tmpDir("host-usage"); val path = "$dir/opencode.db"
        seedHostMessages(path, listOf(Triple("s", 30L, "c"), Triple("s", 10L, "a"), Triple("t", 20L, "b")))
        assertEquals(listOf(Triple("t", 20L, "b"), Triple("s", 30L, "c")), readHostMessages("file:$path?mode=ro", 20))
        rtok.sys.removeDirAll(dir)
    }
}
