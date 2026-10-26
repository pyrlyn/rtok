// Port of crates/rtok-store/src/symbols.rs (T8.10): the `graph` plugin's symbol index. Every
// call is scoped to a canonical root, so repos sharing one store never answer for each other.
package rtok.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import rtok.plugin.sdk.SymbolFile
import rtok.plugin.sdk.SymbolRow
import rtok.plugin.sdk.SymbolSpan
import rtok.sys.Utf8Order
import rtok.sys.asciiLowercase
import rtok.sys.compareUtf8
import rtok.sys.fileStat
import rtok.sys.join

/** Columns bound per inserted symbol row; SQLite's default variable limit is 999. */
private const val INSERT_CHUNK = 999 / 16
/** A BFS frontier is chunked below the bind limit. */
private const val NAME_CHUNK = 500

private const val NOT_IMPORT_REF = "is_def = 0 AND kind <> 'import'"

private const val SYMBOL_INSERT_COLS = "root, path, name, kind, line, is_def, file_sha, mtime, size, end_line, scope, start_byte, end_byte, content_hash, signature, doc"

// ---- FTS ranking helpers ----

internal fun ftsTokens(query: String): List<String> {
    val seen = HashSet<String>()
    return query.split { !it.isLetterOrDigit() && it != '_' }.filter { it.isNotEmpty() && seen.add(it.asciiLowercase()) }
}
private inline fun String.split(isSep: (Char) -> Boolean): List<String> {
    val out = ArrayList<String>(); val cur = StringBuilder()
    for (c in this) if (isSep(c)) { out += cur.toString(); cur.clear() } else cur.append(c)
    out += cur.toString(); return out
}
private fun ftsOr(tokens: List<String>) = tokens.joinToString(" OR ") { "\"${it.replace("\"", "")}\"" }
private fun ftsHas(hay: String, token: String) = hay.asciiLowercase().contains(token.asciiLowercase())
/** 0 name, 1 signature, 2 doc. */
private fun ftsTier(name: String, signature: String, tokens: List<String>) = when { tokens.any { ftsHas(name, it) } -> 0; tokens.any { ftsHas(signature, it) } -> 1; else -> 2 }
private fun ftsCoverage(name: String, signature: String, doc: String, tokens: List<String>) = tokens.count { ftsHas(name, it) || ftsHas(signature, it) || ftsHas(doc, it) }

// ---- row writers (inside the caller's transaction) ----

private fun deleteFile(db: Db, root: String, path: String) = db.execute("DELETE FROM symbols WHERE root = ? AND path = ?", root, path)
private fun noteStale(db: Db, root: String, path: String) = db.execute("INSERT OR IGNORE INTO symbol_stale (root, path) VALUES (?, ?)", root, path)
private fun clearStale(db: Db, root: String, path: String) = db.execute("DELETE FROM symbol_stale WHERE root = ? AND path = ?", root, path)

/** Multi-row INSERTs of `rows` (each a full value list of `cols`), chunked under the bind limit. */
private fun insertChunked(db: Db, table: String, cols: String, rows: List<List<Any?>>) {
    val width = cols.split(',').size
    for (chunk in rows.chunked(INSERT_CHUNK)) {
        val values = chunk.joinToString(", ") { "(${inList(width)})" }
        db.execute("INSERT INTO $table ($cols) VALUES $values", *chunk.flatten().toTypedArray())
    }
}

/** One file's rows: drop what it had, insert the new tags; a tagless file keeps one nameless row so its stat stands. */
private fun replaceOne(db: Db, root: String, path: String, sha: String, stat: Pair<Long, Long>, rows: List<SymbolRow>): Int {
    deleteFile(db, root, path)
    if (rows.isEmpty()) {
        db.execute("INSERT INTO symbols (root, path, name, kind, line, is_def, file_sha, mtime, size) VALUES (?, ?, '', '', 0, 0, ?, ?, ?)", root, path, sha, stat.first, stat.second)
        return 0
    }
    insertChunked(db, "symbols", SYMBOL_INSERT_COLS, rows.map { r ->
        listOf(root, path, r.name, r.kind, r.line, if (r.isDef) 1 else 0, sha, stat.first, stat.second, r.endLine, r.scope, r.startByte, r.endByte, r.contentHash, r.signature, r.doc)
    })
    clearStale(db, root, path)
    return rows.size
}

