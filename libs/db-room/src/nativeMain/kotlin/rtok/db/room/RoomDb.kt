// Adapter: androidx.sqlite's BundledSQLiteDriver (the driver layer Room KMP runs on, with its own
// bundled SQLite) behind the driver-neutral rtok.db interfaces. The schema stays raw SQL (see
// docs/store.md: Room's generated schema cannot match the Rust one byte for byte).
package rtok.db.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_URI
import rtok.db.Db
import rtok.db.DbDriver
import rtok.db.DbException
import rtok.db.Row

/** The driver to install as `Db.driver`. */
object RoomDbDriver : DbDriver {
    private val driver = BundledSQLiteDriver()
    private const val FLAGS = SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_URI

    override fun open(path: String): Db = mapped { RoomDb(driver.open(path, FLAGS)) }
    override fun version(): String = open(":memory:").use { it.stringOf("SELECT sqlite_version()") ?: "?" }
}

/** `Error code: 5, message: database is locked` → DbException(5, "database is locked"). */
private val CODE = Regex("""^Error code: (\d+), message: (.*)$""", RegexOption.DOT_MATCHES_ALL)

internal inline fun <T> mapped(block: () -> T): T = try { block() } catch (e: SQLiteException) {
    val m = CODE.find(e.message ?: "")
    throw DbException(m?.groupValues?.get(1)?.toInt() ?: 1, m?.groupValues?.get(2) ?: e.message ?: "sqlite error")
}

private class StatementRow(private val st: SQLiteStatement) : Row {
    override fun isNull(i: Int) = st.isNull(i)
    override fun long(i: Int) = st.getLong(i)
    override fun int(i: Int) = st.getLong(i).toInt()
    override fun double(i: Int) = st.getDouble(i)
    override fun string(i: Int) = if (st.isNull(i)) "" else st.getText(i)
    override fun blob(i: Int): ByteArray = if (st.isNull(i)) ByteArray(0) else st.getBlob(i)
    override val columns: Int get() = st.getColumnCount()
    override fun name(i: Int) = st.getColumnName(i)
}

private class RoomDb(private val conn: SQLiteConnection) : Db {
    private var closed = false

    override fun exec(sql: String) = splitSql(sql).forEach { stmt -> run(stmt, emptyArray()) { st -> while (st.step()) Unit } }

    override fun execute(sql: String, vararg args: Any?): Int { run(sql, args) { st -> while (st.step()) Unit }; return changes() }

    override fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> = run(sql, args) { st ->
        val row = StatementRow(st); val out = ArrayList<T>(); while (st.step()) out += map(row); out
    }

    override fun lastInsertRowid(): Long = scalar("SELECT last_insert_rowid()")
    override fun changes(): Int = scalar("SELECT changes()").toInt()
    override val inTransaction: Boolean get() = mapped { conn.inTransaction() }
    override fun close() { if (!closed) { closed = true; conn.close() } }

    private fun scalar(sql: String): Long = run(sql, emptyArray()) { st -> st.step(); st.getLong(0) }

    private inline fun <T> run(sql: String, args: Array<out Any?>, use: (SQLiteStatement) -> T): T = mapped {
        conn.prepare(sql).use { st -> bind(st, args); use(st) }
    }

    private fun bind(st: SQLiteStatement, args: Array<out Any?>) = args.forEachIndexed { i0, a ->
        val i = i0 + 1
        when (a) {
            null -> st.bindNull(i)
            is Int -> st.bindLong(i, a.toLong()); is Long -> st.bindLong(i, a)
            is UInt -> st.bindLong(i, a.toLong()); is ULong -> st.bindLong(i, a.toLong())
            is Boolean -> st.bindLong(i, if (a) 1 else 0)
            is Double -> st.bindDouble(i, a); is Float -> st.bindDouble(i, a.toDouble())
            is String -> st.bindText(i, a)
            is ByteArray -> st.bindBlob(i, a)
            else -> throw IllegalArgumentException("cannot bind ${a::class}")
        }
    }
}
