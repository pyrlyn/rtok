// Shared SQL-building helpers (the Kotlin counterpart of crates/rtok-store/src/sql_ext.rs).
package rtok.store

import rtok.db.*

/** `?, ?, ?` — the placeholders of an `IN (…)` list or a VALUES row of `n` items. */
internal fun inList(n: Int) = List(n) { "?" }.joinToString(", ")

/** SQLite's default bind limit is 999; a name batch stays well under it. */
internal const val NAME_CHUNK = 500

/** One query per chunk of `names`, bound as the trailing `IN (#)` list after `pre`. */
internal fun <T> Db.chunkedIn(
    sql: String,
    pre: List<Any?>,
    names: List<String>,
    map: (Row) -> T,
): List<T> = names.chunked(NAME_CHUNK).flatMap { c -> query(sql.replace("#", inList(c.size)), *(pre + c).toTypedArray(), map = map) }
