// Port of crates/rtok-store/src/docs.rs (T455): cached rustdoc rows for the `docs` plugin.
package rtok.store

/** One FTS hit: id, path, kind, snippet (the shape `docs_query` prints). */
data class DocHit(val id: Int, val path: String, val kind: String, val snippet: String)

/** Full cached item for `docs_get`. */
data class DocItem(val id: Int, val crateName: String, val version: String, val path: String, val kind: String, val docs: String)

/** `(path, kind, docs)` of one item to cache. */
typealias DocItemInput = Triple<String, String, String>

fun Store.docCrateCached(name: String, version: String): Boolean = conn { it.longOf("SELECT count(*) FROM doc_crates WHERE name = ? AND version = ?", name, version)!! > 0 }

/** FTS5 search in BM25 order with `snippetChars`-long snippets; nothing quotable → no hits. */
fun Store.searchDocItems(crateName: String, version: String, query: String, limit: UInt, snippetChars: UInt): List<DocHit> {
    val q = ftsPhraseQuery(query) ?: return emptyList()
    val snippet = snippetChars.takeIf { it <= Int.MAX_VALUE.toUInt() }?.toInt() ?: 400
    val lim = limit.takeIf { it <= Int.MAX_VALUE.toUInt() }?.toInt() ?: 5
    return conn { it.query("SELECT i.id, i.path, i.kind, substr(i.docs, 1, ?) FROM doc_items_fts f JOIN doc_items i ON i.id = f.rowid " +
        "WHERE doc_items_fts MATCH ? AND i.crate_name = ? AND i.version = ? ORDER BY bm25(doc_items_fts) LIMIT ?", snippet, q, crateName, version, lim) { r ->
        DocHit(r.int(0), r.string(1), r.string(2), r.string(3)) } }
}

fun Store.getDocItem(id: Int): DocItem? = conn {
    it.queryOne("SELECT id, crate_name, version, path, kind, docs FROM doc_items WHERE id = ?", id) { r -> DocItem(r.int(0), r.string(1), r.string(2), r.string(3), r.string(4), r.string(5)) }
}

/** Full bodies of the given hits, in id order: the baseline byte and token accounting share. */
fun Store.docItemsDocs(ids: List<Int>): List<String> = if (ids.isEmpty()) emptyList() else conn {
    it.query("SELECT docs FROM doc_items WHERE id IN (${inList(ids.size)}) ORDER BY id ASC", *ids.toTypedArray()) { r -> r.string(0) }
}

private fun deleteDocCrateIn(db: Db, name: String, version: String) {
    db.execute("DELETE FROM doc_items WHERE crate_name = ? AND version = ?", name, version)
    db.execute("DELETE FROM doc_crates WHERE name = ? AND version = ?", name, version)
}

private fun replaceDocCrateIn(db: Db, name: String, version: String, sha256: String, formatVersion: Int, fetchedUnix: Long, items: List<DocItemInput>): Int {
    deleteDocCrateIn(db, name, version)
    db.execute("INSERT INTO doc_crates (name, version, sha256, format_version, fetched_unix) VALUES (?, ?, ?, ?, ?)", name, version, sha256, formatVersion, fetchedUnix)
    for ((path, kind, docs) in items) db.execute("INSERT INTO doc_items (crate_name, version, path, kind, docs) VALUES (?, ?, ?, ?, ?)", name, version, path, kind, docs)
    return items.size
}

/** Replace the cached crate row and its items in one transaction. */
fun Store.replaceDocCrate(name: String, version: String, sha256: String, formatVersion: Int, fetchedUnix: Long, items: List<DocItemInput>): Int = conn { db ->
    db.transaction { replaceDocCrateIn(db, name, version, sha256, formatVersion, fetchedUnix, items) }
}

/** [replaceDocCrate] unless the cached row already has `sha256` (ids stay valid); `null` = unchanged. */
fun Store.syncDocCrate(name: String, version: String, sha256: String, formatVersion: Int, fetchedUnix: Long, items: List<DocItemInput>): Int? = conn { db ->
    db.transaction("IMMEDIATE") {
        if (db.stringOf("SELECT sha256 FROM doc_crates WHERE name = ? AND version = ?", name, version) == sha256) null
        else replaceDocCrateIn(db, name, version, sha256, formatVersion, fetchedUnix, items)
    }
}

/** Drop a cached crate row and its items (a local source that disappeared). */
fun Store.deleteDocCrate(name: String, version: String) = conn { db -> db.transaction { deleteDocCrateIn(db, name, version) } }
