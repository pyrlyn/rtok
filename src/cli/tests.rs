// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

use std::io::Read;

use clap::CommandFactory;

use super::Cli;
use super::util::read_lossy;

#[test]
fn read_lossy_keeps_bytes_around_invalid_utf8() {
    assert_eq!(read_lossy(&b"a\xffb\n"[..]).unwrap(), "a\u{FFFD}b\n");
}

#[test]
fn read_lossy_surfaces_read_errors() {
    struct Broken;
    impl Read for Broken {
        fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
            Err(std::io::Error::other("boom"))
        }
    }
    assert!(read_lossy(Broken).is_err());
}

#[test]
fn clink_covers_every_rtok_subcommand_and_parses() {
    let mut out = Vec::new();
    crate::completions::generate(crate::completions::Shell::Clink, Cli::command(), &mut out);
    let lua = String::from_utf8(out).unwrap();
    for sub in Cli::command()
        .get_subcommands()
        .filter(|s| !s.is_hide_set())
    {
        assert!(
            lua.contains(&format!("\"{}\" ..", sub.get_name())),
            "{}",
            sub.get_name()
        );
    }
    // Syntax check where a working Lua compiler is on PATH (CI and dev machines may lack
    // one; a version-manager shim with no version set fails `-v` and counts as absent).
    let luac = |args: &[&std::ffi::OsStr]| std::process::Command::new("luac").args(args).output();
    if !luac(&["-v".as_ref()]).is_ok_and(|o| o.status.success()) {
        return;
    }
    let file = crate::testutil::tmp_dir("clink").join("rtok.lua");
    std::fs::write(&file, &lua).unwrap();
    let out = luac(&["-p".as_ref(), file.as_os_str()]).unwrap();
    assert!(
        out.status.success(),
        "luac: {}",
        String::from_utf8_lossy(&out.stderr)
    );
}

fn man_names(cmd: &clap::Command, prefix: &str, out: &mut Vec<String>) {
    let page = if prefix.is_empty() {
        cmd.get_name().to_string()
    } else {
        format!("{prefix}-{}", cmd.get_name())
    };
    for sub in cmd.get_subcommands().filter(|s| !s.is_hide_set()) {
        if sub.get_name() != "help" {
            man_names(sub, &page, out);
        }
    }
    out.push(format!("{page}.1"));
}

#[test]
fn one_page_per_visible_subcommand() {
    let dir = crate::testutil::tmp_dir("man");
    let written = crate::man::write_all(Cli::command(), &dir).unwrap();
    let mut got: Vec<String> = written
        .iter()
        .map(|p| p.file_name().unwrap().to_string_lossy().into_owned())
        .collect();
    let mut want = Vec::new();
    man_names(&Cli::command(), "", &mut want);
    got.sort();
    want.sort();
    assert_eq!(got, want);
    assert!(
        got.contains(&"rtok-agents-install.1".to_string()),
        "{got:?}"
    );
}

#[test]
fn see_also_links_parent_and_children() {
    let dir = crate::testutil::tmp_dir("man");
    crate::man::write_all(Cli::command(), &dir).unwrap();
    let agents = std::fs::read_to_string(dir.join("rtok-agents.1")).unwrap();
    let see = agents.split(".SH \"SEE ALSO\"").nth(1).expect("SEE ALSO");
    assert!(see.contains("\\fBrtok\\fR(1)"), "{see}");
    assert!(see.contains("\\fBrtok-agents-install\\fR(1)"), "{see}");
    assert!(
        agents.contains(".TH rtok-agents 1  \"rtok "),
        "shared source: {agents}"
    );
    let top = std::fs::read_to_string(dir.join("rtok.1")).unwrap();
    assert!(top.starts_with(".ie \\n(.g .ds Aq"), "roff header");
}