/** One query per chunk of `names`, bound as the trailing `IN (#)` list after `pre`. */
private fun <T> Db.chunkedIn(sql: String, pre: List<Any?>, names: List<String>, map: (Row) -> T): List<T> =
    names.chunked(NAME_CHUNK).flatMap { c -> query(sql.replace("#", inList(c.size)), *(pre + c).toTypedArray(), map = map) }

private fun <K, V> groupByTip(rows: List<Pair<K, V>>, byTip: MutableMap<K, MutableList<V>>) {
    for ((tip, v) in rows) { val l = byTip.getOrPut(tip) { ArrayList() }; if (v !in l) l += v }
}

// ---- reads ----

fun Store.symbolCount(root: String): Long = conn { it.longOf("SELECT count(*) FROM symbols WHERE root = ?", root)!! }
fun Store.symbolFileCount(root: String): Long = conn { it.longOf("SELECT count(*) FROM (SELECT DISTINCT path FROM symbols WHERE root = ?)", root)!! }
fun Store.symbolStalePaths(root: String): List<String> = conn { it.query("SELECT path FROM symbol_stale WHERE root = ? ORDER BY path ASC", root) { r -> r.string(0) } }

/** Hook-staled paths plus indexed paths whose stat no longer matches disk. */
fun Store.symbolPending(root: String, rootPath: String): List<String> {
    val pending = symbolStalePaths(root).toHashSet()
    for ((path, s) in symbolStats(root)) {
        val stat = fileStat(join(rootPath, path)) ?: (0L to 0L)
        if (stat != (s.second to s.third) && stat != (0L to 0L)) pending += path
    }
    return pending.sortedWith(Utf8Order)
}

/** `(sha256, mtime_nanos, size)` of one indexed file (T8.4). */
fun Store.symbolStat(root: String, path: String): Triple<String, Long, Long>? = conn {
    it.queryOne("SELECT file_sha, mtime, size FROM symbols WHERE root = ? AND path = ? LIMIT 1", root, path) { r -> Triple(r.string(0), r.long(1), r.long(2)) }
}

fun Store.symbolStats(root: String): Map<String, Triple<String, Long, Long>> = conn { db ->
    db.query("SELECT DISTINCT path, file_sha, mtime, size FROM symbols WHERE root = ?", root) { it.string(0) to Triple(it.string(1), it.long(2), it.long(3)) }.toMap()
}

/** Copy `from`'s rows onto `to`, skipping paths `to` has and paths under `skipUnder`; the fingerprint follows when `to` has none. */
fun Store.copySymbolRows(from: String, to: String, skipUnder: List<String>): Int {
    if (from == to) return 0
    return conn { db -> db.transaction("IMMEDIATE") {
        val have = db.query("SELECT DISTINCT path FROM symbols WHERE root = ?", to) { it.string(0) }.toHashSet()
        val rows = db.query("SELECT path, name, kind, line, is_def, file_sha, mtime, size, end_line, scope FROM symbols WHERE root = ?", from) {
            listOf<Any?>(to, it.string(0), it.string(1), it.string(2), it.int(3), it.int(4), it.string(5), it.long(6), it.long(7), it.int(8), it.string(9))
        }.filter { r -> val p = r[1] as String; p !in have && skipUnder.none { d -> p.startsWith(d) && p.removePrefix(d).startsWith('/') } }
        insertChunked(db, "symbols", "root, path, name, kind, line, is_def, file_sha, mtime, size, end_line, scope", rows)
        if (db.stringOf("SELECT fingerprint FROM extractor WHERE root = ?", to) == null)
            db.stringOf("SELECT fingerprint FROM extractor WHERE root = ?", from)?.let { db.execute("INSERT INTO extractor (root, fingerprint) VALUES (?, ?)", to, it) }
        rows.size
    } }
}

/** A file whose content hashed the same gets only a new stat (T8.4). */
fun Store.touchSymbols(root: String, path: String, mtime: Long, size: Long) = conn { it.execute("UPDATE symbols SET mtime = ?, size = ? WHERE root = ? AND path = ?", mtime, size, root, path); Unit }

