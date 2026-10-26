// Adapter: the system libsqlite3 (cinterop) behind the driver-neutral `Db` / `Row` interfaces.
// Swapping SQLite drivers means writing another `DbDriver`; nothing above this package changes.
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package rtok.store.driver

import rtok.store.Db
import rtok.store.DbDriver
import rtok.store.DbException
import rtok.store.Row

import kotlinx.cinterop.*
import rtok.store.sqlite.*
import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt

internal class Sqlite3Row(private val st: CPointer<sqlite3_stmt>) : Row {
    override fun isNull(i: Int) = sqlite3_column_type(st, i) == SQLITE_NULL
    override fun long(i: Int): Long = sqlite3_column_int64(st, i)
    override fun int(i: Int): Int = sqlite3_column_int64(st, i).toInt()
    override fun double(i: Int): Double = sqlite3_column_double(st, i)
    override fun string(i: Int): String = sqlite3_column_text(st, i)?.reinterpret<ByteVar>()?.let { p ->
        val n = sqlite3_column_bytes(st, i); p.readBytes(n).decodeToString() } ?: ""
    override fun blob(i: Int): ByteArray { val p = sqlite3_column_blob(st, i) ?: return ByteArray(0); return p.reinterpret<ByteVar>().readBytes(sqlite3_column_bytes(st, i)) }
    override fun longOrNull(i: Int) = if (isNull(i)) null else long(i)
    override fun intOrNull(i: Int) = if (isNull(i)) null else int(i)
    override fun doubleOrNull(i: Int) = if (isNull(i)) null else double(i)
    override fun stringOrNull(i: Int) = if (isNull(i)) null else string(i)
    override fun blobOrNull(i: Int) = if (isNull(i)) null else blob(i)
    override fun bool(i: Int) = long(i) != 0L
    override val columns: Int get() = sqlite3_column_count(st)
    override fun name(i: Int): String = sqlite3_column_name(st, i)?.toKString() ?: ""
}

internal class Sqlite3Db(private val db: CPointer<sqlite3>) : Db {
    private var closed = false

    companion object Driver : DbDriver {
        override fun open(path: String): Db = memScoped {
            val pp = alloc<CPointerVar<sqlite3>>()
            val rc = sqlite3_open_v2(path, pp.ptr, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_URI, null)
            val h = pp.value
            if (rc != SQLITE_OK || h == null) {
                val msg = h?.let { sqlite3_errmsg(it)?.toKString() } ?: "unable to open database file"
                h?.let { sqlite3_close_v2(it) }
                throw DbException(rc, msg)
            }
            sqlite3_extended_result_codes(h, 1)
            Sqlite3Db(h)
        }
        override fun version(): String = sqlite3_libversion()?.toKString() ?: "?"
    }

    private fun err(rc: Int): Nothing = throw DbException(rc, sqlite3_errmsg(db)?.toKString() ?: "sqlite error $rc")

    /** Run one or more statements with no parameters (`sqlite3_exec`). */
    override fun exec(sql: String) = memScoped {
        val errp = alloc<CPointerVar<ByteVar>>()
        val rc = sqlite3_exec(db, sql, null, null, errp.ptr)
        if (rc != SQLITE_OK) { val m = errp.value?.toKString() ?: "sqlite error $rc"; sqlite3_free(errp.value); throw DbException(rc, m) }
    }

    private inline fun <T> prepared(sql: String, args: Array<out Any?>, use: (CPointer<sqlite3_stmt>) -> T): T = memScoped {
        val sp = alloc<CPointerVar<sqlite3_stmt>>()
        val rc = sqlite3_prepare_v2(db, sql, -1, sp.ptr, null)
        if (rc != SQLITE_OK) err(rc)
        val st = sp.value ?: throw DbException(SQLITE_MISUSE, "empty statement: $sql")
        try { bind(st, args); use(st) } finally { sqlite3_finalize(st) }
    }

    private fun bind(st: CPointer<sqlite3_stmt>, args: Array<out Any?>) {
        args.forEachIndexed { i0, a ->
            val i = i0 + 1
            val rc = when (a) {
                null -> sqlite3_bind_null(st, i)
                is Int -> sqlite3_bind_int64(st, i, a.toLong())
                is Long -> sqlite3_bind_int64(st, i, a)
                is UInt -> sqlite3_bind_int64(st, i, a.toLong())
                is ULong -> sqlite3_bind_int64(st, i, a.toLong())
                is Boolean -> sqlite3_bind_int64(st, i, if (a) 1 else 0)
                is Double -> sqlite3_bind_double(st, i, a)
                is Float -> sqlite3_bind_double(st, i, a.toDouble())
                is String -> { val b = a.encodeToByteArray(); if (b.isEmpty()) sqlite3_bind_text(st, i, "", 0, TRANSIENT) else sqlite3_bind_text(st, i, a, b.size, TRANSIENT) }
                is ByteArray -> if (a.isEmpty()) sqlite3_bind_zeroblob(st, i, 0) else a.usePinned { p -> sqlite3_bind_blob(st, i, p.addressOf(0), a.size, TRANSIENT) }
                else -> throw IllegalArgumentException("cannot bind ${a::class}")
            }
            if (rc != SQLITE_OK) err(rc)
        }
    }

    /** Execute one statement; returns `sqlite3_changes`. */
    override fun execute(sql: String, vararg args: Any?): Int = prepared(sql, args) { st ->
        while (true) { val rc = sqlite3_step(st); if (rc == SQLITE_DONE) break; if (rc != SQLITE_ROW) err(rc) }
        sqlite3_changes(db)
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> = prepared(sql, args) { st ->
        val out = ArrayList<T>(); val row = Sqlite3Row(st)
        while (true) { val rc = sqlite3_step(st); if (rc == SQLITE_DONE) break; if (rc != SQLITE_ROW) err(rc); out += map(row) }
        out
    }


    override fun lastInsertRowid(): Long = sqlite3_last_insert_rowid(db)
    override fun changes(): Int = sqlite3_changes(db)
    override val inTransaction: Boolean get() = sqlite3_get_autocommit(db) == 0


    override fun close() { if (!closed) { closed = true; sqlite3_close_v2(db) } }
}

/** `SQLITE_TRANSIENT`: SQLite copies the bound bytes before the call returns. */
private val TRANSIENT: sqlite3_destructor_type = (-1L).toCPointer<CFunction<(COpaquePointer?) -> Unit>>()!!
