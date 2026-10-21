`MIGRATIONS` is a hand-kept list, and `src/store/schema.rs` `table!` macros are hand-kept too. A unit test: every `migrations/*.sql` file is in `MIGRATIONS`, in filename order, and after all migrations each `table!` column set equals `PRAGMA table_info`.

Check: deleting a line from `MIGRATIONS` or a column from `schema.rs` fails the test; `just test` green.

Do (2026-09-21): two unit tests in `src/store/mod.rs`. `migrations_list_matches_the_directory` reads `migrations/*.sql`, sorts the names and compares them with `MIGRATIONS` in order. `schema_rs_matches_the_migrated_tables` parses every `diesel::table!` in `schema.rs` (`include_str!`, `#[sql_name]` resolved to the SQL name) and compares each column set with `pragma_table_info` on a fully migrated in-memory store. Its first run found real drift: `0017.sql` (T69.2) added `notes.uses` and `notes.last_used`, which `schema.rs` never listed — both added (`Integer`, `Nullable<BigInt>`). Every `notes` query selects columns by name, so nothing else changed.

Check result (2026-09-21): mutation-checked — deleting the `0018.sql` line from `MIGRATIONS` fails `migrations_list_matches_the_directory` ("MIGRATIONS drifted"); deleting `notes.last_used` from `schema.rs` fails `schema_rs_matches_the_migrated_tables` ("schema.rs `notes` vs the migrated table"). Both green on the real tree; `just check` green.
