From `research.md` §11. sqz strips nulls and flattens arrays in JSON output; rtok cuts `gh … --json`, `aws`, `kubectl -o json` and `curl` bodies by line position, which keeps the opening of the document and loses the keys the model asked for. `toon` (off) is the wire-side encoder and does not run in the hook path.
Step 1 (gate): `stats` share of Bash result bytes whose body parses as JSON, 30 d, this machine, into `research.md` §11.
Done when output that parses as JSON is rewritten before the line cut: null / empty-string / empty-container fields dropped, arrays beyond `json_items` (default 20) elements shown as `… +K more`, object keys kept, strings longer than `json_string` (default 200) cut with their length, one line per top-level key; lossless via the archived raw body and the trailer; a fixture per source (`gh pr list --json`, `aws ec2 describe-instances`, `kubectl get pods -o json`) records the bytes against the default rule; a body that does not parse is untouched.

**Result (2026-09-18).** Isolated worktree on `t65.2` from `t64.1`. Gate: 4 / 200 non-empty `cmd` measurements, 5 776 / 2 792 960 B = **0.21 %** JSON (`~/.rtok/rtok.db`, 30 d, this machine). Rewrite still shipped. `rules::apply` compacts a body that parses as a JSON object or array after grouping and before the head/tail cut: null / empty-string / empty-container fields dropped, arrays beyond `json_items` (default 20) as `… +K more`, strings longer than `json_string` (default 200) cut with their length, one line per top-level key. Unparseable bodies untouched. Table formatters stand down when the body is JSON. Raw archive + trailer unchanged.

Fixtures vs the default rule (raw / line-cut / compact B, est tokens saved vs raw = bytes/4): `gh pr list --json` 10275 / 908 / 4530 (1436); `aws ec2 describe-instances` 19516 / 519 / 5890 (3406); `kubectl get pods -o json` 26271 / 353 / 8297 (4493). Compact is larger than the line cut and keeps the keys the cut drops.

Deviation: `src/plugins/cmd/rules.rs` is 221 insertions (over the 200 LOC guide) because parse/strict/default wiring and the compact helpers live in one file.

Check: `cargo test --lib -- plugins::cmd::` 64 passed, including JSON compact, unparseable untouched, goldens, and kubectl table still `formatter`.

---
