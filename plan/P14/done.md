Goal: every plugin beats the field on a named number before a line of it is written (D15). Template + `plugin_plans` test, then one `PLAN.md` per catalogue plugin. No plugin code, no new dependency.

Check: `cargo test plugin_plans` green (7 tests); `ls src/plugins/*/PLAN.md | wc -l` → 10.
