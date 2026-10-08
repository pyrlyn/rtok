// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T71.3 / T155: size limits for every shipped skill (`skills/<name>/SKILL.md`), and the
//! `rtok-worktrees` skill names only commands `rtok worktree` really has, and
//! (T234) no host plugin bundles a copy of a skill.

mod common;

use clap::CommandFactory;
use common::agents::{rtok, tmp, write_cfg};
use rtok::agents::skill::{SKILL_DESC_MAX, SKILLS};
use rtok::cli::Cli;
use std::fs;
use std::path::PathBuf;

fn hub_skill(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(format!("skills/{name}/SKILL.md"));
    fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()))
}

fn split_frontmatter(content: &str) -> (&str, &str) {
    let content = content.strip_prefix("---\n").expect("opening ---");
    let (front, body) = content.split_once("\n---\n").expect("closing ---");
    (front, body)
}

fn description(front: &str) -> String {
    front
        .lines()
        .find_map(|l| l.strip_prefix("description: "))
        .expect("description:")
        .to_string()
}

#[test]
fn every_skill_description_is_at_most_120_chars() {
    for name in SKILLS {
        let content = hub_skill(name);
        let (front, _) = split_frontmatter(&content);
        let desc = description(front);
        assert!(
            desc.chars().count() <= SKILL_DESC_MAX,
            "{name}: description is {} chars: {desc}",
            desc.chars().count()
        );
        assert!(
            front.lines().any(|l| l == format!("name: {name}")),
            "{name}: frontmatter name must match the directory"
        );
    }
}

#[test]
fn every_skill_body_is_at_most_2kb_and_has_no_disable_model_invocation() {
    for name in SKILLS {
        let content = hub_skill(name);
        let (front, body) = split_frontmatter(&content);
        assert!(
            body.len() <= 2048,
            "{name}: body is {} bytes (limit 2048)",
            body.len()
        );
        assert!(
            !front.contains("disable-model-invocation"),
            "{name}: hub skill must stay model-invokable"
        );
    }
}

/// T155: every inline `rtok worktree <sub> … --flag …` span in the skill resolves against the
/// real CLI, so the skill cannot teach a command or flag that `rtok worktree --help` lacks.
#[test]
fn worktrees_skill_names_only_commands_rtok_worktree_has() {
    let cli = Cli::command();
    let worktree = cli.find_subcommand("worktree").expect("rtok worktree");
    let content = hub_skill("rtok-worktrees");
    let (_, body) = split_frontmatter(&content);
    let mut seen = Vec::new();
    for span in body.split('`').skip(1).step_by(2) {
        let Some(rest) = span.strip_prefix("rtok worktree ") else {
            continue;
        };
        let mut words = rest.split_whitespace();
        let sub = words.next().expect("subcommand after `rtok worktree`");
        let cmd = worktree
            .find_subcommand(sub)
            .unwrap_or_else(|| panic!("`rtok worktree {sub}` is not a subcommand"));
        for flag in words.filter_map(|w| w.strip_prefix("--")) {
            assert!(
                cmd.get_arguments().any(|a| a.get_long() == Some(flag)),
                "`rtok worktree {sub}` has no `--{flag}`"
            );
        }
        seen.push(sub);
    }
    for sub in ["add", "adopt", "list", "gc", "clean"] {
        assert!(
            seen.contains(&sub),
            "the skill must show `rtok worktree {sub}`"
        );
    }
}

/// T380: the hub skill keeps the name `rtok`; every other shipped skill is `rtok-<name>`.
#[test]
fn shipped_skills_except_the_hub_start_with_rtok_prefix() {
    assert!(SKILLS.contains(&"rtok"));
    for name in SKILLS {
        if *name == "rtok" {
            continue;
        }
        assert!(
            name.starts_with("rtok-"),
            "{name} must be prefixed so it cannot collide with a user's skill"
        );
    }
}

