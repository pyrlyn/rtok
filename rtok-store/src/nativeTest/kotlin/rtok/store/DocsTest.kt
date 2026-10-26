package rtok.store

import kotlin.test.*

/** docs.rs has no unit tests of its own (the `docs` plugin tests drive it); these pin the store contract. */
class DocsTest {
    private val s = Store.openInMemory()
    private val items = listOf(DocItemInput("serde::Serialize", "trait", "A data structure that can be serialized."), DocItemInput("serde::de", "module", "Generic data structure deserialization."))

    @Test fun searchFindsCachedItemsScopedToCrateAndVersion() {
        assertFalse(s.docCrateCached("serde", "1.0")); assertEquals(2, s.replaceDocCrate("serde", "1.0", "sha", 39, 100, items)); assertTrue(s.docCrateCached("serde", "1.0"))
        val hits = s.searchDocItems("serde", "1.0", "serialized", 5u, 10u); assertEquals(listOf("serde::Serialize"), hits.map { it.path }); assertEquals("A data str", hits[0].snippet)
        assertTrue(s.searchDocItems("serde", "2.0", "serialized", 5u, 10u).isEmpty()); assertTrue(s.searchDocItems("serde", "1.0", "\"\"", 5u, 10u).isEmpty())
        assertEquals("trait", s.getDocItem(hits[0].id)!!.kind); assertEquals(listOf(items[0].third), s.docItemsDocs(listOf(hits[0].id)))
    }

    @Test fun syncWithTheSameShaKeepsIdsAndDeleteDropsEverything() {
        s.replaceDocCrate("serde", "1.0", "sha", 39, 100, items); val id = s.searchDocItems("serde", "1.0", "serialized", 5u, 400u)[0].id
        assertNull(s.syncDocCrate("serde", "1.0", "sha", 39, 200, items)); assertNotNull(s.getDocItem(id))
        assertEquals(1, s.syncDocCrate("serde", "1.0", "sha2", 39, 200, items.take(1))); assertEquals(1L, s.conn { it.longOf("SELECT count(*) FROM doc_items") }, "a changed crate is rewritten")
        s.deleteDocCrate("serde", "1.0"); assertFalse(s.docCrateCached("serde", "1.0")); assertTrue(s.searchDocItems("serde", "1.0", "serialized", 5u, 10u).isEmpty())
    }
}
