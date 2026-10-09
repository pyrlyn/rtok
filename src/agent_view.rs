// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Agent and session rows shared by the operator model and the console renderer.
//!
//! The model builds these; `render` prints them. The types sit on `store`'s row
//! shapes so the renderer does not import the model (the model reaches `demon`,
//! and `demon` reaches `render`).

use serde::Serialize;

use crate::store::{AgentDetail, SessionTotals};

/// `live`: seen within `[agents] idle`; `idle`: not ended, but quiet longer; `ended`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum AgentState {
    Live,
    Idle,
    Ended,
}

impl AgentState {
    pub fn of(ended_at: Option<i64>, last_seen: i64, now: i64, idle: i64) -> Self {
        match ended_at {
            Some(_) => Self::Ended,
            None if now - last_seen > idle => Self::Idle,
            None => Self::Live,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Live => "live",
            Self::Idle => "idle",
            Self::Ended => "ended",
        }
    }
}

/// One agent with its sub-agents nested (`--json` of `agents show`, and of each
/// `agents sessions` row's `agent`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct AgentView {
    #[serde(flatten)]
    pub detail: AgentDetail,
    /// The session's newest model; `None` for a sub-agent or when no call recorded one.
    pub model: Option<String>,
    /// The claimed worktree's directory name (T285), else `cwd` relative to its checkout,
    /// named by the checkout's directory.
    pub worktree: Option<String>,
    /// Every worktree path this agent holds an open claim on (T285).
    pub worktrees: Vec<String>,
    /// Messages sent to this agent that it has not read (T287).
    pub unread: i64,
    pub state: AgentState,
    pub sub_agents: Vec<AgentView>,
}

/// One `rtok agents sessions` row: the session's totals plus its agent, if registered.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct SessionView {
    #[serde(flatten)]
    pub session: SessionTotals,
    pub state: AgentState,
    pub agent: Option<AgentView>,
}
