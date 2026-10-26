# Store (`:rtok-store`)

## How it works
One SQLite file (default `~/.rtok/rtok.db`, from `[core] db_path`) through the system `libsqlite3`
(cinterop, FTS5 included). `Store.open` creates the directory owner-only (0700), sets
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

## Enable / disable
Always on. `retain_calls_days = 0` / `retain_hook_bodies_days = 0` keep everything.

## Test
`./gradlew :rtok-store:macosArm64Test`

## Limitations
- Numbers inside re-encoded JSON keep their parsed literal (serde re-prints floats via ryu).
- Ported so far: handle, migrations, sanitize, hook_fields, retention, cache, kv. The remaining
  modules are tracked in [port-plan.md](../port-plan.md).
