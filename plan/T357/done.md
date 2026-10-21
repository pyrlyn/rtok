Found 2026-10-02 (T349): installed hooks run `rtok-hook` only when it is on `PATH`, and a package manager that links one binary (the published ketch manifest links only `rtok`) leaves the fast client unused although `rtok-hook` sits next to the real `rtok` executable (`~/.ketch/store/rtok/<version>/rtok-hook`). The fix must not depend on publishing a manifest. Creator's choice (2026-10-02): the link goes next to `rtok` on `PATH`; hook commands stay as they are.

Done when: when `rtok-hook` is not found on `PATH`, rtok creates `<dir of the rtok found on PATH>/rtok-hook` as a symlink to the `rtok-hook` beside the canonical `current_exe()`, and re-points a symlink of that name that dangles or points at another version's `rtok-hook` (after an upgrade). It never replaces a regular file or a working link it did not make, does nothing when the sibling is missing or the directory is not writable, and is skipped on Windows (no unprivileged symlinks; the T349 doctor advice stays). Runs from `rtok init`/setup, `rtok doctor --fix` if present, and the start of `rtok mcp` and `rtok hook --serve` — a few `stat` calls, never on the per-hook path. `rtok doctor` reports the link.

Execution plan: one helper in `src/agents/mod.rs` next to `bin_on_path` (PATH lookup, sibling resolution, atomic create via temp name + rename); call sites listed above; tests with a temp `PATH`, a fake package dir with `rtok`/`rtok-hook`, a dangling old-version link, a foreign regular file, and a read-only dir. Verify on this machine with a copy of `~/.ketch/bin` layout under the scratch dir, not the real one.

Check: unit tests for each case above; `rtok doctor` no longer prints the T349 advice once the link exists.

**Result (2026-10-02, pyrlyn/rtok#617).** `agents::ensure_hook_client_link` (unix) takes the first `rtok` on `PATH`; only when it canonicalizes to the running executable does it link `rtok-hook` beside it to the sibling `rtok-hook` (temp name + rename), re-pointing a dangling or other-version link and leaving a regular file or a foreign link alone. It runs at the start of `rtok mcp`, `rtok hook --serve` and `rtok agents install`. Windows keeps the T349 doctor advice. 8 `hook_client_link` tests cover each case.

Status: done 2026-10-02
Model: Claude Code / claude-opus-5-5
