// Driver-neutral SQL connection: the Kotlin stand-in for diesel's SqliteConnection. Statements are
// plain SQL with `?` parameters. Code that stores data sees only `Db` / `Row`; a SQLite binding is
// an adapter module (`libs/db-room`) that the application installs as `Db.driver`.
package rtok.db

import kotlin.concurrent.AtomicInt

/** A statement failed; `code` is the driver's (extended) result code. */
class DbException(
    val code: Int,
    message: String,
) : Exception(message)

/** The current row of a running query. */
interface Row {
    fun isNull(i: Int): Boolean

    fun long(i: Int): Long

    fun int(i: Int): Int

    fun double(i: Int): Double

    fun string(i: Int): String

    fun blob(i: Int): ByteArray

    fun longOrNull(i: Int) = if (isNull(i)) null else long(i)

    fun intOrNull(i: Int) = if (isNull(i)) null else int(i)

    fun doubleOrNull(i: Int) = if (isNull(i)) null else double(i)

    fun stringOrNull(i: Int) = if (isNull(i)) null else string(i)

    fun blobOrNull(i: Int) = if (isNull(i)) null else blob(i)

    fun bool(i: Int) = long(i) != 0L

    val columns: Int

    fun name(i: Int): String
}

/** Opens connections; one implementation per driver. */
interface DbDriver {
    fun open(path: String): Db

    fun version(): String
}

private val savepoints = AtomicInt(0)

interface Db : AutoCloseable {
    /** One or more statements without parameters. */
    fun exec(sql: String)

    /** One statement; the number of changed rows. Binds Int, Long, UInt, ULong, Boolean, Double, Float, String, ByteArray, null. */
    fun execute(
        sql: String,
        vararg args: Any?,
    ): Int

    fun <T> query(
        sql: String,
        vararg args: Any?,
        map: (Row) -> T,
    ): List<T>

    fun lastInsertRowid(): Long

    fun changes(): Int

    val inTransaction: Boolean

    fun <T> queryOne(
        sql: String,
        vararg args: Any?,
        map: (Row) -> T,
    ): T? = query(sql, *args, map = map).firstOrNull()

    fun longOf(
        sql: String,
        vararg args: Any?,
    ): Long? = queryOne(sql, *args) { it.longOrNull(0) }

    fun stringOf(
        sql: String,
        vararg args: Any?,
    ): String? = queryOne(sql, *args) { it.stringOrNull(0) }

    /** `BEGIN <mode>` … COMMIT, ROLLBACK on any throw; nested calls become savepoints (like diesel). */
    fun <T> transaction(
        mode: String = "DEFERRED",
        body: () -> T,
    ): T {
        if (inTransaction) {
            val sp = "sp${savepoints.incrementAndGet()}"
            exec("SAVEPOINT $sp")
            return try {
                body().also { exec("RELEASE $sp") }
            } catch (e: Throwable) {
                runCatching { exec("ROLLBACK TO $sp; RELEASE $sp") }
                throw e
            }
        }
        exec("BEGIN $mode")
        return try {
            body().also { exec("COMMIT") }
        } catch (e: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw e
        }
    }

    companion object {
        private var installed: DbDriver? = null

        /** The driver every `Db.open` uses; the application (or a test) installs it once at start. */
        var driver: DbDriver
            get() = installed ?: error("no SQLite driver installed: set Db.driver (see libs/db-room)")
            set(v) {
                installed = v
            }

        /** Install [d] unless a driver is already set. */
        fun installIfAbsent(d: DbDriver) {
            if (installed == null) installed = d
        }

        fun open(path: String): Db = driver.open(path)

        fun version(): String = driver.version()
    }
}

/** A statement gave up on another process's lock after its `busy_timeout`. */
fun isLocked(e: Throwable?): Boolean {
    var cur = e
    while (cur != null) {
        if ((cur.message ?: "").contains("database is locked")) return true
        cur = cur.cause
    }
    return false
}
