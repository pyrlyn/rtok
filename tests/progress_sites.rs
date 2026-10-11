// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T276.4: `rtok bench` and `rtok run` go through `ProgressRunner`, and on pipes (how agents and
//! CI see rtok) their stdout and stderr stay what they were: no spinner or bar bytes, the same
//! output. Every child here is an `sh` script in a temp dir; no real agent and no host process.

#![cfg(unix)]

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::time::{Duration, Instant};

const ESC: char = '\u{1b}';

fn dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("rtok-t276-4-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

fn rtok(dir: &Path, args: &[&str], env: &[(&str, &str)]) -> Output {
    let mut cmd = Command::new(env!("CARGO_BIN_EXE_rtok"));
    cmd.args(args)
        .current_dir(dir)
        .env("RTOK_HOME", dir)
        .env("HOME", dir)
        .env_remove("RTOK_NO_PROGRESS")
        .env_remove("RTOK_BENCH_LIVE");
    cmd.envs(env.iter().copied()).output().unwrap()
}

fn text(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}

/// A live bench with a fake `claude` on `PATH`: each run is a child of the runner, the check's
/// own output still reaches stdout, the table is unchanged, and stderr stays empty.
#[test]
fn a_live_bench_on_a_pipe_prints_the_table_and_nothing_on_stderr() {
    let d = dir("bench");
    fs::create_dir_all(d.join("bench/configs")).unwrap();
    fs::write(d.join("bench/configs/legacy.json"), "{}").unwrap();
    fs::write(d.join("bench/configs/rtok.json"), "{}").unwrap();
    // The loader insists on six tasks.
    let tasks: String = (1..=6)
        .map(|i| format!("[[tasks]]\nid = \"t{i}\"\nprompt = \"p\"\ncheck = \"echo CHECKED\"\n"))
        .collect();
    fs::write(d.join("tasks.toml"), tasks).unwrap();
    fs::create_dir_all(d.join("bin")).unwrap();
    let claude = d.join("bin/claude");
    fs::write(
        &claude,
        "#!/bin/sh\necho 'fake claude noise' >&2\n\
         printf '{\"usage\":{\"input_tokens\":10,\"output_tokens\":5},\"total_cost_usd\":0.5}'\n",
    )
    .unwrap();
    fs::set_permissions(&claude, fs::Permissions::from_mode(0o755)).unwrap();
    let path = format!("{}:/usr/bin:/bin", d.join("bin").display());
    let out = rtok(
        &d,
        &["bench", "--tasks", "tasks.toml", "--runs", "2"],
        &[("PATH", &path), ("RTOK_BENCH_LIVE", "1")],
    );
    let (so, se) = (text(&out.stdout), text(&out.stderr));
    assert!(out.status.success(), "{so}{se}");
    assert_eq!(se, "", "stderr must stay empty on a pipe");
    assert!(!so.contains(ESC), "{so:?}");
    // One check per task and config.
    assert_eq!(so.matches("CHECKED").count(), 12, "{so}");
    let row = so.lines().find(|l| l.starts_with("a ")).unwrap();
    assert!(row.contains("10") && row.contains("5"), "{so}");
    assert!(
        !so.contains("fake claude noise"),
        "child stderr is dropped: {so}"
    );
    let _ = fs::remove_dir_all(&d);
}

/// `rtok run` prints the command's merged output and exit code as before; a pipe sees no
/// control bytes or `running …` text.
#[test]
fn rtok_run_on_a_pipe_prints_only_the_command_output() {
    let d = dir("run");
    let out = rtok(&d, &["run", "--", "echo out; echo err >&2; exit 3"], &[]);
    let (so, se) = (text(&out.stdout), text(&out.stderr));
    assert_eq!(out.status.code(), Some(3), "{so}{se}");
    assert_eq!(so, "out\nerr\n");
    assert_eq!(se, "");
    let _ = fs::remove_dir_all(&d);
}

/// T235.1 still holds on the runner: a backgrounded process keeps the pipes open, and `rtok run`
/// returns when the shell exits.
#[test]
fn rtok_run_returns_though_a_background_child_holds_the_pipe() {
    let d = dir("run-bg");
    let start = Instant::now();
    let out = rtok(&d, &["run", "--", "sleep 8 & echo started"], &[]);
    assert!(start.elapsed() < Duration::from_secs(6));
    assert_eq!(text(&out.stdout), "started\n");
    assert_eq!(text(&out.stderr), "");
    let _ = fs::remove_dir_all(&d);
}
