// Shared SQL-building helpers (the Kotlin counterpart of crates/rtok-store/src/sql_ext.rs).
package rtok.store

/** `?, ?, ?` — the placeholders of an `IN (…)` list or a VALUES row of `n` items. */
internal fun inList(n: Int) = List(n) { "?" }.joinToString(", ")
