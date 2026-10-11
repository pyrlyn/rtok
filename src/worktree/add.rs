// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok worktree add` (T158): the one creation path — one location, one name, one
//! owner. [`plan`] decides everything from paths and strings; [`run`] is one fetch and
//! one `git worktree add`.

use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail, ensure};

use super::{Owner, git};

#[derive(Debug, PartialEq, Eq)]
pub struct Plan {
    pub path: PathBuf,
    pub branch: String,
    pub task: String,
    /// The lock reason, `<owner> | <task-id> | <date>[ | agent <uuid>]` — what
    /// [`Owner::parse`] reads back.
    pub reason: String,
}

/// Lower-cased, then `[a-z0-9][a-z0-9._-]*` minus what git refuses in a ref name.
fn ident(what: &str, raw: &str) -> Result<String> {
    let id = raw.to_ascii_lowercase();
    let allowed = |c: char| c.is_ascii_lowercase() || c.is_ascii_digit() || "._-".contains(c);
    let ok = id.starts_with(|c: char| c.is_ascii_alphanumeric())
        && id.chars().all(allowed)
        && !id.contains("..")
        && !id.ends_with('.')
        && !id.ends_with(".lock");
    ensure!(
        ok,
        "{what} `{raw}` must match [a-z0-9][a-z0-9._-]* and be a valid branch name"
    );
    Ok(id)
}

/// Agents pass `T504 read-hook-same-path` the way the branch is named: one argument.
/// The first token is the task id, the rest the slug. Spaces in a slug become dashes.
/// A bare id is unchanged; a separate `slug` is joined after the rest.
fn split_id_slug<'a>(task: &'a str, slug: Option<&str>) -> (&'a str, Option<String>) {
    let task = task.trim();
    let (id, rest) = task.split_once(char::is_whitespace).unwrap_or((task, ""));
    let dashed = |s: &str| {
        let d = s.split_whitespace().collect::<Vec<_>>().join("-");
        (!d.is_empty()).then_some(d)
    };
    let slug = match (dashed(rest), slug.and_then(dashed)) {
        (Some(rest), Some(slug)) => Some(format!("{rest}-{slug}")),
        (rest, slug) => rest.or(slug),
    };
    (id, slug)
}

/// Symlinks resolved through the deepest ancestor that exists, the rest appended: a root
/// that does not exist yet still has to be judged by where it would land.
fn resolved(path: &Path) -> PathBuf {
    // `dunce`: std canonicalize's `\\?\` prefix makes `starts_with` miss the temp dir.
    let real = |a: &Path| {
        let canon = dunce::canonicalize(a).ok()?;
        let rel = path.strip_prefix(a).ok()?;
        Some(canon.join(rel))
    };
    let landed = path.ancestors().find_map(real);
    landed.unwrap_or_else(|| crate::fs::canon(path))
}

/// `temp` lists the directories the OS may purge; a worktree under one of them loses
/// its files while git keeps the (locked, never pruned) record.
pub fn plan(
    main: &Path,
    root: &Path,
    temp: &[PathBuf],
    (task, slug): (&str, Option<&str>),
    (owner, agent): (&str, Option<&str>),
    date: &str,
) -> Result<Plan> {
    let (task, slug) = split_id_slug(task, slug);
    let task = ident("task id", task)?;
    let branch = match slug {
        Some(slug) => format!("{task}-{}", ident("slug", &slug)?),
        None => task.clone(),
    };
    let reason = Owner {
        owner: owner.into(),
        task: task.clone(),
        date: date.into(),
        agent: agent.map(Into::into),
    }
    .reason();
    // ASCII: `git worktree list --porcelain` C-quotes anything else.
    let round_trips = Owner::parse(&reason).is_some_and(|o| o.owner == owner);
    ensure!(
        owner.is_ascii() && round_trips && !owner.contains(['\n', '\r']),
        "--owner `{owner}` must be non-empty ASCII without ` | `, e.g. \"Claude Code / sonnet\""
    );
    ensure!(
        !root.as_os_str().is_empty(),
        "`[worktree] root` is empty; set it or drop the key for ~/.rtok/worktrees"
    );
    let (landing, checkout) = (resolved(root), resolved(main));
    // A repository that itself lives under the temp directory (a test fixture, a scratch
    // clone) is no worse off with its worktrees beside it.
    let purgeable = |t: &PathBuf| {
        crate::fs::path_starts_with(&landing, t) && !crate::fs::path_starts_with(&checkout, t)
    };
    if let Some(tmp) = temp.iter().map(|t| resolved(t)).find(purgeable) {
        bail!(
            "worktree root {} is under the temporary directory {}; set `[worktree] root`",
            root.display(),
            tmp.display()
        );
    }
    let repo = main
        .file_name()
        .context("the main checkout has no directory name")?;
    // Path is `<repo>-<task>`: an existing directory is this task's worktree (a retry).
    // Hand the same plan back rather than fail on "one worktree per task".
    let path = root.join(format!("{}-{task}", repo.to_string_lossy()));
    Ok(Plan {
        path,
        branch,
        task,
        reason,
    })
}

