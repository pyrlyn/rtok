Confirmed through actual config APIs: `set_with(..., "stats.prices", "42", false)` writes the scalar and `issues` returns no errors, but Figment extraction into `Config` rejects it as an expected map. Likewise `plugins.read.allow_paths = [1]` is written and passes `issues`, then typed extraction rejects element 0 as not a path string. [check_table](crates/rtok-config/src/validate.rs#L545) skips open-map keys entirely through `is_open`, while [check_leaf](crates/rtok-config/src/validate.rs#L663) checks only that an array exists, not its element types. [set_all_with](crates/rtok-config/src/validate.rs#L100) uses those checks as its sole pre-write gate. An accepted edit can therefore leave the user file unloadable, contrary to the setter's contract. These are one incomplete structured-type validation issue; T363–365 cover other constraints, not these shapes.

Execution plan:

1. Add rejection tests for scalar open maps, wrongly typed map entries, and array elements, including the two reproduced commands. Assert both real and dry-run setters reject them and preserve an existing file byte-for-byte.
2. Validate the candidate document against the actual Config field types before persistence, using a pure default-plus-candidate extraction so project/environment overrides cannot mask invalid file values. Retain semantic/range checks and useful key/line diagnostics; do not add a second independently maintained type schema.
3. Keep free-form map names accepted while checking their container and value types. Cover valid custom model prices, benchmark paths, graph mappings, empty arrays and heterogeneous-invalid arrays; apply consistent checks to file validation.
4. Add CLI coverage proving a rejected set leaves subsequent loading functional and valid edits still preserve comments. Rerun config/layer tests and `just check`.

Check: every successful set produces a document accepted by typed config extraction, invalid structured values never replace the existing file, and free-form names remain supported. The scratch probe used the actual config crate's setter/validator plus Figment default-and-file extraction; full CLI and environment layering were not executed.

Result: `issues_in` in `crates/rtok-config/src/validate.rs` does a typed `Config` extraction from defaults plus the candidate file, so `config set` rejects wrongly typed values (real and dry run) with the file unchanged.

Check: rtok-config 85 green, `--test config_coverage`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
