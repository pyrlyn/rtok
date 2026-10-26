// Port of crates/rtok-store/src/migrations.rs: embedded `migrations/<version>/up.sql` recorded in
// diesel's `__diesel_schema_migrations` (version = numeric prefix, e.g. "0001"), plus the one-time
// bridge from the pre-T163.4 `schema_migrations` (`NNNN.sql`).
package rtok.store

internal object Migrations {
    private const val CREATE = "CREATE TABLE IF NOT EXISTS __diesel_schema_migrations (\n" +
        "       version VARCHAR(50) PRIMARY KEY NOT NULL,\n" +
        "       run_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP\n)"

    /** Diesel's version of a migration directory: everything before the first `_`. */
    fun versionOf(dir: String) = dir.substringBefore('_')

    fun versions(): List<String> = EMBEDDED_MIGRATIONS.map { versionOf(it.first) }.sorted()

    private fun applied(db: Db): Set<String> { db.exec(CREATE); return db.query("SELECT version FROM __diesel_schema_migrations") { it.string(0) }.toSet() }

    fun bridgeLegacy(db: Db) {
        db.exec(CREATE)
        val names = try { db.query("SELECT name FROM schema_migrations ORDER BY name") { it.string(0) } }
            catch (e: SqliteException) { if ((e.message ?: "").contains("no such table")) return else throw e }
        for (name in names) {
            val prefix = name.substringBefore('.')
            val version = EMBEDDED_MIGRATIONS.map { versionOf(it.first) }.firstOrNull { it.substringBefore('_') == prefix }
                ?: throw StoreException("schema_migrations name $name has no Diesel version")
            db.execute("INSERT OR IGNORE INTO __diesel_schema_migrations (version) VALUES (?)", version)
        }
    }

    fun hasPending(db: Db): Boolean { val a = applied(db); return EMBEDDED_MIGRATIONS.any { versionOf(it.first) !in a } }

    fun runPending(db: Db): Int {
        val a = applied(db); var n = 0
        for ((dir, sql) in EMBEDDED_MIGRATIONS.sortedBy { it.first }) {
            val v = versionOf(dir); if (v in a) continue
            runOne(db, v, sql, dir); n++
        }
        return n
    }

    private fun runOne(db: Db, version: String, sql: String, dir: String) = db.transaction {
        try { db.exec(sql) } catch (e: SqliteException) { throw StoreException("Failed to run $dir with: ${e.message}", e) }
        db.execute("INSERT INTO __diesel_schema_migrations (version) VALUES (?)", version)
    }

    /** Apply every migration whose numeric prefix is less than `tag` (`"0015"`). Tests only. */
    fun runBefore(db: Db, tag: String) {
        db.exec(CREATE)
        for ((dir, sql) in EMBEDDED_MIGRATIONS.sortedBy { it.first }) { val v = versionOf(dir); if (v >= tag) break; runOne(db, v, sql, dir) }
    }

    /** Drop Diesel's rows and write pre-T163.4 `NNNN.sql` names. Tests only. */
    fun revertToLegacyBookkeeping(db: Db) {
        db.exec("DELETE FROM __diesel_schema_migrations")
        db.exec("CREATE TABLE IF NOT EXISTS schema_migrations (\n                name TEXT PRIMARY KEY,\n                applied_at INTEGER NOT NULL DEFAULT (unixepoch()))")
        for (v in versions()) db.execute("INSERT INTO schema_migrations (name) VALUES (?)", "${v.substringBefore('_')}.sql")
    }
}