/// `YYYY-MM-DD`, UTC: the lock reason's date.
pub(super) fn today() -> Result<String> {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?;
    Ok(crate::log::stamp(now.as_secs())[..10].to_string())
}

/// Creates the worktree and returns its plan. A failed fetch is an error: branching
/// from a stale base is how a finished task gets rebuilt on old code.
pub fn run(
    cwd: &Path,
    root: &Path,
    id: (&str, Option<&str>),
    owner: (&str, Option<&str>),
) -> Result<Plan> {
    let worktrees = git::list(cwd)?;
    let main = &worktrees.first().context("git lists no worktree")?.path;
    let temp = [std::env::temp_dir(), "/tmp".into()];
    let plan = plan(main, root, &temp, id, owner, &today()?)?;
    // A retry of an add that already landed: the worktree is already there.
    if plan.path.exists() {
        return Ok(plan);
    }
    let base = git::default_base(main);
    git::fetch(main, &base)?;
    if let Some(root) = plan.path.parent() {
        std::fs::create_dir_all(root)
            .with_context(|| format!("cannot create {}", root.display()))?;
    }
    git::add_locked(main, &plan.path, &plan.branch, &plan.reason, &base)?;
    Ok(plan)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::tmp_dir;
    use rstest::rstest;

    const OWNER: &str = "Claude Code / sonnet";

    fn plan_in(main: &Path, root: &Path, id: (&str, Option<&str>), owner: &str) -> Result<Plan> {
        plan(
            main,
            root,
            &["/purged".into()],
            id,
            (owner, None),
            "2026-09-22",
        )
    }

    #[test]
    fn the_root_holds_the_worktree_and_the_names_follow_the_task() {
        let dir = tmp_dir("wt-add");
        let (main, root) = (dir.join("apps/rtok"), dir.join("home/.rtok/worktrees"));
        std::fs::create_dir_all(&main).unwrap();
        // T410: a `_worktrees/` beside the repository no longer pulls the worktree there.
        std::fs::create_dir_all(dir.join("_worktrees")).unwrap();
        let p = plan_in(&main, &root, ("T158", Some("Worktree-Add")), OWNER).unwrap();
        assert_eq!(p.path, root.join("rtok-t158"));
        assert_eq!(p.branch, "t158-worktree-add");
        assert_eq!(p.reason, "Claude Code / sonnet | t158 | 2026-09-22");
        let parsed = Owner::parse(&p.reason).unwrap();
        assert_eq!(
            (parsed.owner.as_str(), parsed.task.as_str()),
            (OWNER, "t158")
        );

        let empty = plan_in(&main, Path::new(""), ("t2", None), OWNER).unwrap_err();
        assert!(empty.to_string().contains("is empty"), "{empty}");
    }

    /// Agents name the branch in one argument: the first token is the id, the rest the slug.
    #[rstest]
    #[case::logged_t504("T504 read-hook-same-path", "t504", "t504-read-hook-same-path")]
    #[case::logged_m16_5("M16.5 test toml", "m16.5", "m16.5-test-toml")]
    #[case::logged_m16_3(
        "M16.3 push.rs through the config module",
        "m16.3",
        "m16.3-push.rs-through-the-config-module"
    )]
    #[case::bare_id("T504", "t504", "t504")]
    fn one_argument_is_an_id_plus_a_slug(
        #[case] arg: &str,
        #[case] task: &str,
        #[case] branch: &str,
    ) {
        let dir = tmp_dir("wt-split");
        let (main, root) = (dir.join("apps/rtok"), dir.join("home/.rtok/worktrees"));
        std::fs::create_dir_all(&main).unwrap();
        let p = plan_in(&main, &root, (arg, None), OWNER).unwrap();
        assert_eq!(p.task, task);
        assert_eq!(p.branch, branch);
    }

    #[test]
    fn spaces_in_the_slug_become_dashes() {
        let p = plan_in(
            Path::new("/r/rtok"),
            Path::new("/r/_w"),
            ("T504", Some("read hook same path")),
            OWNER,
        )
        .unwrap();
        assert_eq!(p.task, "t504");
        assert_eq!(p.branch, "t504-read-hook-same-path");
    }

    /// A retry of an add that already landed: the same task's path is still its plan.
    #[test]
    fn an_existing_path_of_the_same_task_returns_the_plan() {
        let dir = tmp_dir("wt-again");
        let (main, root) = (dir.join("apps/rtok"), dir.join("home/.rtok/worktrees"));
        std::fs::create_dir_all(&main).unwrap();
        std::fs::create_dir_all(root.join("rtok-t504")).unwrap();
        let p = plan_in(&main, &root, ("T504 read-hook-same-path", None), OWNER).unwrap();
        assert_eq!(p.path, root.join("rtok-t504"));
        assert_eq!(p.task, "t504");
        assert_eq!(p.branch, "t504-read-hook-same-path");
        let bare = plan_in(&main, &root, ("t504", None), OWNER).unwrap();
        assert_eq!(bare.path, p.path);
        assert_eq!(bare.task, "t504");
    }

    #[test]
    fn the_default_root_is_under_the_rtok_home() {
        let root = crate::config::Config::default().worktree.root;
        assert_eq!(root, Path::new("~/.rtok/worktrees"));
    }

    #[test]
    fn a_known_agent_is_the_fourth_field_of_the_lock() {
        let agent = "an-agent-id";
        let p = plan(
            Path::new("/r/rtok"),
            Path::new("/r/_w"),
            &[],
            ("t1", None),
            (OWNER, Some(agent)),
            "d",
        );
        let reason = p.unwrap().reason;
        assert_eq!(reason, format!("{OWNER} | t1 | d | agent {agent}"));
        assert_eq!(Owner::parse(&reason).unwrap().agent.as_deref(), Some(agent));
    }

    #[rstest]
    #[case::empty_id(("", None), OWNER, "task id")]
    #[case::path_in_id(("../t1", None), OWNER, "task id")]
    #[case::leading_dash(("-t1", None), OWNER, "task id")]
    #[case::ref_rule(("t1.lock", None), OWNER, "task id")]
    #[case::not_an_id_after_split(("../t1 rest", None), OWNER, "task id")]
    #[case::dash_id_after_split(("-t1 rest", None), OWNER, "task id")]
    #[case::bad_slug(("t1", Some("../evil")), OWNER, "slug")]
    #[case::no_owner(("t1", None), "", "--owner")]
    #[case::separator_in_owner(("t1", None), "a | b", "--owner")]
    #[case::non_ascii_owner(("t1", None), "Клод / sonnet", "--owner")]
    fn bad_input_is_refused_before_git_runs(
        #[case] id: (&str, Option<&str>),
        #[case] owner: &str,
        #[case] names: &str,
    ) {
        let err = plan_in(Path::new("/r/rtok"), Path::new("/r/_w"), id, owner).unwrap_err();
        assert!(err.to_string().contains(names), "{err}");
    }

    #[test]
    fn a_root_under_a_temporary_directory_is_refused_unless_the_repository_is_there_too() {
        let purged = Path::new("/purged/x");
        let err = plan_in(Path::new("/r/rtok"), purged, ("t1", None), OWNER).unwrap_err();
        assert!(err.to_string().contains("temporary directory"), "{err}");
        let beside = Path::new("/purged/session/_worktrees");
        let scratch = plan_in(
            Path::new("/purged/session/rtok"),
            beside,
            ("t1", None),
            OWNER,
        );
        assert_eq!(
            scratch.unwrap().path,
            Path::new("/purged/session/_worktrees/rtok-t1")
        );
    }

    /// T430: a missing child of a real directory does not keep `std::fs::canonicalize`'s `\\?\`.
    #[cfg(windows)]
    #[test]
    fn resolved_drops_the_verbatim_prefix() {
        let dir = tmp_dir("wt-resolved");
        let got = resolved(&dir.join("child"));
        let text = got.to_string_lossy();
        assert!(!text.starts_with(r"\\?\"), "{text}");
        assert!(got.ends_with("child"), "{got:?}");
    }
}