/** T352: every write is BEGIN IMMEDIATE, so WAL never fails a deferred upgrade. */
fun Store.replaceSymbolFiles(root: String, files: List<SymbolFile>): Int = if (files.isEmpty()) 0 else conn { db ->
    db.transaction("IMMEDIATE") { files.sumOf { replaceOne(db, root, it.path, it.sha, it.stat, it.rows) } }
}

fun Store.replaceSymbols(root: String, path: String, fileSha: String, stat: Pair<Long, Long>, rows: List<SymbolRow>): Int = conn { db ->
    db.transaction("IMMEDIATE") { replaceOne(db, root, path, fileSha, stat, rows) }
}

fun Store.deleteSymbolsMissing(root: String, keep: Set<String>): Int = conn { db ->
    if (keep.isEmpty()) return@conn db.execute("DELETE FROM symbols WHERE root = ?", root)
    val missing = db.query("SELECT DISTINCT path FROM symbols WHERE root = ?", root) { it.string(0) }.filter { it !in keep }
    missing.chunked(NAME_CHUNK).sumOf { c -> db.execute("DELETE FROM symbols WHERE root = ? AND path IN (${inList(c.size)})", root, *c.toTypedArray()) }
}

/** Drop rows for one absolute file path; every `/` split is tried as `(root, rel)` — indexed point deletes. */
fun Store.markSymbolsStale(absPath: String) = conn { db ->
    db.transaction("IMMEDIATE") {
        absPath.forEachIndexed { i, c -> if (c == '/') { val root = absPath.substring(0, i); val rel = absPath.substring(i + 1); if (deleteFile(db, root, rel) > 0) noteStale(db, root, rel) } }
    }
}

fun Store.markSymbolsStaleIn(root: String, relPath: String) = conn { db ->
    db.transaction("IMMEDIATE") { if (deleteFile(db, root, relPath) > 0) noteStale(db, root, relPath) }; Unit
}

fun Store.extractorFingerprint(root: String): String? = conn { it.stringOf("SELECT fingerprint FROM extractor WHERE root = ?", root) }
fun Store.setExtractorFingerprint(root: String, fp: String) = conn { it.execute("INSERT INTO extractor (root, fingerprint) VALUES (?, ?) ON CONFLICT (root) DO UPDATE SET fingerprint = excluded.fingerprint", root, fp); Unit }
fun Store.symbolIndexedAt(root: String): Long? = conn { it.longOf("SELECT indexed_at FROM extractor WHERE root = ?", root) }
fun Store.touchSymbolIndexedAt(root: String, ts: Long) = conn { db ->
    db.transaction("IMMEDIATE") {
        val fp = db.stringOf("SELECT fingerprint FROM extractor WHERE root = ?", root) ?: ""
        db.execute("INSERT INTO extractor (root, fingerprint, indexed_at) VALUES (?, ?, ?) ON CONFLICT (root) DO UPDATE SET indexed_at = excluded.indexed_at", root, fp, ts)
    }; Unit
}

private fun quad(r: Row) = Quad(r.string(0), r.string(1), r.int(2), r.int(3))
/** A 4-tuple row; `Quad(a, b, c, d)` mirrors Rust's `(A, B, C, D)` results. */
data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** T130.3: definitions in one file as `(name, kind, line, end_line)`, by line. */
fun Store.symbolFileDefs(root: String, path: String) = conn { it.query("SELECT name, kind, line, end_line FROM symbols WHERE root = ? AND path = ? AND is_def = 1 ORDER BY line ASC", root, path, map = ::quad) }
/** Definitions of `name` as `(path, kind, line, end_line)`, by path then line. */
fun Store.symbolDefs(root: String, name: String) = conn { it.query("SELECT path, kind, line, end_line FROM symbols WHERE root = ? AND name = ? AND is_def = 1 ORDER BY path ASC, line ASC", root, name, map = ::quad) }

/** Byte span of one definition (T474). */
fun Store.symbolSpan(root: String, path: String, name: String, kind: String, line: Int): SymbolSpan? = conn {
    it.queryOne("SELECT start_byte, end_byte, content_hash, file_sha FROM symbols WHERE root = ? AND path = ? AND name = ? AND kind = ? AND line = ? AND is_def = 1 LIMIT 1", root, path, name, kind, line) { r ->
        SymbolSpan(r.long(0).coerceAtLeast(0).toULong(), r.long(1).coerceAtLeast(0).toULong(), r.string(2), r.string(3))
    }
}

