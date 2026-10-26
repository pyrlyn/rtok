# Store (`:rtok-store`)

## How it works
One SQLite file (default `~/.rtok/rtok.db`, from `[core] db_path`) through androidx.sqlite's
`BundledSQLiteDriver` (Room KMP's driver layer, SQLite compiled in, FTS5 included). `Store.open` creates the directory owner-only (0700), sets
`busy_timeout`, `auto_vacuum=INCREMENTAL` (new files only), `journal_mode=WAL`,
`synchronous=NORMAL`, `foreign_keys=ON`, tightens the db/WAL/SHM files to 0600, and retries while
another process holds the lock.

Migrations are the Rust `migrations/<version>/up.sql` files, copied verbatim and embedded at
build time (`genMigrations`). They are recorded exactly like Diesel does — table
`__diesel_schema_migrations`, version = the 4-digit directory prefix — and applied inside one
`BEGIN EXCLUSIVE`, so a database is interchangeable between the Rust and Kotlin builds. A
pre-Diesel `schema_migrations` table (`NNNN.sql`) is bridged once.

JSON written to the store follows serde_json's shape (sorted keys, compact) so rows written by
either build compare byte-for-byte. Stored bodies are cleaned by `Sanitize` (escapes, control
and zero-width characters, CRLF, blank runs, harness wrapper blocks) unless `[core] store_raw`.

Retention (`Store.housekeeping`, run at session start) purges calls older than
`retain_calls_days`, clears hook bodies older than `retain_hook_bodies_days` in batches of 5000,
drops orphan hook-session rows and dead symbol roots, then runs an incremental vacuum; a pre-T352
file is converted to incremental auto-vacuum once.

## Layout
One file per Rust module, as extension functions on `Store` (`Agents.kt`, `Messages.kt`,
`Symbols.kt`, `Embed.kt`, …); `SqlExt.kt` holds the shared SQL helpers (`inList`, `chunkedIn`).

The SQLite binding is an adapter: the store talks only to `rtok.db.Db` / `Row` (module
`libs/db`), and `libs/db-room` implements them over androidx.sqlite. The application installs the
driver once (`Db.installIfAbsent(RoomDbDriver)` in `main`); store tests install the same one from
`Support.kt`. androidx.sqlite prepares one statement at a time, so the adapter splits scripts
(migrations) itself, keeping trigger bodies, quotes and comments whole.

Room's entities/DAOs and its KSP code generation are not used: Room owns the schema it creates
(its own DDL plus a `room_master_table`), which cannot stay byte-identical to the Rust schema the
drift test checks. Migrations stay the Rust raw SQL, run through androidx.sqlite.

Schema compatibility is tested, not assumed: `ArchiveTest` checks the migrated database against
the Rust crate's `schema.rs` and `schema_snapshot.txt` (copied to `src/nativeTest/fixtures`).

Note embeddings (`[plugins.memory.embed]`) use the same deterministic hash vectors as Rust
(`hash2` scheme), or a host-supplied `TextEmbedder` for a remote backend.

## Enable / disable
Always on. `retain_calls_days = 0` / `retain_hook_bodies_days = 0` keep everything.

## Test
`./gradlew :rtok-store:macosArm64Test`

## Limitations
- The bundled SQLite defaults to `auto_vacuum = 1`; the store sets `INCREMENTAL` on new files as before, and pre-T352 files are still converted.
- Numbers inside re-encoded JSON keep their parsed literal (serde re-prints floats via ryu).
- Every rtok-store module is ported. `host_usage` reads a host's own database through the same
  driver.
- Ordering that Rust does in memory uses Rust string order (`compareUtf8`, code-point order);
  ordering done in SQL is SQLite's `BINARY` collation in both builds.
- Hash embeddings split words on `Char.isLetterOrDigit`; a letter outside the Basic Multilingual
  Plane separates words, where Rust's `char::is_alphanumeric` would keep it.
