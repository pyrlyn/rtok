package rtok.store

import rtok.sys.*
import kotlin.test.*

class HandleTest {
    @Test fun freshStoreIsOwnerOnly() {
        val base = tmpDir("private-store"); val db = "$base/home/rtok.db"
        Store.open(db).close()
        val modes = fileMode("$base/home") to fileMode(db); removeDirAll(base)
        assertEquals(0x1c0 to 0x180, modes)
    }

    @Test fun existingReadableDbIsTightened() {
        val base = tmpDir("private-existing"); val db = "$base/rtok.db"
        Store.open(db).close(); platform.posix.chmod(db, 0x1a4u); Store.open(db).close()
        val m = fileMode(db); removeDirAll(base); assertEquals(0x180, m)
    }

    @Test fun archiveFilesAreOwnerOnly() {
        val base = tmpDir("private-archive"); val (p, created) = writeArchiveFile("$base/archive", "abc", "x".encodeToByteArray())
        assertTrue(created); assertEquals(0x180, fileMode(p)); assertEquals(0x1c0, fileMode("$base/archive"))
        assertFalse(writeArchiveFile("$base/archive", "abc", "x".encodeToByteArray()).second); removeDirAll(base)
    }

    @Test fun migrationsUseDieselVersionsAndAreIdempotent() {
        val base = tmpDir("migrate"); val db = "$base/rtok.db"
        Store.open(db).use { s ->
            val versions = s.conn { it.query("SELECT version FROM __diesel_schema_migrations ORDER BY version") { r -> r.string(0) } }
            assertEquals(EMBEDDED_MIGRATIONS.size, versions.size); assertEquals("0001", versions.first()); assertTrue(versions.all { Regex("\\d{4}").matches(it) })
            assertEquals(0, s.migrate())
        }
        removeDirAll(base)
    }

    @Test fun legacyBookkeepingIsBridged() {
        val s = Store.openInMemory()
        s.conn { Migrations.revertToLegacyBookkeeping(it) }
        assertEquals(0, s.migrate(), "every legacy name maps to an applied version")
        assertEquals(EMBEDDED_MIGRATIONS.size.toLong(), s.conn { it.longOf("SELECT count(*) FROM __diesel_schema_migrations") })
    }

    @Test fun anOldStoreMigratesForward() {
        val db = Db.open(":memory:"); db.exec("PRAGMA foreign_keys = ON")
        Migrations.runBefore(db, "0015")
        assertEquals(14L, db.longOf("SELECT count(*) FROM __diesel_schema_migrations"))
        assertTrue(Migrations.hasPending(db)); assertEquals(EMBEDDED_MIGRATIONS.size - 14, Migrations.runPending(db)); assertFalse(Migrations.hasPending(db))
        db.close()
    }

    @Test fun kvPrefixIsLiteralAndOrdered() {
        val dir = tmpDir("t419-kv-prefix")
        Store.open("$dir/rtok.db").use { s ->
            listOf("plugin:a_b:2" to "two", "plugin:a_b:1" to "one", "plugin:axb:1" to "other", "plugin:a%b:1" to "pct", "plugin:A_B:1" to "upper", "other" to "x").forEach { (k, v) -> s.kvSet(k, v) }
            assertEquals(listOf("plugin:a_b:1" to "one", "plugin:a_b:2" to "two"), s.kvPrefix("plugin:a_b:"))
            assertEquals(1, s.kvPrefix("plugin:a%b:").size); assertTrue(s.kvPrefix("nothing:").isEmpty())
            s.kvSet("other", "y"); assertEquals("y", s.kvGet("other")); s.kvDelete("other"); assertNull(s.kvGet("other"))
        }
        removeDirAll(dir)
    }

    private fun preT352(db: String) { Db.open(db).use { it.exec("CREATE TABLE t (x INTEGER)") } }

    @Test fun anExistingStoreConvertsOnce() {
        val dir = tmpDir("t352-convert"); val db = "$dir/old.db"; preT352(db)
        Store.open(db).use { s -> assertEquals(0, s.autoVacuumMode()); assertTrue(s.convertToIncrementalVacuum()); assertEquals(2, s.autoVacuumMode()); assertFalse(s.convertToIncrementalVacuum()) }
        removeDirAll(dir)
    }

    @Test fun housekeepingConvertsAPreT352Store() {
        val dir = tmpDir("t352-housekeeping"); val db = "$dir/rtok.db"; preT352(db)
        assertEquals(0, Store.open(db).use { it.autoVacuumMode() })
        assertEquals(emptyList(), Store.housekeeping(RetentionJob(db, 30u, 3u)))
        assertEquals(2, Store.open(db).use { it.autoVacuumMode() })
        removeDirAll(dir)
    }

    @Test fun retentionScoreAndRankBlend() {
        assertEquals(1.0, retentionScore(true, 0, 1000.0, null))
        assertTrue(retentionScore(false, 0, 0.0, null) - 0.5 < 1e-9)
        assertTrue(rankBlend(0, 1.0) > rankBlend(1, 1.0)); assertEquals("\"a\" \"b\"\"c\"", ftsPhraseQuery(" a  b\"c ")); assertNull(ftsPhraseQuery("  "))
    }
}
