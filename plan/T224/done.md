Found 2026-09-22 in the docs pass: `report.html` and `report/jscpd-report.json` are stale jscpd outputs (`.jscpd.json` now sets `reporters: ["console"]`, so they are unreproducible) and `dump/` holds nine captured stdout/stderr files — all in the tree; `.gitignore` covers `rtok.db`/`/~/` but not `/report.html` or `/dump/`. The `.rtok/`/`~`/`rtok.db` half of the cleanup is T184; this is the other half of "an artifact that is not reproducible from a command should not be in the repo".

Plan: `git rm --cached` `report.html`, `report/jscpd-report.json`, `dump/*`; extend `.gitignore` with `/report.html`, `/report/`, `/dump/`; the jscpd console workflow stays the way to regenerate reports.

Check: `git ls-files report.html report/ dump/` prints nothing; after `just test` and `just dup`, `git status --porcelain` stays clean (T184's Check covers the rest); `just check` green.

Result: Untracked the stale report.html (report/jscpd-report.json and dump/ were already untracked); .gitignore now covers /report.html, /report/, /dump/. just dup leaves git status clean.
