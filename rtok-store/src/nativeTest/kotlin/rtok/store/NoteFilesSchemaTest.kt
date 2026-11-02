package rtok.store

import db.Db
import db.sqldelight.KitSqlDriver
import rtok.store.queries.NoteFilesDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NOT_NULL_COLUMN = 3
private const val DEFAULT_COLUMN = 4
private const val PRIMARY_KEY_COLUMN = 5

class NoteFilesSchemaTest {
    @Test fun compilationSchemaMatchesMigratedTables() {
        Db.open(":memory:").use { declared ->
            NoteFilesDatabase.Schema.create(KitSqlDriver(declared))
            Store.openInMemory().use { store ->
                store.conn { actual ->
                    for (table in listOf("notes", "note_files", "read_cache")) {
                        assertEquals(columns(actual, table), columns(declared, table), table)
                    }
                }
            }
        }
    }

    private fun columns(
        db: Db,
        table: String,
    ): List<List<String?>> =
        db.query("PRAGMA table_info($table)") {
            listOf(it.string(1), it.string(2), it.int(NOT_NULL_COLUMN).toString(), it.stringOrNull(DEFAULT_COLUMN), it.int(PRIMARY_KEY_COLUMN).toString())
        }
}
