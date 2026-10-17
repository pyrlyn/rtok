// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Re-index jobs of this process (T329.54): the `indexing` and `failed` states of a project row.
//! The registry stores no index status, so a job exists only in the process that started it —
//! the `rtok web` server or the TUI — which is also the process that draws the row. The CLI
//! blocks on [`super::projects::Action::Reindex`] and needs no tracker.

use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{LazyLock, Mutex, PoisonError};

use anyhow::{Result, bail};

use super::projects::{Action, resolve, run};
use crate::config::Config;
use crate::plugin::Runtime;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Job {
    Running,
    /// The last run's error; it stays until the next run starts, so a failure is not missed
    /// between two snapshots.
    Failed(String),
}

// Keyed by root, not id: ids repeat across stores, and one process can open several.
static JOBS: LazyLock<Mutex<HashMap<String, Job>>> = LazyLock::new(Mutex::default);

fn jobs() -> std::sync::MutexGuard<'static, HashMap<String, Job>> {
    JOBS.lock().unwrap_or_else(PoisonError::into_inner)
}

pub fn get(root: &str) -> Option<Job> {
    jobs().get(root).cloned()
}

fn finish(root: &str, outcome: Result<String>) {
    match outcome {
        Ok(_) => jobs().remove(root),
        Err(e) => jobs().insert(root.to_string(), Job::Failed(format!("{e:#}"))),
    };
}

/// Re-indexes `target` on a worker thread and returns at once, so the caller's loop (the web
/// socket, the TUI keys) stays alive. The worker runs the function `rtok graph projects reindex`
/// runs. Until it ends the old rows keep answering: the index replaces them file by file.
pub fn start(rt: &Runtime, target: &str) -> Result<String> {
    let p = resolve(&rt.store, target)?;
    if p.missing() {
        bail!(
            "{} no longer exists; remove it or restore the directory",
            p.root
        );
    }
    {
        let mut all = jobs();
        if all.get(&p.root) == Some(&Job::Running) {
            bail!("{} is already being indexed", p.root);
        }
        all.insert(p.root.clone(), Job::Running);
    }
    let (config, root, id) = (rt.config.clone(), p.root.clone(), p.id.to_string());
    let spawned = std::thread::Builder::new()
        .name("graph-reindex".into())
        .spawn({
            let root = root.clone();
            move || {
                // A panic must not leave the row `indexing` for good.
                let outcome = catch_unwind(AssertUnwindSafe(|| work(config, id)))
                    .unwrap_or_else(|_| Err(anyhow::anyhow!("the index worker stopped")));
                finish(&root, outcome);
            }
        });
    if let Err(e) = spawned {
        jobs().remove(&root);
        bail!("cannot start the index worker: {e}");
    }
    Ok(format!("indexing {root}\n"))
}

fn work(config: Config, id: String) -> Result<String> {
    let rt = Runtime::open(config, "graph-reindex")?;
    run(&rt, Action::Reindex(id), false)
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;
    use std::time::{Duration, Instant};

    use super::*;
    use crate::plugins::graph::projects::{ProjectRow, rows};
    use crate::store::Origin;

    fn project(tag: &str) -> (Runtime, PathBuf) {
        let (c, dir) = crate::testutil::config(tag);
        let root = dir.join("proj");
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(root.join("lib.rs"), "pub fn alpha() {}\npub fn beta() {}\n").unwrap();
        let rt = Runtime::open(c, tag).unwrap();
        rt.store.register_project(&root, Origin::Manual).unwrap();
        (rt, root)
    }

    fn only(rt: &Runtime) -> ProjectRow {
        rows(rt).unwrap().remove(0)
    }

    fn wait_done(key: &str) {
        let end = Instant::now() + Duration::from_secs(60);
        while get(key) == Some(Job::Running) {
            assert!(Instant::now() < end, "the index worker never finished");
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    #[test]
    fn the_row_shows_indexing_then_failed_then_clears() {
        let (rt, _) = project("t32954-states");
        let key = only(&rt).root;
        assert_eq!(only(&rt).state, "not indexed");

        // The old rows stay readable while a run replaces them.
        run(&rt, Action::Reindex("1".into()), false).unwrap();
        let before = only(&rt).index.unwrap().rows;
        assert!(before >= 2);
        jobs().insert(key.clone(), Job::Running);
        let running = only(&rt);
        assert_eq!(running.state, "indexing");
        assert_eq!(running.index.unwrap().rows, before);
        jobs().remove(&key);
        assert_eq!(only(&rt).state, "ok");
        jobs().insert(key.clone(), Job::Running);

        finish(&key, Err(anyhow::anyhow!("disk full")));
        let failed = only(&rt);
        assert_eq!(failed.state, "failed");
        assert_eq!(failed.error.as_deref(), Some("disk full"));
        let json = serde_json::to_value(&failed).unwrap();
        assert_eq!(json["error"], "disk full");

        finish(&key, Ok(String::new()));
        assert_eq!(only(&rt).state, "ok");
        assert!(
            serde_json::to_value(only(&rt))
                .unwrap()
                .get("error")
                .is_none()
        );
    }

    #[test]
    fn start_indexes_on_a_worker_and_leaves_the_cli_result() {
        let (rt, _) = project("t32954-worker");
        assert_eq!(only(&rt).state, "not indexed");
        let out = start(&rt, "1").unwrap();
        assert!(out.starts_with("indexing "), "{out}");
        wait_done(&only(&rt).root);
        let web = only(&rt);
        assert_eq!(web.state, "ok", "{web:?}");

        // The blocking command on an identical project in its own store.
        let (cli_rt, _) = project("t32954-cli");
        let cli_out = run(&cli_rt, Action::Reindex("1".into()), false).unwrap();
        assert!(cli_out.contains("indexed"), "{cli_out}");
        let cli = only(&cli_rt);
        assert_eq!(cli.state, "ok");
        let (a, b) = (web.index.unwrap(), cli.index.unwrap());
        assert_eq!((a.rows, a.files), (b.rows, b.files));
        assert!(a.rows >= 2, "both functions are indexed");
    }

    #[test]
    fn a_second_start_while_running_and_a_missing_root_are_refused() {
        let (rt, root) = project("t32954-refuse");
        let key = only(&rt).root;
        jobs().insert(key.clone(), Job::Running);
        let err = start(&rt, "1").unwrap_err().to_string();
        assert!(err.contains("already being indexed"), "{err}");
        jobs().remove(&key);

        std::fs::remove_dir_all(&root).unwrap();
        let err = start(&rt, "1").unwrap_err().to_string();
        assert!(err.contains("no longer exists"), "{err}");
        assert_eq!(get(&key), None, "a refused start leaves no job behind");
    }
}
