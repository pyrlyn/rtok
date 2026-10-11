// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `<n>`, `<n>d` or `<n>h` windows shared by `stats`, `report` and config validation.
//! The parser lives in `rtok-config`, next to the keys it checks.
//!
//! That is the `--since` grammar only (bare `<n>` is days). `worktree --idle` /
//! `--stale-lock` and `[agents] idle` take humantime (`30m`, `0s`, `24h`, `7d`) instead —
//! `rtok_store::parse_idle_from`, the one parser of that key — so a value copied from
//! `[agents] idle` is accepted there and `--since` keeps its days-only callers unchanged.

pub use rtok_config::{parse_since, parse_since_from};
