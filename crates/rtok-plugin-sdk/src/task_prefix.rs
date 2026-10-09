//! `[tasks] prefix` rule. The store's id parser and config validation both call it, so
//! `config set` cannot store a prefix the allocator then refuses. It lives here so neither
//! crate depends on the other.

use anyhow::{Result, bail};

/// Longest prefix accepted. A short project tag (`R`, `AT`) keeps ids readable in titles.
pub const TASK_PREFIX_MAX: usize = 8;

/// 1 to [`TASK_PREFIX_MAX`] ASCII letters.
pub fn check_task_prefix(prefix: &str) -> Result<()> {
    if prefix.is_empty()
        || prefix.len() > TASK_PREFIX_MAX
        || !prefix.bytes().all(|b| b.is_ascii_alphabetic())
    {
        bail!("task prefix {prefix:?} must be 1 to {TASK_PREFIX_MAX} ASCII letters");
    }
    Ok(())
}
