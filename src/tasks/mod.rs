// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Task adapters (T441): agents create and track tasks through rtok, numbered by one
//! allocator and stored on disk, in GitHub or in GitLab. This module holds the domain types
//! every adapter, the CLI and the MCP tools share (T441.2); `[tasks]` in the config picks the
//! adapter and the id prefix.
//!
//! "Adapter" is the task storage backend. "Provider" stays the proxy's upstream LLM API.

use std::fmt;
use std::str::FromStr;

use anyhow::{Result, bail};
use serde::{Deserialize, Deserializer, Serialize, Serializer};

pub mod adapter;
pub mod disk;
pub mod github;
mod github_project;
pub mod remote;
pub mod run;

/// Subtasks go one level deep (`R2.1`) until a second level is asked for (T441 §5).
pub const MAX_DEPTH: usize = 2;

/// Longest prefix accepted. A short project tag (`R`, `AT`) keeps ids readable in titles.
pub const MAX_PREFIX: usize = 8;

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
            bail!("task id needs 1 to {MAX_DEPTH} numbers, got {}", path.len());
        }
        if path.contains(&0) {
            bail!("task numbers start at 1");
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
            bail!("task id {s:?} has no number");
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
                    bail!("task id {s:?}: {part:?} is not a number from 1");
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
    if prefix.is_empty()
        || prefix.len() > MAX_PREFIX
        || !prefix.bytes().all(|b| b.is_ascii_alphabetic())
    {
        bail!("task prefix {prefix:?} must be 1 to {MAX_PREFIX} ASCII letters");
    }
    Ok(())
}

/// The prefix a project gets when `[tasks] prefix` is empty: the first ASCII letter of its
/// name, upper-cased (`rtok` → `R`). `None` when the name has no ASCII letter.
pub fn default_prefix(project: &str) -> Option<String> {
    project
        .chars()
        .find(char::is_ascii_alphabetic)
        .map(|c| c.to_ascii_uppercase().to_string())
}

/// The prefix new ids get: `[tasks] prefix` when set, else [`default_prefix`] of `project`
/// (its name, [`crate::project::project_name`]).
pub fn resolve_prefix(configured: &str, project: Option<&str>) -> Result<String> {
    if !configured.is_empty() {
        check_prefix(configured)?;
        return Ok(configured.to_ascii_uppercase());
    }
    match project.and_then(default_prefix) {
        Some(p) => Ok(p),
        None => {
            bail!("no task prefix: set [tasks] prefix, since the project name has no ASCII letter")
        }
    }
}

/// Where a task stands. `Done` and `Closed` both leave the plan; `Closed` is "won't do",
/// which GitHub (`not_planned`) and GitLab ("Won't do") record natively.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Status {
    Open,
    InProgress,
    Done,
    Closed,
}

impl Status {
    pub const ALL: [Self; 4] = [Self::Open, Self::InProgress, Self::Done, Self::Closed];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Open => "open",
            Self::InProgress => "in-progress",
            Self::Done => "done",
            Self::Closed => "closed",
        }
    }

    /// Still in the plan: listed by default and blocking a parent from closing.
    pub fn is_active(self) -> bool {
        matches!(self, Self::Open | Self::InProgress)
    }
}

impl fmt::Display for Status {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for Status {
    type Err = anyhow::Error;

    /// Also takes the checklist words Claude Code and Codex use (`pending`, `in_progress`,
    /// `completed`), since agents reach for those first.
    fn from_str(s: &str) -> Result<Self> {
        Ok(
            match s.trim().to_ascii_lowercase().replace('_', "-").as_str() {
                "open" | "todo" | "pending" => Self::Open,
                "in-progress" => Self::InProgress,
                "done" | "completed" => Self::Done,
                "closed" | "wontdo" | "won't-do" | "not-planned" => Self::Closed,
                other => {
                    bail!("unknown task status {other:?}: use open, in-progress, done or closed")
                }
            },
        )
    }
}

/// The issue a task lives in on GitHub or GitLab. The rtok id is ours; the number is theirs.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExternalRef {
    /// `github` or `gitlab`.
    pub adapter: String,
    /// GitHub issue number or GitLab issue iid.
    pub number: u64,
    pub url: String,
    /// GraphQL node or global id, for the calls REST cannot make (sub-issues, Status).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub node_id: Option<String>,
}

/// What a caller supplies to create a task; the allocator adds the id.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NewTask {
    pub title: String,
    #[serde(default)]
    pub description: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent: Option<TaskId>,
}

