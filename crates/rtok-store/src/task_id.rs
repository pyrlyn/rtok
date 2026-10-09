// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Task ids (`R12`, `R2.1`) shared by the task adapters, the store allocator and config validation.

use std::fmt;
use std::str::FromStr;

use anyhow::Result;
use serde::{Deserialize, Deserializer, Serialize, Serializer};

/// Subtasks go one level deep (`R2.1`) until a second level is asked for (T441 §5).
pub const MAX_DEPTH: usize = 2;

/// Longest prefix accepted. A short project tag (`R`, `AT`) keeps ids readable in titles.
pub const MAX_PREFIX: usize = rtok_plugin_sdk::TASK_PREFIX_MAX;

/// A task id such as `R12` or `R2.1`: an ASCII-letter prefix and a dotted number path.
///
/// Input is case-insensitive; output is upper case. Ordering is by prefix, then numerically
/// by path, so `R2 < R2.1 < R10`.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct TaskId {
    prefix: String,
    path: Vec<u32>,
}

impl TaskId {
    /// A top-level id. Fails on a prefix [`check_prefix`] rejects or a zero number.
    pub fn new(prefix: &str, n: u32) -> Result<Self> {
        Self::from_parts(prefix, vec![n])
    }

    fn from_parts(prefix: &str, path: Vec<u32>) -> Result<Self> {
        check_prefix(prefix)?;
        if path.is_empty() || path.len() > MAX_DEPTH {
            anyhow::bail!("task id needs 1 to {MAX_DEPTH} numbers, got {}", path.len());
        }
        if path.contains(&0) {
            anyhow::bail!("task numbers start at 1");
        }
        Ok(Self {
            prefix: prefix.to_ascii_uppercase(),
            path,
        })
    }

    /// The id of subtask `n` under this one; fails past [`MAX_DEPTH`].
    pub fn child(&self, n: u32) -> Result<Self> {
        let mut path = self.path.clone();
        path.push(n);
        Self::from_parts(&self.prefix, path)
    }

    pub fn prefix(&self) -> &str {
        &self.prefix
    }

    pub fn path(&self) -> &[u32] {
        &self.path
    }

    /// The parent of a subtask; `None` for a top-level id.
    pub fn parent(&self) -> Option<Self> {
        (self.path.len() > 1).then(|| Self {
            prefix: self.prefix.clone(),
            path: self.path[..self.path.len() - 1].to_vec(),
        })
    }
}

impl fmt::Display for TaskId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.prefix)?;
        for (i, n) in self.path.iter().enumerate() {
            if i > 0 {
                f.write_str(".")?;
            }
            write!(f, "{n}")?;
        }
        Ok(())
    }
}

impl FromStr for TaskId {
    type Err = anyhow::Error;

    fn from_str(s: &str) -> Result<Self> {
        let s = s.trim();
        let digits = s
            .find(|c: char| !c.is_ascii_alphabetic())
            .unwrap_or(s.len());
        let (prefix, rest) = s.split_at(digits);
        if rest.is_empty() {
            anyhow::bail!("task id {s:?} has no number");
        }
        let path = rest
            .split('.')
            .map(|part| {
                // A leading zero would print differently from what was typed, so `R012` and
                // `R12` could name the same task in two spellings.
                if part.is_empty()
                    || part.starts_with('0')
                    || !part.bytes().all(|b| b.is_ascii_digit())
                {
                    anyhow::bail!("task id {s:?}: {part:?} is not a number from 1");
                }
                Ok(part.parse::<u32>()?)
            })
            .collect::<Result<Vec<_>>>()?;
        Self::from_parts(prefix, path).map_err(|e| anyhow::anyhow!("task id {s:?}: {e}"))
    }
}

impl Serialize for TaskId {
    fn serialize<S: Serializer>(&self, s: S) -> std::result::Result<S::Ok, S::Error> {
        s.collect_str(self)
    }
}

impl<'de> Deserialize<'de> for TaskId {
    fn deserialize<D: Deserializer<'de>>(d: D) -> std::result::Result<Self, D::Error> {
        String::deserialize(d)?
            .parse()
            .map_err(serde::de::Error::custom)
    }
}

/// `[tasks] prefix`: 1 to [`MAX_PREFIX`] ASCII letters. The config validator and the id
/// parser both call this, so `config set` cannot store a prefix ids then refuse.
pub fn check_prefix(prefix: &str) -> Result<()> {
    rtok_plugin_sdk::check_task_prefix(prefix)
}
