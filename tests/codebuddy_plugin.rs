// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T560.1 + D21: the CodeBuddy Code / WorkBuddy plugin tree carries a `.codebuddy-plugin`
//! manifest and marketplace, Claude-shaped `hooks/hooks.json` that run `rtok hook <Event> --host
//! codebuddy`, and an `.mcp.json` for `rtok mcp --host codebuddy`. The installer is T560.2.

use std::fs;
use std::path::PathBuf;

use serde_json::{Value, json};

fn root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("plugins/codebuddy")
}

fn read(name: &str) -> Value {
    let path = root().join(name);
    let text = fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

#[test]
fn manifest_and_marketplace_name_the_rtok_plugin() {
    let m = read(".codebuddy-plugin/plugin.json");
    assert_eq!(m["name"], "rtok");
    assert_eq!(m["version"], env!("CARGO_PKG_VERSION"));
    let mp = read(".codebuddy-plugin/marketplace.json");
    assert_eq!(mp["name"], "rtok");
    assert_eq!(mp["owner"]["name"], "pyrlyn");
    assert_eq!(
        mp["plugins"],
        json!([{
            "name": "rtok",
            "source": "./",
            "description": "rtok hooks and MCP for CodeBuddy Code and WorkBuddy"
        }])
    );
}

#[test]
fn mcp_json_runs_rtok_mcp_for_the_codebuddy_host() {
    assert_eq!(
        read(".mcp.json"),
        json!({"mcpServers": {"rtok": {
            "type": "stdio",
            "command": "rtok",
            "args": ["mcp", "--host", "codebuddy"]
        }}})
    );
}

#[test]
fn hooks_cover_the_five_events_and_all_name_the_codebuddy_host() {
    let doc = read("hooks/hooks.json");
    let hooks = doc["hooks"].as_object().unwrap();
    let mut events: Vec<_> = hooks.keys().map(String::as_str).collect();
    events.sort_unstable();
    assert_eq!(
        events,
        [
            "PostToolUse",
            "PreToolUse",
            "SessionStart",
            "Stop",
            "UserPromptSubmit"
        ]
    );
    assert_eq!(
        hooks["PreToolUse"][0]["matcher"],
        "Bash|Read|Edit|Write|Grep|Glob"
    );
    for (event, entries) in hooks {
        let hook = &entries[0]["hooks"][0];
        let cmd = hook["command"].as_str().unwrap();
        assert!(
            cmd.contains(&format!("rtok hook {event} --host codebuddy")),
            "{event}: {cmd}"
        );
        assert!(
            cmd.contains(&format!(
                "\"${{CODEBUDDY_PLUGIN_ROOT}}/scripts/hook.sh\" {event}"
            )),
            "{event}: {cmd}"
        );
        assert_eq!(hook["type"], "command");
        assert_eq!(hook["timeout"], 5);
    }
}

#[cfg(unix)]
mod launcher {
    use super::*;
    use std::os::unix::fs::PermissionsExt;
    use std::path::Path;
    use std::process::Command;

    /// A directory holding an `rtok` shim that prints its arguments.
    fn shim_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("rtok-t560-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let shim = dir.join("rtok");
        fs::write(&shim, "#!/bin/sh\necho \"$@\"\n").unwrap();
        fs::set_permissions(&shim, fs::Permissions::from_mode(0o755)).unwrap();
        dir
    }

    fn run(cmd: &str, path: &Path, home: &Path) -> (bool, String) {
        let out = Command::new("/bin/sh")
            .args(["-c", cmd])
            .env("PATH", format!("{}:/usr/bin:/bin", path.display()))
            .env("HOME", home)
            .env("CODEBUDDY_PLUGIN_ROOT", root())
            .output()
            .unwrap();
        (
            out.status.success(),
            String::from_utf8_lossy(&out.stdout).into_owned(),
        )
    }

    #[test]
    fn every_hook_command_execs_rtok_with_the_codebuddy_host() {
        let dir = shim_dir("path");
        let doc = read("hooks/hooks.json");
        for (event, entries) in doc["hooks"].as_object().unwrap() {
            let cmd = entries[0]["hooks"][0]["command"].as_str().unwrap();
            let (ok, stdout) = run(cmd, &dir, &dir);
            assert!(ok, "{event}");
            assert_eq!(stdout.trim(), format!("hook {event} --host codebuddy"));
        }
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_launcher_falls_back_to_the_ketch_store_and_fails_open_without_rtok() {
        let script = root().join("scripts/hook.sh");
        let launch = |home: &Path, event: &str| {
            run(
                &format!("\"{}\" {event}", script.display()),
                Path::new("/nonexistent"),
                home,
            )
        };

        let home = shim_dir("ketch");
        fs::create_dir_all(home.join(".ketch/bin")).unwrap();
        fs::rename(home.join("rtok"), home.join(".ketch/bin/rtok")).unwrap();
        assert_eq!(
            launch(&home, "Stop"),
            (true, "hook Stop --host codebuddy\n".into())
        );
        let _ = fs::remove_dir_all(&home);

        // Another install location on the machine would make "missing" untestable.
        if ["/usr/local/bin/rtok", "/opt/homebrew/bin/rtok"]
            .iter()
            .any(|p| Path::new(p).exists())
        {
            return;
        }
        let empty = std::env::temp_dir().join(format!("rtok-t560-empty-{}", std::process::id()));
        fs::create_dir_all(&empty).unwrap();
        assert_eq!(launch(&empty, "PreToolUse"), (true, String::new()));
        let (ok, start) = launch(&empty, "SessionStart");
        assert!(ok);
        assert!(start.contains("ketch install pyrlyn/rtok"), "{start}");
        assert!(start.contains("additionalContext"), "{start}");
        let _ = fs::remove_dir_all(&empty);
    }
}