/** Definitions matching `query` in name, signature or doc: name hits outrank signature hits outrank doc hits (T474). */
fun Store.symbolFts(root: String, query: String, limit: Long): List<Quad<String, String, String, Int>> {
    if (limit <= 0) return emptyList()
    val tokens = ftsTokens(query); val match = ftsOr(tokens); if (match.isEmpty()) return emptyList()
    data class Hit(val path: String, val name: String, val kind: String, val line: Int, val sig: String, val doc: String, val bm25: Double)
    val hits = conn { it.query("SELECT s.path, s.name, s.kind, s.line, s.signature, s.doc, bm25(symbols_fts) FROM symbols_fts JOIN symbols s ON s.id = symbols_fts.rowid " +
        "WHERE symbols_fts MATCH ? AND s.root = ? AND s.is_def = 1 LIMIT ?", match, root, 200) { r -> Hit(r.string(0), r.string(1), r.string(2), r.int(3), r.string(4), r.string(5), r.double(6)) } }
    return hits.sortedWith(compareBy<Hit> { ftsTier(it.name, it.sig, tokens) }.thenByDescending { ftsCoverage(it.name, it.sig, it.doc, tokens) }
        .thenBy { it.bm25 }.thenComparator { a, b -> compareUtf8(a.path, b.path) }.thenBy { it.line }.thenComparator { a, b -> compareUtf8(a.name, b.name) })
        .take(minOf(limit, Int.MAX_VALUE.toLong()).toInt()).map { Quad(it.path, it.name, it.kind, it.line) }
}

/** Reference sites of `name` as `(path, line)` (T8.2 `callers`). */
fun Store.symbolRefs(root: String, name: String): List<Pair<String, Int>> = conn {
    it.query("SELECT path, line FROM symbols WHERE root = ? AND name = ? AND $NOT_IMPORT_REF ORDER BY path ASC, line ASC", root, name) { r -> r.string(0) to r.int(1) }
}

/** Callees of each definition of `name`: `(path, line, callee, first_ref_line)` (T68.2). */
fun Store.symbolCallees(root: String, name: String): List<Quad<String, Int, String, Int>> = conn {
    it.query("SELECT d.path, d.line, r.name, MIN(r.line) FROM symbols d JOIN symbols r ON r.root = d.root AND r.path = d.path AND r.is_def = 0 AND r.scope = d.name " +
        "WHERE d.root = ? AND d.is_def = 1 AND d.name = ? AND r.name <> '' GROUP BY d.path, d.line, r.name", root, name) { r -> Quad(r.string(0), r.int(1), r.string(2), r.int(3)) }
}.sortedWith { x, y -> compareUtf8(x.a, y.a).takeIf { it != 0 } ?: x.b.compareTo(y.b).takeIf { it != 0 } ?: x.d.compareTo(y.d).takeIf { it != 0 } ?: compareUtf8(x.c, y.c) }

/** Reference sites collapsed per calling definition (T8.5): `(path, scope, count, first line)`. */
fun Store.symbolRefGroups(root: String, name: String): List<Quad<String, String, Long, Int>> = conn {
    it.query("SELECT path, scope, count(*), MIN(line) FROM symbols WHERE root = ? AND name = ? AND $NOT_IMPORT_REF GROUP BY path, scope ORDER BY path ASC, scope ASC", root, name) { r ->
        Quad(r.string(0), r.string(1), r.long(2), r.intOrNull(3) ?: 0)
    }
}

fun Store.hasSymbolDef(root: String, name: String) = symbolDefs(root, name).isNotEmpty()
fun Store.symbolRefCount(root: String, name: String) = symbolRefs(root, name).size.toLong()

private const val REFS_OF_D = "(SELECT count(*) FROM symbols r WHERE r.root = d.root AND r.name = d.name AND r.is_def = 0 AND r.kind <> 'import')"

