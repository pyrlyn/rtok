Goal: `rtok --version`, DB, plugin registry, hook I/O types. All seven tasks done.

Gate P0 (review) · Status: done 2026-09-03 (historical) · Check: T0.8 `c9b6f81` froze `Plugin` with no catalogue plugin logic yet. Later phases added logic; §6 records that the wording is a freeze date, not HEAD.

Verified at the head of the P0 series: `make check` (fmt, clippy `-D warnings` on all targets
and features, 13 unit tests, single-feature build) and `make example` pass on macOS,
Rust 1.97.1.
