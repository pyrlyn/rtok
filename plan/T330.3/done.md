Split into T330.3.1 (done) and T330.3.2 (one PR each); this card stays the spec and the parent.

Part of T330. The safe kinds of the T330 table plus rtok's cache (`.rtok-lsp-xdg/{cache,pub-cache}`, `$XDG_CACHE_HOME/rtok`, `CACHEDIR.TAG` dirs, T329 registry roots) and each agent's cache: §22 dirs and `CACHEDIR.TAG` dirs are cleared; platform cache roots and Electron cache folders without a §22 row are listed as `not documented: not cleared` (D36). A bad `CACHEDIR.TAG` signature is not cache; settings, extensions and downloaded models are `never`. Depends on T330.2 (T339 closed: D36; T342 closed: `build` and `CACHEDIR.TAG` dirs follow T152's idle, own-worktree and one-root rules).

Check: the T330 "Cache" fixtures (exact sizes, kinds under the right agent, tag handling); `just check`.

Closed 2026-10-07 with its last subtask: T330.3.1 and T330.3.2 (#793).
