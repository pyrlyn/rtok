**T23.6 the release publishes it** · T23.5 · `release-plz.toml`, `.github/workflows/release-plz.yml`, `.github/workflows/ci.yml`
Do: `publish = false` becomes a per-package setting — the SDK is published, the `rtok` binary crate
stays off crates.io (dist ships it). release-plz gains the `release` command with
`CARGO_REGISTRY_TOKEN`, after `verify`, and `semver_check` is switched on for the SDK because its
whole point is a stable surface. CI runs `cargo publish -p rtok-plugin-sdk --dry-run` so a broken
manifest fails on the pull request, not at the tag.
Complexity: 2/5
Status: done 2026-09-09
Model: Claude Opus 5 (anthropic/claude-opus-5)
Check result: `just publish-dry` (`cargo publish -p rtok-plugin-sdk --dry-run --locked`) packages
12 files, 68.7 KiB, and verifies the built package — green. `publish = false` moved off the
workspace default onto the `rtok` package itself and `[[package]] rtok-plugin-sdk` sets
`publish = true`, so a release run publishes exactly one crate. cargo-semver-checks v0.50.0
against `--baseline-rev HEAD` passes 223 checks on the tree as it is; adding one required method
to `Plugin` fails it with `trait_method_added` — "a non-sealed public trait added a new method
without a default implementation" — and the probe was reverted. The licence is Apache-2.0, the
one the repository owner chose, with the full text in `crates/rtok-plugin-sdk/LICENSE`.
Deviation: the licence file sits inside the crate, not at the repository root. `cargo publish`
packages that directory and nothing above it, and the owner's answer was about this crate — what
licence the binary and the rest of the tree carry is still their decision to make.
Deviation: `--release-type patch` was needed to make cargo-semver-checks say anything. At 0.0.1
with an identical baseline version it assumes major and skips all 254 lints; release-plz will not
have that problem once a version is actually out, but it is why the local proof is spelled that
way.
Note: cargo-semver-checks is not in mise's registry and there is no `cargo binstall` here, so the
proof was run from a `cargo install`ed binary in `~/.cargo/bin`. Nothing in the repository depends
on it locally — in CI, `release-plz/action` brings its own.
Note: `just example` now also runs the SDK's own `shrink` example, so CI exercises the plugin
that has no `rtok` dependency.
