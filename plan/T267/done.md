`duration_at` scanned only digits and `.`, then treated any following `s` as a duration. `copied 1src/a.rs` and `copied 2src/a.rs` collapsed to the same key. The `d:d:d` branch (`1:2:3`) required five digits after a scan that stops at `:`, so it never matched. `s` and `ms` now match only at a token boundary, and `d:d:d` is recognized before that scan.

Check: `duration_clock_and_s_suffix_do_not_merge_distinct_lines`; existing normalized-dedupe tests stay green.
