# Project root & `--since` (`rtok.config`)

**How it works.** `ProjectRoot.find(cwd)` walks up to the first `.git`: a directory → main checkout;
a `gitdir:` file → linked worktree (main resolved via `commondir`) or submodule (its own project).
`contain(root, path)` resolves the deepest existing ancestor with realpath and refuses anything that
lands outside `root` (symlink escapes included). `parseSince` accepts `<n>`, `<n>d`, `<n>h`.
**Enable / disable.** Always on; used by commands once they are ported.
**Test.** `./gradlew :rtok-config:macosArm64Test`.
**Limitations.** Config layering (TOML files, env, flags), `Config` and validation are not ported yet.
