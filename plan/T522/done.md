Found 2026-10-10 in `~/.rtok/errors.log` and the `logs` table, 2026-10-03 through 2026-10-10: 25 `invalid line range` failures, each a different spelling. `parse_range` (`src/expand.rs`) already stripped one pair of surrounding quotes (T172) and read `a, b` as `a-b` (T353). It still rejected a space around `-` (`420 - 640`), a colon (`580:650`), brackets (`[1, 50]`), two numbers separated by a space (`610 614`), an HTML break (`95<br>`, `490<br>-800`), quotes in the middle (`235"-"270`) and an underscore (`1860_1960`).

`normalize_range` folds those spellings into `a-b` before the existing checks. A trailing `<br>` with no second number is that one line. A value that is not one or two positive lines still fails with `invalid line range`, and the error keeps the caller's spelling.

Check: unit tests in `src/expand.rs` for the 25 logged strings; `just check`.

Check result: `parse_range_accepts_the_logged_spellings` covers the 25 strings (n = 5000). `parse_range_trailing_br_is_one_line_not_to_end` checks `52` still runs to the end while `52<br>`, `52<br/>` and `52<br><br>` are one line, and `52-70<br>` / `490<br>-800` stay ranges. `parse_range_still_rejects_non_lines` rejects `abc`, `1-two`, `1-2-3`, empty, `[]`, `<br>`, `,,,`, `a b` and `1"2`. Existing bare-start, quote, comma, zero and reverse cases stay green.
