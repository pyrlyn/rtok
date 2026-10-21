Found 2026-09-26 running `just check` locally on Windows (rust 1.97.1, the mise pin): the new clippy lint `permissions_set_readonly_false` fires on `rtok-agent-sdk`'s `clear_readonly` and, under `-D warnings`, fails the whole `lint` recipe. CI never sees it — the function is `#[cfg(windows)]`, compiled out on the Linux/macOS runners.

Plan: a scoped `#[allow(clippy::permissions_set_readonly_false)]` with a comment — the lint's world-writable rationale is Unix-only and this function exists only on Windows, where `MOVEFILE_REPLACE_EXISTING` needs the read-only bit gone.

Check: `cargo clippy -p rtok-agent-sdk --all-targets --all-features -- -D warnings` green on Windows (2026-09-26). The full gate runs in CI on the branch — the creator's call; this lint is invisible to CI's ubuntu jobs, which analyze no cfg(windows) code.

Status: done 2026-09-26
Model: ZCode / glm-5.3
