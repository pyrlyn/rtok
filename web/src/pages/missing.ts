// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

/**
 * Why a field is empty, one place for every page (T414.17). Each reason restates where the
 * Rust side leaves the field null, so a page never guesses: `src/store` for calls and
 * sessions, `src/web/model.rs` for plugins and hosts, `src/worktree/list.rs` for worktrees.
 */
export const why = {
  callPlugin: "The call was recorded without a plugin.",
  callName: "The call was recorded without a tool or plugin name.",
  callHost: "The call was recorded without a host.",
  callProvider: "The call was recorded without a provider.",
  callModel: "The call was recorded without a model.",
  callMs: "No duration was recorded for this call.",
  callUsage: "No usage row is linked to this call: hooks, MCP calls and plugin runs carry none.",
  callParent: "A top-level call: no other call started it.",
  callRef: "Nothing was archived for this call, so there is nothing to expand.",
  sessionHost: "The session was recorded without a host.",
  sessionProject: "The session was recorded without a project.",
  sessionProvider: "No call in this session recorded a provider.",
  sessionUsage: "No token usage has been recorded for this session yet.",
  pluginNoSaving: "This plugin does not save tokens, so it records no Measurement rows.",
  pluginNoRows: "No Measurement rows yet, so no saving is claimed.",
  hostApp: "No app bundle or binary of this host was found on this machine.",
  hostConfig: "rtok writes no config file for this host.",
  worktreeBranch: "Git records no branch here: the worktree is detached or not listed by git.",
  worktreeOwner: "Not locked and no owner recorded.",
  worktreeAgent: "No agent is bound and no session was seen here.",
  worktreeAgentState: "No agent is bound to this worktree.",
  worktreeSeen: "No agent session was seen in this worktree.",
  worktreeModified: "The modification time of this worktree could not be read.",
  servicePid: "The service is not running, so it has no process.",
  serviceLog: "The status line carried no log path.",
  otelEndpoint: "No OTel endpoint is configured, so nothing is exported.",
  statsHit: "The stats output carried no cache hit rate.",
} as const;
