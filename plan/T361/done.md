Found 2026-10-01 (QA audit, #601; still present on `main` @ `aecab806`). A path typo, a directory, a permission error or a non-UTF-8 file all print `inserted 0  skipped 0  malformed 0` with exit 0, so scripts and agents believe the import ran. `src/plugins/memory/import.rs:57` is `std::fs::read_to_string(path).unwrap_or_default()`.

Repro: `rtok memory import /nonexistent.json; echo $?` prints the zero counts and `0`.

Done when: the read propagates its error with the path as context (`.with_context(|| path.display().to_string())?`, the pattern `src/config/validate.rs` already uses), so the command exits non-zero with `Error: /nonexistent.json: No such file or directory`.

Check: an import test with a missing path and one with a directory both return an error naming the path and insert nothing; existing `memory import` tests unchanged; `just check`.

Result (2026-10-03, Claude Code / claude-opus-5-5): `memory::import::run` reads the file first with `.with_context(|| path.display().to_string())?`, before the store opens, so a missing path, a directory or a non-UTF-8 file exits non-zero naming the path and writes nothing. Check: `an_unreadable_file_is_an_error_naming_the_path` (missing path and directory; an empty file still returns zero counts) and the three existing import tests green; clippy `-D warnings`; `just check`.
