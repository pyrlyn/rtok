// Port of crates/rtok-store/src/symbol_graph.rs (T329.14): the scans the graph drill-down builds
// from — one scan of a root (or a batch of names) each, never a query per symbol.
package rtok.store

import rtok.db.*
import rtok.sys.compareUtf8

private const val DEF_COLUMNS = "path, name, kind, line, end_line, signature, content_hash, start_byte, end_byte"

/** One definition: where it is and the line that declares it. `contentHash` is the sha256 of its bytes. */
data class DefRow(
    val path: String,
    val name: String,
    val kind: String,
    val line: Int,
    val endLine: Int,
    val signature: String,
    val contentHash: String,
    val startByte: Long,
    val endByte: Long,
)

private fun defRow(r: Row) = DefRow(r.string(0), r.string(1), r.string(2), r.int(3), r.int(4), r.string(5), r.string(6), r.long(7), r.long(8))

/** References grouped by the definition holding them (`scope` empty at file level). */
data class RefGroup(
    val path: String,
    val scope: String,
    val name: String,
    val kind: String,
    val count: Long,
)

/** Every named definition of `root`, by path then line. */
fun Store.symbolDefScan(root: String): List<DefRow> =
    conn {
        it.query("SELECT $DEF_COLUMNS FROM symbols WHERE root = ? AND is_def = 1 AND name <> '' ORDER BY path ASC, line ASC", root, map = ::defRow)
    }

/** Non-import references grouped by `(path, scope, name, kind)`. */
fun Store.symbolRefScan(root: String): List<RefGroup> =
    conn {
        it.query(
            "SELECT path, scope, name, kind, count(*) FROM symbols WHERE root = ? AND is_def = 0 AND kind <> 'import' AND name <> '' " +
                "GROUP BY path, scope, name, kind ORDER BY path ASC, scope ASC, name ASC",
            root,
        ) { r -> RefGroup(r.string(0), r.string(1), r.string(2), r.string(3), r.long(4)) }
    }

/** `(importing file, imported file)`: a specifier names the file whose segments are its longest prefix. */
fun Store.symbolImportEdges(root: String): List<Pair<String, String>> {
    val (files, imports) =
        conn { db ->
            db.query("SELECT DISTINCT path FROM symbols WHERE root = ? AND is_def = 1", root) { it.string(0) } to
                db.query("SELECT DISTINCT path, scope FROM symbols WHERE root = ? AND kind = 'import' AND is_def = 0 AND scope <> ''", root) { it.string(0) to it.string(1) }
        }
    val byKey = HashMap<List<String>, MutableList<String>>()
    for (f in files) {
        val key = fileKey(f)
        if (key.isNotEmpty()) byKey.getOrPut(key) { ArrayList() } += f
    }
    val out = HashSet<Pair<String, String>>()
    for ((from, spec) in imports) {
        if (!isImportPath(spec)) continue
        val segs = importSegs(spec)
        val hit = (segs.size downTo 1).firstNotNullOfOrNull { k -> byKey[segs.subList(0, k)]?.takeIf { it.isNotEmpty() } } ?: continue
        for (to in hit) if (to != from) out += from to to
    }
    return out.sortedWith { x, y -> compareUtf8(x.first, y.first).takeIf { it != 0 } ?: compareUtf8(x.second, y.second) }
}

/** Definitions of any of `names`, for resolving a call into a linked project. */
fun Store.symbolDefsNamed(
    root: String,
    names: List<String>,
): List<DefRow> =
    conn { db ->
        db.chunkedIn("SELECT $DEF_COLUMNS FROM symbols WHERE root = ? AND is_def = 1 AND name IN (#)", listOf(root), names, ::defRow)
    }
