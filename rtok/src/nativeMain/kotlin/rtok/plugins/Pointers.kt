// Pointer prefixes shared by the plugins that write `archive_decisions` rows (toon, json_tree, archive):
// each leaves the others' rows alone, so one saving is measured once.
package rtok.plugins

const val TOON_PREFIX = "[toon "

/** Rust `expand::JSON_TREE_PREFIX`. */
const val JSON_TREE_PREFIX = "[json-tree "

/** A decision `archive` did not write. */
fun isForeignToArchive(pointer: String) = pointer.startsWith(TOON_PREFIX) || pointer.startsWith(JSON_TREE_PREFIX)