/// One task as every adapter returns it, and the JSON shape of the CLI and MCP tools.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Task {
    pub id: TaskId,
    pub title: String,
    #[serde(default)]
    pub description: String,
    pub status: Status,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent: Option<TaskId>,
    /// Unix seconds.
    pub created_at: i64,
    /// Unix seconds.
    pub updated_at: i64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub external: Option<ExternalRef>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use rstest::rstest;

    #[rstest]
    #[case("R12", "R12")]
    #[case("r2.1", "R2.1")]
    #[case(" at7 ", "AT7")]
    #[case("Rtok1.20", "RTOK1.20")]
    fn ids_parse_case_insensitively_and_print_upper_case(#[case] input: &str, #[case] canon: &str) {
        let id: TaskId = input.parse().unwrap();
        assert_eq!(id.to_string(), canon);
        assert_eq!(canon.parse::<TaskId>().unwrap(), id);
    }

    #[rstest]
    #[case("")]
    #[case("R")]
    #[case("12")]
    #[case("R0")]
    #[case("R012")]
    #[case("R1.")]
    #[case("R1..2")]
    #[case("R1.2.3")]
    #[case("R-1")]
    #[case("R1a")]
    #[case("Ж1")]
    #[case("ABCDEFGHI1")]
    #[case("R99999999999")]
    fn malformed_ids_are_refused(#[case] input: &str) {
        assert!(input.parse::<TaskId>().is_err(), "{input:?} parsed");
    }

    #[test]
    fn ids_order_numerically_and_know_their_parent() {
        let mut ids: Vec<TaskId> = ["R10", "R2.1", "R2", "R9", "Q1"]
            .iter()
            .map(|s| s.parse().unwrap())
            .collect();
        ids.sort();
        let shown: Vec<String> = ids.iter().map(ToString::to_string).collect();
        assert_eq!(shown, ["Q1", "R2", "R2.1", "R9", "R10"]);
        let sub: TaskId = "R2.1".parse().unwrap();
        assert_eq!(sub.parent(), Some(TaskId::new("r", 2).unwrap()));
        assert_eq!(sub.parent().unwrap().parent(), None);
        assert_eq!(
            TaskId::new("R", 2).unwrap().child(3).unwrap().to_string(),
            "R2.3"
        );
        assert!(sub.child(1).is_err(), "depth past {MAX_DEPTH}");
    }

    #[test]
    fn ids_serialize_as_strings() {
        let id: TaskId = "R2.1".parse().unwrap();
        assert_eq!(serde_json::to_string(&id).unwrap(), "\"R2.1\"");
        assert_eq!(serde_json::from_str::<TaskId>("\"r2.1\"").unwrap(), id);
        assert!(serde_json::from_str::<TaskId>("\"R0\"").is_err());
    }

    #[rstest]
    #[case("rtok", Some("R"))]
    #[case("airtalk", Some("A"))]
    #[case("2fa-app", Some("F"))]
    #[case("123", None)]
    fn default_prefix_is_the_first_letter(#[case] project: &str, #[case] want: Option<&str>) {
        assert_eq!(default_prefix(project).as_deref(), want);
    }

    #[test]
    fn a_configured_prefix_wins_over_the_project_name() {
        assert_eq!(resolve_prefix("at", Some("rtok")).unwrap(), "AT");
        assert_eq!(resolve_prefix("", Some("rtok")).unwrap(), "R");
        assert!(resolve_prefix("", Some("123")).is_err());
        assert!(resolve_prefix("", None).is_err());
        assert!(resolve_prefix("R2", Some("rtok")).is_err());
    }

    #[test]
    fn statuses_round_trip_and_take_host_words() {
        for status in Status::ALL {
            assert_eq!(status.as_str().parse::<Status>().unwrap(), status);
            let json = serde_json::to_string(&status).unwrap();
            assert_eq!(json, format!("\"{status}\""));
        }
        for (word, want) in [
            ("pending", Status::Open),
            ("in_progress", Status::InProgress),
            ("In-Progress", Status::InProgress),
            ("completed", Status::Done),
            ("not_planned", Status::Closed),
        ] {
            assert_eq!(word.parse::<Status>().unwrap(), want, "{word}");
        }
        assert!("blocked".parse::<Status>().is_err());
        assert!(Status::Open.is_active() && Status::InProgress.is_active());
        assert!(!Status::Done.is_active() && !Status::Closed.is_active());
    }

    #[test]
    fn a_task_round_trips_through_json_without_empty_optionals() {
        let task = Task {
            id: "R3".parse().unwrap(),
            title: "Ship it".into(),
            description: String::new(),
            status: Status::InProgress,
            parent: None,
            created_at: 1,
            updated_at: 2,
            external: None,
        };
        let json = serde_json::to_string(&task).unwrap();
        assert!(
            !json.contains("parent") && !json.contains("external"),
            "{json}"
        );
        assert_eq!(serde_json::from_str::<Task>(&json).unwrap(), task);
    }
}
