// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `env.ANTHROPIC_BASE_URL` in Claude settings.json (plan T5.2).
//!
//! `rtok agents install claude --proxy` writes it and uninstall clears it.
//! The helpers live with the other host installers so `agents` does not
//! depend on the proxy surface.

use anyhow::Result;
use rtok_agent_sdk::{NO_CHANGES, edit_json, object_at};
use serde_json::{Value, json};

use super::{anthropic_proxy_url, apply};
use crate::config::Config;

/// Set `env.ANTHROPIC_BASE_URL` in Claude settings.json to this proxy (backup).
pub fn register_proxy(cfg: &Config) -> Result<String> {
    let url = anthropic_proxy_url(cfg);
    edit_json(&apply(cfg), &cfg.setup.claude.settings_path, |root| {
        let want = json!(url);
        let env = object_at(root, "env");
        let prev = env.get("ANTHROPIC_BASE_URL").cloned();
        if prev.as_ref() == Some(&want) {
            return NO_CHANGES.into();
        }
        env["ANTHROPIC_BASE_URL"] = want;
        let revert = match prev.and_then(|v| v.as_str().map(str::to_string)) {
            Some(old) => format!("revert: set env.ANTHROPIC_BASE_URL to {old}"),
            None => "revert: remove env.ANTHROPIC_BASE_URL".into(),
        };
        format!("env.ANTHROPIC_BASE_URL: {url}\n{revert}")
    })
}

/// Clear `env.ANTHROPIC_BASE_URL` (`rtok agents uninstall claude`), but only while it still
/// points at this proxy — a URL the user set themselves is not ours to delete.
pub fn unregister_proxy(cfg: &Config) -> Result<String> {
    let url = anthropic_proxy_url(cfg);
    edit_json(&apply(cfg), &cfg.setup.claude.settings_path, |root| {
        let Some(env) = root.get_mut("env").and_then(Value::as_object_mut) else {
            return NO_CHANGES.into();
        };
        if env.get("ANTHROPIC_BASE_URL").and_then(Value::as_str) != Some(url.as_str()) {
            return NO_CHANGES.into();
        }
        env.remove("ANTHROPIC_BASE_URL");
        if env.is_empty() {
            root.as_object_mut().unwrap().remove("env");
        }
        "- env.ANTHROPIC_BASE_URL".into()
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn proxy_env_dry_run_then_apply_is_idempotent() {
        let dir = std::env::temp_dir().join(format!("rtok-proxy-setup-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("settings.json");
        let mut c = Config::default();
        c.setup.claude.settings_path = path.clone();
        c.setup.backup = false;
        c.setup.dry_run = true;
        let dry = register_proxy(&c).unwrap();
        assert!(dry.contains("ANTHROPIC_BASE_URL"), "{dry}");
        assert!(dry.contains("revert:"), "{dry}");
        assert!(!path.exists());
        c.setup.dry_run = false;
        let first = register_proxy(&c).unwrap();
        assert!(first.contains("8790"), "{first}");
        assert_eq!(register_proxy(&c).unwrap(), "no changes");
        let raw = fs::read_to_string(&path).unwrap();
        assert!(raw.contains("127.0.0.1:8790"), "{raw}");
        let _ = fs::remove_dir_all(dir);
    }
}
