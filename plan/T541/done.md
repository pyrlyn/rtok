Source-confirmed: [disk scan/find](src/tasks/disk.rs#L81) accepts a valid task id in the filename without comparing it to the parsed frontmatter id. Lookup selects by filename, but [place/write](src/tasks/disk.rs#L116) chooses the destination using the frontmatter id and title, then deletes the original file. Create a valid `R2 - target.md`, then a manually copied `R1 - copy.md` whose frontmatter says `id: R2`, `title: target` and has a different body. Setting R1 to in-progress overwrites R2's file with the copied body and removes R1. A malformed identity must be rejected before any mutation. T441.4 shipped the adapter; it does not validate this consistency boundary.

Execution plan:

1. Add a two-file fixture with the mismatch above; assert get/list/status reject the inconsistent task and preserve both files byte-for-byte.
2. Validate filename id against frontmatter id in the shared disk read path and return an error naming the offending path and ids before write/move operations.
3. Cover mismatches in active and done directories, status changes and claim/save; retain valid renames, moves and case-normalized ids.

Check: no operation on the malformed R1 overwrites R2 or removes either fixture; valid task lifecycle tests and `just check` pass. This reproduction is source-derived; an end-to-end regression remains part of the fix.

Result: `scan()` in `src/tasks/disk.rs` errors, naming the path and both ids, before any mutation when the filename id differs from the front-matter id; `get`, `list`, `write_status`, `save` and `max_id` all go through it.

Check: `a_file_whose_front_matter_id_differs_from_its_name_is_rejected_untouched`, `a_mismatch_in_done_is_rejected_and_a_lower_case_name_still_matches`; `tasks::` 108 green.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