/** T52.3: names ranked by reference count with their first def site `(name, refs, path, line)`. */
fun Store.symbolTopRefs(root: String, limit: Long): List<Quad<String, Long, String, Int>> = conn {
    it.query("SELECT d.name, $REFS_OF_D, d.path, d.line FROM symbols d WHERE d.root = ? AND d.is_def = 1 AND d.name <> '' AND NOT EXISTS (SELECT 1 FROM symbols e " +
        "WHERE e.root = d.root AND e.name = d.name AND e.is_def = 1 AND (e.path < d.path OR (e.path = d.path AND e.line < d.line))) ORDER BY $REFS_OF_D DESC, d.name ASC LIMIT ?", root, limit) { r ->
        Quad(r.string(0), r.longOrNull(1) ?: 0, r.string(2), r.int(3))
    }
}

/** T370: `(name, path, is_def, count)` per group, imports and nameless rows left out. */
fun Store.symbolFileScan(root: String): List<Quad<String, String, Boolean, Long>> = conn {
    it.query("SELECT name, path, is_def, count(*) FROM symbols WHERE root = ? AND name <> '' AND kind <> 'import' GROUP BY name, path, is_def ORDER BY name ASC, path ASC, is_def ASC", root) { r ->
        Quad(r.string(0), r.string(1), r.int(2) != 0, r.long(3))
    }
}

fun Store.fileRankGet(root: String): String? = conn { it.stringOf("SELECT graph FROM file_rank WHERE root = ?", root) }
fun Store.fileRankPut(root: String, graph: String) = conn { it.execute("INSERT INTO file_rank (root, graph) VALUES (?, ?) ON CONFLICT (root) DO UPDATE SET graph = excluded.graph", root, graph); Unit }

/** T52.4: definitions with no same-name reference row, `(path, name, kind, line)`. */
fun Store.symbolDeadCandidates(root: String): List<Quad<String, String, String, Int>> = conn {
    it.query("SELECT d.path, d.name, d.kind, d.line FROM symbols d WHERE d.root = ? AND d.is_def = 1 AND d.name <> '' AND NOT EXISTS (SELECT 1 FROM symbols r " +
        "WHERE r.root = d.root AND r.name = d.name AND r.is_def = 0 AND r.kind <> 'import') ORDER BY d.path ASC, d.line ASC", root) { r -> Quad(r.string(0), r.string(1), r.string(2), r.int(3)) }
}

/** T329.5: which of `names` have a non-import reference row under `root`. */
fun Store.symbolReferencedNames(root: String, names: List<String>): Set<String> = conn { db ->
    db.chunkedIn("SELECT DISTINCT name FROM symbols WHERE root = ? AND name IN (#) AND $NOT_IMPORT_REF", listOf(root), names) { it.string(0) }.toHashSet()
}

/** Callers of `name` out to `depth` (1..4), each `(depth, path, scope)` at its first depth (T8.13); per-chain `seen` sets, like the old CTE. */
fun Store.symbolImpact(root: String, name: String, depth: UInt): List<Triple<UInt, String, String>> {
    class Chain(val tip: String, val seen: Set<String>)
    val maxDepth = depth.coerceIn(1u, 4u)
    val results = LinkedHashMap<Pair<String, String>, UInt>()
    var current = listOf(Chain(name, emptySet())); var level = 1u
    conn { db ->
        while (current.isNotEmpty() && level <= maxDepth) {
            val tips = current.map { it.tip }.distinct()
            val byTip = HashMap<String, MutableList<Pair<String, String>>>()
            groupByTip(db.chunkedIn("SELECT name, path, scope FROM symbols WHERE root = ? AND name IN (#) AND is_def = 0 AND name <> '' AND kind <> 'import'", listOf(root), tips) {
                it.string(0) to (it.string(1) to it.string(2)) }, byTip)
            groupByTip(db.chunkedIn("SELECT i.name, d.path, d.name FROM symbols i JOIN symbols d ON d.root = i.root AND d.path = i.path AND d.is_def = 1 AND d.name <> '' " +
                "WHERE i.root = ? AND i.name IN (#) AND i.kind = 'import' AND i.is_def = 0", listOf(root), tips) { it.string(0) to (it.string(1) to it.string(2)) }, byTip)
            val next = ArrayList<Chain>()
            for (c in current) for ((path, out) in byTip[c.tip] ?: continue) {
                if (out in c.seen) continue
                results.getOrPut(path to out) { level }
                if (out.isNotEmpty()) next += Chain(out, c.seen + out)
            }
            current = next; level++
        }
    }
    return results.map { (k, d) -> Triple(d, k.first, k.second) }
        .sortedWith { x, y -> x.first.compareTo(y.first).takeIf { it != 0 } ?: compareUtf8(x.second, y.second).takeIf { it != 0 } ?: compareUtf8(x.third, y.third) }
}

