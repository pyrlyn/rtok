package rtok.db.room

import kotlin.test.*
import rtok.db.Db
import rtok.db.DbException

class RoomDbTest {
    private fun open() = RoomDbDriver.open(":memory:")

    @Test fun roundTripsTypesAndFts5() {
        open().use { db ->
            db.exec("CREATE TABLE t (a INTEGER, b TEXT, c BLOB, d REAL); CREATE VIRTUAL TABLE f USING fts5(x)")
            assertEquals(1, db.execute("INSERT INTO t VALUES (?, ?, ?, ?)", 5L, "héllo", byteArrayOf(0, 1, 2), 1.5))
            db.execute("INSERT INTO t VALUES (?, ?, ?, ?)", null, "", ByteArray(0), null)
            val rows = db.query("SELECT a, b, c, d FROM t ORDER BY rowid") { listOf(it.longOrNull(0), it.string(1), it.blob(2).toList(), it.doubleOrNull(3)) }
            assertEquals(listOf(listOf(5L, "héllo", listOf<Byte>(0, 1, 2), 1.5), listOf(null, "", emptyList<Byte>(), null)), rows)
            db.execute("INSERT INTO f(x) VALUES (?)", "token reduction")
            assertEquals(1L, db.longOf("SELECT count(*) FROM f WHERE f MATCH ?", "reduction"))
            assertEquals(2L, db.lastInsertRowid().let { db.longOf("SELECT count(*) FROM t") })
        }
    }

    @Test fun transactionRollsBackAndNests() {
        open().use { db ->
            db.exec("CREATE TABLE t (a INTEGER)")
            assertFails { db.transaction { db.execute("INSERT INTO t VALUES (1)"); error("boom") } }
            db.transaction { db.execute("INSERT INTO t VALUES (2)"); runCatching { db.transaction { db.execute("INSERT INTO t VALUES (3)"); error("inner") } } }
            assertEquals(listOf(2L), db.query("SELECT a FROM t") { it.long(0) })
            assertFalse(db.inTransaction)
        }
    }

    @Test fun errorsCarryTheSqliteCode() {
        open().use { db ->
            val e = assertFailsWith<DbException> { db.exec("SELECT * FROM missing") }
            assertEquals(1, e.code); assertTrue("no such table" in e.message!!, e.message)
        }
    }

    @Test fun scriptsSplitOnStatementsButNotInsideTriggersQuotesOrComments() {
        val sql = """
            -- a; comment
            CREATE TABLE a (x TEXT DEFAULT 'semi;colon'); /* block; */
            CREATE TRIGGER tr AFTER INSERT ON a BEGIN
              INSERT INTO b(x) VALUES (new.x); UPDATE b SET y = 1;
            END;
            CREATE TABLE "b;c" (y INTEGER)
        """.trimIndent()
        val parts = splitSql(sql)
        assertEquals(3, parts.size, parts.joinToString("\n---\n"))
        assertTrue("CREATE TRIGGER" in parts[1] && parts[1].endsWith("END;"), parts[1])
        open().use { db -> db.exec("CREATE TABLE b (x TEXT, y INTEGER); " + sql); db.execute("INSERT INTO a VALUES ('v')"); assertEquals(1L, db.longOf("SELECT y FROM b")) }
    }

    @Test fun driverInstallsOnce() {
        Db.installIfAbsent(RoomDbDriver); Db.installIfAbsent(object : rtok.db.DbDriver { override fun open(path: String) = error("x"); override fun version() = "x" })
        assertSame(RoomDbDriver, Db.driver)
        assertTrue(RoomDbDriver.version().startsWith("3."))
    }
}
