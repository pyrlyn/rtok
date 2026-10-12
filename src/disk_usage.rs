// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Disk usage walk shared by `agents junk` and the removal previews. A leaf module: it knows
//! nothing of agents or worktrees, so both can preview a removal without importing each other.
//! The walk itself is `fs-kit`'s (disk usage, not apparent size; links never followed).

use std::path::Path;
use std::time::Duration;

/// What one walk found. A folder it could not read or finish is a lower bound, never an error.
pub use fs_kit::tree::{Usage, usage_until as disk_usage_until};

/// How long one agent's folders may take to size before the rest is reported as a lower bound
/// (a huge cache on a network volume must not hang `list`).
pub const AGENT_SCAN_LIMIT: Duration = Duration::from_secs(10);

/// Disk usage of `path`, not apparent size; see [`fs_kit::tree::usage`].
pub fn disk_usage(path: &Path) -> u64 {
    fs_kit::tree::usage(path).bytes
}
