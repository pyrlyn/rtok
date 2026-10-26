// Port of the kv half of crates/rtok-store/src/note_store.rs.
package rtok.store

fun Store.kvGet(key: String): String? = conn { it.stringOf("SELECT value FROM kv WHERE key = ?", key) }
fun Store.kvSet(key: String, value: String) = conn { it.execute("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = ?", key, value, value); Unit }
/** Every `(key, value)` whose key starts with `prefix` literally, ordered by key. */
fun Store.kvPrefix(prefix: String): List<Pair<String, String>> = conn { db ->
    val pattern = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    db.query("SELECT key, value FROM kv WHERE key LIKE ? ESCAPE '\\' ORDER BY key", pattern) { it.string(0) to it.string(1) }.filter { it.first.startsWith(prefix) }
}
fun Store.kvDelete(key: String) = conn { it.execute("DELETE FROM kv WHERE key = ?", key); Unit }