/// T155: one install copies every shipped skill under the host's skill root and one remove
/// takes them all away (codex: no plugin offer, so no flags).
#[test]
fn install_copies_every_skill_and_remove_takes_them_away() {
    let home = tmp("skills-e2e");
    let cfg = write_cfg(&home);
    let root = home.join(".codex/skills");
    let out = rtok(&["agents", "install", "codex"], &cfg, &home);
    for name in SKILLS {
        assert!(root.join(name).join("SKILL.md").is_file(), "{name}: {out}");
        assert!(out.contains(&format!("skills/{name}")), "{name}: {out}");
    }
    let out = rtok(&["agents", "remove", "codex"], &cfg, &home);
    for name in SKILLS {
        assert!(!root.join(name).exists(), "{name}: {out}");
    }
}

/// T380: install removes a legacy marked `worktrees`, leaves a foreign `worktrees` symlink
/// (and a stray `rtok-x` directory) and still copies the prefixed skills.
#[test]
fn install_retires_our_legacy_worktrees_and_leaves_a_foreign_one() {
    let home = tmp("skills-migrate");
    let cfg = write_cfg(&home);

    let codex = home.join(".codex/skills");
    let legacy = codex.join("worktrees");
    fs::create_dir_all(&legacy).unwrap();
    fs::write(legacy.join("SKILL.md"), "old skill\n").unwrap();
    fs::write(legacy.join(".rtok-owned"), "").unwrap();
    let stray = codex.join("rtok-x");
    fs::create_dir_all(&stray).unwrap();
    fs::write(stray.join("SKILL.md"), "stray\n").unwrap();

    let out = rtok(&["agents", "install", "codex"], &cfg, &home);
    assert!(codex.join("rtok/SKILL.md").is_file(), "{out}");
    assert!(codex.join("rtok-worktrees/SKILL.md").is_file(), "{out}");
    assert!(!legacy.exists(), "marked legacy worktrees must go: {out}");
    assert!(
        out.lines().any(|l| {
            let l = l.replace('\\', "/");
            l.starts_with("- skill ") && l.ends_with("/skills/worktrees")
        }),
        "{out}"
    );
    assert_eq!(
        fs::read_to_string(stray.join("SKILL.md")).unwrap(),
        "stray\n"
    );
    assert!(
        !out.contains("rtok-x"),
        "a stray prefixed directory we do not ship is not a destination: {out}"
    );

    #[cfg(unix)]
    {
        // Cursor's installer does not copy skills; Command Code does, into its own root.
        let cc = home.join(".commandcode/skills");
        fs::create_dir_all(&cc).unwrap();
        let personal = home.join("personal-worktrees");
        fs::create_dir_all(&personal).unwrap();
        fs::write(personal.join("SKILL.md"), "mine\n").unwrap();
        let link = cc.join("worktrees");
        std::os::unix::fs::symlink(&personal, &link).unwrap();
        let out = rtok(&["agents", "install", "commandcode"], &cfg, &home);
        assert!(
            link.symlink_metadata().unwrap().file_type().is_symlink(),
            "{out}"
        );
        assert_eq!(
            fs::read_to_string(personal.join("SKILL.md")).unwrap(),
            "mine\n"
        );
        assert!(out.contains(&format!("leave {}", link.display())), "{out}");
        assert!(cc.join("rtok-worktrees/SKILL.md").is_file(), "{out}");
        assert!(cc.join("rtok/SKILL.md").is_file(), "{out}");
    }

    let _ = fs::remove_dir_all(&home);
}

/// T234: a skill lives only in `skills/<name>/`. Host plugins get it from `rtok agents install`
/// (`skill::sync`), never as a bundled copy that drifts from the hub.
#[test]
fn no_host_plugin_bundles_a_skill() {
    let plugins = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("plugins");
    let copies: Vec<_> = ignore::WalkBuilder::new(&plugins)
        .build()
        .filter_map(Result::ok)
        .filter(|e| e.file_name() == "SKILL.md")
        .map(|e| e.into_path())
        .collect();
    assert!(
        copies.is_empty(),
        "skills belong in skills/<name>/ only: {copies:?}"
    );
}
