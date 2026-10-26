package rtok.store
import kotlin.test.*
class DbTest {
    @Test fun roundTripsTypesAndFts5() {
        Db.open(":memory:").use { db ->
            db.exec("CREATE TABLE t (a INTEGER, b TEXT, c BLOB, d REAL); CREATE VIRTUAL TABLE f USING fts5(x)")
            assertEquals(1, db.execute("INSERT INTO t VALUES (?, ?, ?, ?)", 5L, "héllo", byteArrayOf(0, 1, 2), 1.5))
            db.execute("INSERT INTO t VALUES (?, ?, ?, ?)", null, "", ByteArray(0), null)
            val rows = db.query("SELECT a, b, c, d FROM t ORDER BY rowid") { listOf(it.longOrNull(0), it.string(1), it.blob(2).toList(), it.doubleOrNull(3)) }
            assertEquals(listOf(listOf(5L, "héllo", listOf<Byte>(0, 1, 2), 1.5), listOf(null, "", emptyList<Byte>(), null)), rows)
            db.execute("INSERT INTO f(x) VALUES (?)", "token reduction")
            assertEquals(1L, db.longOf("SELECT count(*) FROM f WHERE f MATCH ?", "reduction"))
        }
    }
    @Test fun transactionRollsBackAndNests() {
        Db.open(":memory:").use { db ->
            db.exec("CREATE TABLE t (a INTEGER)")
            assertFails { db.transaction { db.execute("INSERT INTO t VALUES (1)"); error("boom") } }
            db.transaction { db.execute("INSERT INTO t VALUES (2)"); runCatching { db.transaction { db.execute("INSERT INTO t VALUES (3)"); error("inner") } } }
            assertEquals(listOf(2L), db.query("SELECT a FROM t") { it.long(0) })
        }
    }
}
