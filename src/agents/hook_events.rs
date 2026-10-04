// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The one table of hook events rtok registers per host (T390): which host event runs which
//! `rtok hook <event>`. Installers read their event list from here and
//! `tests/hook_manifests.rs` checks every `plugins/*/hooks/hooks.json` against it, so a
//! manifest and an installer cannot quietly diverge (Cursor once lacked two events its
//! manifest never carried, while `done.md` said it had them).

/// One host event and the `rtok hook <rtok_event>` it runs.
pub struct HookEvent {
    pub host: &'static str,
    pub host_event: &'static str,
    pub rtok_event: &'static str,
    /// The host's own installer writes this row into the host's config. `false` marks a row only
    /// the plugin manifest carries; Claude's installer lists are read from its manifest instead.
    pub installer: bool,
}

const fn row(
    host: &'static str,
    host_event: &'static str,
    rtok_event: &'static str,
    installer: bool,
) -> HookEvent {
    HookEvent {
        host,
        host_event,
        rtok_event,
        installer,
    }
}

/// Cursor's `subagentStart` stays out on purpose: its output schema has no context field, so a
/// hook there could not deliver the spawn brief (research.md §23). `beforeSubmitPrompt` is in
/// for the session bookkeeping alone, because its output is only `{continue, user_message}`
/// (https://cursor.com/docs/hooks): the dispatcher injects nothing for it.
pub const HOOK_EVENTS: &[HookEvent] = &[
    row("claude", "PreToolUse", "PreToolUse", false),
    row("claude", "PostToolUse", "PostToolUse", false),
    row("claude", "UserPromptSubmit", "UserPromptSubmit", false),
    row("claude", "SessionStart", "SessionStart", false),
    row("claude", "PreCompact", "PreCompact", false),
    row("claude", "PostCompact", "PostCompact", false),
    row("claude", "SessionEnd", "SessionEnd", false),
    row("claude", "SubagentStart", "SubagentStart", false),
    row("codex", "PreCompact", "PreCompact", false),
    row("codex", "PostCompact", "PostCompact", false),
    row("copilot", "preToolUse", "PreToolUse", true),
    row("copilot", "postToolUse", "PostToolUse", true),
    row("copilot", "userPromptSubmitted", "UserPromptSubmit", true),
    row("copilot", "sessionStart", "SessionStart", true),
    row("copilot", "sessionEnd", "SessionEnd", true),
    row("copilot", "preCompact", "PreCompact", true),
    row("copilot", "subagentStart", "SubagentStart", true),
    row("cursor", "beforeShellExecution", "PreToolUse", true),
    row("cursor", "afterShellExecution", "PostToolUse", true),
    row("cursor", "beforeSubmitPrompt", "UserPromptSubmit", true),
    row("cursor", "sessionStart", "SessionStart", false),
    row("cursor", "sessionEnd", "SessionEnd", true),
    row("cursor", "preCompact", "PreCompact", true),
    row("cursor", "afterMCPExecution", "AfterMCPExecution", false),
    row("cursor", "postToolUse", "PostToolUse", false),
    row("gemini", "BeforeTool", "PreToolUse", true),
    row("gemini", "AfterTool", "PostToolUse", true),
    row("gemini", "BeforeAgent", "UserPromptSubmit", true),
    row("gemini", "SessionStart", "SessionStart", true),
    row("gemini", "SessionEnd", "SessionEnd", true),
    row("gemini", "PreCompress", "PreCompact", true),
    row("grok", "PreToolUse", "PreToolUse", false),
    row("grok", "PostToolUse", "PostToolUse", false),
    row("grok", "UserPromptSubmit", "UserPromptSubmit", false),
    row("grok", "SessionStart", "SessionStart", false),
    row("grok", "PreCompact", "PreCompact", false),
    row("grok", "PostCompact", "PostCompact", false),
    row("grok", "SessionEnd", "SessionEnd", false),
    row("zcode", "PreToolUse", "PreToolUse", false),
    row("zcode", "PostToolUse", "PostToolUse", false),
    row("zcode", "UserPromptSubmit", "UserPromptSubmit", false),
    row("zcode", "SessionStart", "SessionStart", false),
];

/// Every row of `host`, plugin-only ones included.
pub fn for_host(host: &str) -> impl Iterator<Item = &'static HookEvent> + '_ {
    HOOK_EVENTS.iter().filter(move |e| e.host == host)
}

/// `(host event, rtok event)` for what `host`'s installer writes to its own config.
pub fn installed(host: &str) -> impl Iterator<Item = (&'static str, &'static str)> + '_ {
    for_host(host)
        .filter(|e| e.installer)
        .map(|e| (e.host_event, e.rtok_event))
}
