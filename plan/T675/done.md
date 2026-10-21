Best competitor: none specialised. Design: detect pytest/unittest/traceback output: keep the full last traceback, collapse repeated frames, summary line; JSON stdout → TOON (#1078).

Check: #1076 harness (`bench/c14`) shows ≥ t-o pytest (564→115) on this category with full anchor retention.

Plan: add a `python` formatter in `compact.rs`: pytest/unittest output keeps the summary and the full last traceback, collapses repeated frames and repeated failing-test headers; JSON stdout is printed as a TOON table through the existing #1078 path if merged, otherwise left to a follow-up (written in this card). Tests: golden `python_pytest.in/out`, `python_traceback.in/out`.

Result: Measured by golden bytes (tokens are not measured here: the #1076 bench/c14 harness is not on main): deep recursion traceback 3974 B -> 251 B (94%, `[previous frame repeated N more times]`, every other frame and the exception line kept), pytest failing run 1349 B -> 662 B (51%, failure blocks, warnings, summary and totals whole, header and passing progress dropped), unittest pass 254 B -> 99 B (61%). New `src/plugins/cmd/python.rs` (pytest, unittest, traceback fold) wired into the `pytest` arm and new `python`/`python3`/`py` arms in formatters.rs; falls back to the old keep() or the `python` rule when the shape is not recognised. Not done: JSON stdout -> TOON, because #1078 is not on main; follow-up once it lands.

Status: done 2026-10-15
Model: Claude Code / claude-sonnet-5-5