/** T68.1: distinct definition names starting with `prefix`, best `limit` by reference count. */
fun Store.symbolNamePrefix(root: String, prefix: String, limit: Long): List<String> {
    val like = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    val refs = "(SELECT count(*) FROM symbols r WHERE r.root = ? AND r.name = symbols.name AND r.is_def = 0 AND r.kind <> 'import')"
    return conn { it.query("SELECT name, $refs FROM symbols WHERE root = ? AND is_def = 1 AND name <> '' AND name LIKE ? ESCAPE '\\' GROUP BY name ORDER BY $refs DESC, name ASC LIMIT ?",
        root, root, like, root, limit) { r -> r.string(0) } }
}

/** T68.1: simple call chains `from → … → to` in the caller direction, shortest first. */
fun Store.symbolPaths(root: String, from: String, to: String, depth: UInt): List<String> {
    class Partial(val chain: String, val tip: String, val seen: Set<String>)
    val maxDepth = depth.coerceIn(1u, 4u)
    val results = ArrayList<Pair<UInt, String>>()
    if (from == to) results += 1u to from
    var current = listOf(Partial(from, from, setOf(from))); var level = 1u
    conn { db ->
        while (level < maxDepth) {
            val active = current.filter { it.tip != to }; if (active.isEmpty()) break
            val byTip = HashMap<String, MutableList<String>>()
            groupByTip(db.chunkedIn("SELECT name, scope FROM symbols WHERE root = ? AND name IN (#) AND is_def = 0 AND scope <> '' AND kind <> 'import'", listOf(root),
                active.map { it.tip }.distinct()) { it.string(0) to it.string(1) }, byTip)
            val next = ArrayList<Partial>()
            for (p in active) for (scope in byTip[p.tip] ?: continue) {
                if (scope in p.seen) continue
                val chain = "${p.chain} → $scope"
                if (scope == to) results += (level + 1u) to chain
                next += Partial(chain, scope, p.seen + scope)
            }
            current = next; level++
        }
    }
    return results.sortedWith { x, y -> x.first.compareTo(y.first).takeIf { it != 0 } ?: compareUtf8(x.second, y.second) }.map { it.second }
}

/** T68.6: import rows of `path` as `(name, line)`. */
fun Store.symbolImports(root: String, path: String): List<Pair<String, Int>> = conn {
    it.query("SELECT name, line FROM symbols WHERE root = ? AND path = ? AND kind = 'import' AND is_def = 0 ORDER BY line ASC", root, path) { r -> r.string(0) to r.int(1) }
}
/** T68.6: files that import `module` as `(path, line)`. */
fun Store.symbolImporters(root: String, module: String): List<Pair<String, Int>> = conn {
    it.query("SELECT path, line FROM symbols WHERE root = ? AND name = ? AND kind = 'import' AND is_def = 0 ORDER BY path ASC, line ASC", root, module) { r -> r.string(0) to r.int(1) }
}
/** T68.6: definitions in files that import `name`. */
fun Store.symbolImportFollow(root: String, name: String): List<Pair<String, String>> = conn {
    it.query("SELECT d.path, d.name FROM symbols i JOIN symbols d ON d.root = i.root AND d.path = i.path AND d.is_def = 1 AND d.name <> '' " +
        "WHERE i.root = ? AND i.name = ? AND i.kind = 'import' AND i.is_def = 0 ORDER BY d.path ASC, d.line ASC", root, name) { r -> r.string(0) to r.string(1) }
}

