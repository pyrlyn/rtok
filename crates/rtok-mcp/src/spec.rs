//! `McpSpec`: the static, per-host-client data every host module repeats by hand today
//! (T277). One value says where rtok's MCP entry lives in a host's config, how the file is
//! shaped, and what a same-named plugin server does next to it (decision D33, research.md §25).

use std::path::PathBuf;

use serde_json::{Value, json};

use crate::registry::Server;

/// The config file's syntax. `Json` is strict (no comments, rewritten as pretty JSON, T79);
/// `Jsonc` and `Toml` keep the file's own formatting and comments on every write.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Format {
    Json,
    Jsonc,
    Toml,
}

/// Which client of a host an entry is for: its CLI, its desktop app, or an editor
/// extension host. Claude Code and Claude Desktop are two clients of one host, each with its
/// own [`McpSpec`] and [`crate::status::McpStatus`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Client {
    Cli,
    Desktop,
    Ide,
}

/// What a host does when a plugin serves a server under the same name as a config entry
/// (research.md §25). Only `ShowsBoth` (Claude Code's plugin namespacing) and
/// `OverridesByScope` (Gemini's `settings.json`) are confirmed on any host today; `Merges` and
/// `Errors` are kept for a host whose docs describe that behaviour once one is found.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DuplicateName {
    ShowsBoth,
    OverridesByScope,
    Merges,
    Errors,
}

/// Whether the entry carries the `"type": "stdio"` default (Claude Code, Cursor) or omits it
/// (a host whose schema has only one transport, so the field is redundant).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EntryShape {
    TypedCommand,
    Command,
    /// Copilot CLI: `"type": "local"` and `"tools": ["*"]` (all of the server's tools).
    LocalAllTools,
}

/// How [`crate::ops::apply`] words the report of a change.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ReportStyle {
    /// `<key>.<name>: <command> <args>` and `- <key>.<name>`.
    #[default]
    Summary,
    /// `+ [<key>.<name>]` followed by the entry's own `key = value` lines, and
    /// `- [<key>.<name>]`: the table a TOML host's installer shows, which is what its users
    /// read in a dry run.
    TomlBlock,
}

/// How a host starts the server when that is not the registry's bare `rtok mcp`. A desktop app
/// starts without a shell `PATH`, so it needs the absolute binary; since T283.2 every host's
/// entry also names itself with `--host <id>`.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Launch {
    /// The binary to run instead of `server.command`.
    pub command: Option<String>,
    /// Append `--host <spec.host>` to the registry's args.
    pub host_arg: bool,
}

/// One host client's MCP config: where it lives, how it is shaped, and the entry rtok writes
/// into it. `ops::apply` is the only thing that should read one of these.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct McpSpec {
    pub host: &'static str,
    pub client: Client,
    pub config_path: PathBuf,
    pub format: Format,
    /// Dotted path to the servers table (`mcpServers`, `mcp_servers`, `mcp.servers`).
    pub key_path: Vec<String>,
    pub server: Server,
    pub launch: Launch,
    pub entry_shape: EntryShape,
    pub duplicate_name: DuplicateName,
    /// The name the rtok plugin itself serves on this host, if any (D33: usually none — the
    /// plugin ships no MCP server and the config entry is the only call path).
    pub plugin_serves: Option<&'static str>,
    /// Keys the entry carries with a seed value that the user may change (Codex's
    /// `default_tools_approval_mode`). Install adds one that is missing and never overwrites
    /// one that is there, as long as the rest of the entry still starts rtok; status and
    /// remove do not count a changed value as a stale or an edited entry.
    pub user_keys: Vec<(&'static str, Value)>,
    pub report: ReportStyle,
}

impl McpSpec {
    /// `"a.b.c"` → `["a", "b", "c"]`, the shape [`Self::key_path`] itself uses (ZCode's
    /// `mcp.servers`, ChatGPT/Codex's `plugins.<plugin>.mcp_servers`).
    pub fn key_path(dotted: &str) -> Vec<String> {
        dotted.split('.').map(str::to_string).collect()
    }

    /// The binary and its args as the entry spells them: the launch override on top of the
    /// registry's bare `rtok mcp`.
    fn argv(&self) -> (&str, Vec<&'static str>) {
        let command = self
            .launch
            .command
            .as_deref()
            .unwrap_or(self.server.command);
        let mut args = self.server.args.to_vec();
        if self.launch.host_arg {
            args.extend(["--host", self.host]);
        }
        (command, args)
    }

    /// `rtok mcp --host claude`: what a host's report prints after `<key>.<name>: `.
    pub fn summary(&self) -> String {
        let (command, args) = self.argv();
        format!("{command} {}", args.join(" "))
    }

    /// The JSON entry [`crate::config`] and [`crate::ops`] write under
    /// `key_path.<server.name>`. TOML specs get the same shape converted on write
    /// ([`crate::config`] owns that conversion, never a caller).
    pub fn entry(&self) -> Value {
        let mut m = serde_json::Map::new();
        match self.entry_shape {
            EntryShape::TypedCommand => {
                m.insert("type".into(), json!("stdio"));
            }
            EntryShape::LocalAllTools => {
                m.insert("type".into(), json!("local"));
            }
            EntryShape::Command => {}
        }
        let (command, args) = self.argv();
        m.insert("command".into(), json!(command));
        m.insert("args".into(), json!(args));
        if self.entry_shape == EntryShape::LocalAllTools {
            m.insert("tools".into(), json!(["*"]));
        }
        for (key, seed) in &self.user_keys {
            m.insert((*key).into(), seed.clone());
        }
        if !self.server.env.is_empty() {
            let env: serde_json::Map<String, Value> = self
                .server
                .env
                .iter()
                .map(|(k, v)| ((*k).to_string(), json!(v)))
                .collect();
            m.insert("env".into(), Value::Object(env));
        }
        Value::Object(m)
    }

    /// `entry` without every user key, whatever its value: what is left must match rtok's.
    pub fn without_user_keys(&self, entry: &Value) -> Value {
        self.strip(entry, |_, _| true)
    }

    /// `entry` without the user keys still at their seed, so one the user changed stays and
    /// shows up as a difference.
    pub fn without_seeded_keys(&self, entry: &Value) -> Value {
        self.strip(entry, |have, seed| have == seed)
    }

    fn strip(&self, entry: &Value, drop: impl Fn(&Value, &Value) -> bool) -> Value {
        let mut out = entry.clone();
        if let Some(m) = out.as_object_mut() {
            for (key, seed) in &self.user_keys {
                if m.get(*key).is_some_and(|have| drop(have, seed)) {
                    m.remove(*key);
                }
            }
        }
        out
    }
}
