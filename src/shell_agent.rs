// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The agent an `rtok` command run from an agent's own shell acts for.

use crate::store::Store;

/// Hosts whose MCP children inherit a session id in the environment, matching the `session_id`
/// their hooks send, and whether an MCP process may register that session's row itself.
/// Grok Build sets it at MCP spawn by first-party code (`research.md` §26). Claude Code
/// documents `CLAUDE_CODE_SESSION_ID` for Bash, hook and stdio MCP subprocesses, but an MCP
/// server keeps the id it was spawned with, which goes stale on `/clear` and may be the startup
/// id on `--continue` (T473), so a Claude MCP only links a row its hooks already wrote.
pub(crate) const SESSION_ENV: &[(&str, &str, bool)] = &[
    ("grok", "GROK_SESSION_ID", true),
    ("claude", "CLAUDE_CODE_SESSION_ID", false),
];

/// T473: the agent an `rtok` command run from an agent's own shell acts for, as a raw id:
/// `RTOK_AGENT_ID` (T283's env file), else the main agent of the host session a
/// [`SESSION_ENV`] var names. Claude Code's desktop app often never delivers the plugin's
/// startup `SessionStart` that writes `RTOK_AGENT_ID` (`research.md` §26), while
/// `CLAUDE_CODE_SESSION_ID` reaches every Bash command and follows `/clear`. Never registers a
/// row: the hook of the tool call running this command has already written it when there is one.
pub fn shell_agent(store: Option<&Store>, env: impl Fn(&str) -> Option<String>) -> Option<String> {
    let set = |k: &str| env(k).filter(|v| !v.trim().is_empty());
    if let Some(id) = set("RTOK_AGENT_ID") {
        return Some(id);
    }
    let store = store?;
    SESSION_ENV.iter().find_map(|(host, var, _)| {
        let session = set(var)?;
        // A host the `hosts` table does not know registers under `other`, as hooks do.
        let host_id = store
            .host_id(host)
            .ok()?
            .or_else(|| store.host_id("other").ok().flatten())?;
        store.main_agent(host_id, session.trim()).ok()?
    })
}