/** T368: `(reference file, definition file)` pairs where a reference file imports one of several same-named definitions. */
fun Store.symbolImportedDefs(root: String, name: String): List<Pair<String, String>> {
    val defs = symbolDefs(root, name).map { it.a }
    if (defs.size < 2) return emptyList()
    val imports = conn { db ->
        val refFiles = db.query("SELECT DISTINCT path FROM symbols WHERE root = ? AND name = ? AND $NOT_IMPORT_REF", root, name) { it.string(0) }
        db.chunkedIn("SELECT path, scope FROM symbols WHERE root = ? AND kind = 'import' AND is_def = 0 AND path IN (#)", listOf(root), refFiles) { it.string(0) to it.string(1) }
    }
    val keys = defs.map { it to fileKey(it) }
    val seen = HashSet<Pair<String, String>>(); val out = ArrayList<Pair<String, String>>()
    for ((refFile, spec) in imports) {
        val imp = importPathSegs(spec) ?: continue
        for ((def, key) in keys) if (keyPrefixesImport(key, imp) && seen.add(refFile to def)) out += refFile to def
    }
    return out.sortedWith { x, y -> compareUtf8(x.first, y.first).takeIf { it != 0 } ?: compareUtf8(x.second, y.second) }
}

// ---- IDF cache (T368) ----

/** Per-root document frequency written at the end of an index pass; `df` key-sorted like Rust's BTreeMap. */
@Serializable
private data class IdfCache(val n: Long, val df: Map<String, Long>)
private class IdfMemo(val key: String, val body: String, val cache: IdfCache)
private fun idfKey(root: String) = "graph-idf:$root"
private val lenientJson = Json { ignoreUnknownKeys = true }

/** `(df, n_files)` from the cache; `(0, 0)` before an index pass wrote it. */
fun Store.symbolNameFreq(root: String, name: String): Pair<Long, Long> = cachedNameFreq(root, name) ?: (0L to 0L)

/** T368: one `GROUP BY name` of referencing files, stored in kv so ranking does not rescan `symbols`. */
fun Store.rebuildSymbolIdf(root: String) {
    val (rows, n) = conn { db ->
        val rows = db.query("SELECT name, count(DISTINCT path) FROM symbols WHERE root = ? AND is_def = 0 AND kind <> 'import' AND name <> '' GROUP BY name", root) { it.string(0) to it.long(1) }
        val n = db.longOf("SELECT count(DISTINCT path) FROM symbols WHERE root = ?", root)!!
        db.exec("PRAGMA analysis_limit = 1000"); db.exec("ANALYZE symbols")
        rows to n
    }
    val df = JsonObject(rows.sortedWith { x, y -> compareUtf8(x.first, y.first) }.associate { it.first to JsonPrimitive(it.second) })
    kvSet(idfKey(root), SerdeJson.encode(JsonObject(mapOf("n" to JsonPrimitive(n), "df" to df))))
}

private fun Store.cachedNameFreq(root: String, name: String): Pair<Long, Long>? {
    val key = idfKey(root); val body = kvGet(key) ?: return null
    val memo = (idfMemo as? IdfMemo)?.takeIf { it.key == key && it.body == body }
        ?: IdfMemo(key, body, runCatching { lenientJson.decodeFromString(IdfCache.serializer(), body) }.getOrElse { IdfCache(0, emptyMap()) }).also { idfMemo = it }
    return (memo.cache.df[name] ?: 0L) to memo.cache.n
}

// ---- import specifier matching ----

internal fun importSegs(spec: String): List<String> =
    spec.split('/', '\\', ':', '.').filter { it.isNotEmpty() && it !in setOf("crate", "self", "super", "package") }.map { it.replace('-', '_') }

internal fun fileKey(file: String): List<String> {
    val parts = file.split('/', '\\').filter { it.isNotEmpty() }.toMutableList()
    if (parts.isNotEmpty()) { val last = parts.last(); val dot = last.lastIndexOf('.'); if (dot >= 0) parts[parts.size - 1] = last.substring(0, dot) }
    if (parts.lastOrNull() in setOf("mod", "lib", "index")) parts.removeAt(parts.size - 1)
    return parts.filter { it != "src" && it != "crates" }.map { it.replace('-', '_') }
}

/** Null for a bare identifier or an empty path. */
private fun importPathSegs(spec: String): List<String>? =
    if (spec.none { it == ':' || it == '/' || it == '.' || it == '\\' }) null else importSegs(spec).takeIf { it.isNotEmpty() }

private fun keyPrefixesImport(file: List<String>, imp: List<String>) = file.isNotEmpty() && file.size <= imp.size && imp.subList(0, file.size) == file

/** Test hook: a bare identifier is not a path; the file's segments must prefix the import. */
internal fun importMatchesFile(spec: String, file: String) = importPathSegs(spec)?.let { keyPrefixesImport(fileKey(file), it) } ?: false
