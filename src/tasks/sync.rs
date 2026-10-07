// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `rtok task sync` (T441.12): bring the store's counters up to what the adapter holds and
//! report the drift that explains why they were behind. It only ever reads the adapter, so
//! running it against a shared tracker is safe; the one write is the local counter, and that
//! only goes up.

use std::collections::BTreeMap;
use std::fmt::Write as _;

use anyhow::Result;
use serde::Serialize;

use super::TaskId;
use super::adapter::{Filter, Stray};
use super::run::Project;
use crate::store::Store;

/// One counter that was behind the adapter.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Raised {
    /// `None`: the top-level counter; else the parent whose subtask counter this is.
    pub parent: Option<TaskId>,
    pub from: u32,
    pub to: u32,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct SyncReport {
    pub adapter: &'static str,
    /// The top-level counter after the sync: the next id is this plus one.
    pub counter: u32,
    pub next: TaskId,
    pub raised: Vec<Raised>,
    /// Ids numbered above their counter before the sync: made by hand, by another machine, or
    /// by a checkout whose store this one never saw.
    pub above_counter: Vec<TaskId>,
    /// Ids that more than one task claims; `create` refuses taken ids, so these came from outside.
    pub duplicates: Vec<TaskId>,
    /// Issues that kept rtok's label but lost their `rtok:<id>` one.
    pub unlabelled: Vec<Stray>,
}

impl SyncReport {
    pub fn is_clean(&self) -> bool {
        self.above_counter.is_empty() && self.duplicates.is_empty() && self.unlabelled.is_empty()
    }
}

fn number(id: &TaskId) -> u32 {
    id.path().last().copied().unwrap_or(0)
}

fn join(ids: &[TaskId]) -> String {
    let ids: Vec<String> = ids.iter().map(ToString::to_string).collect();
    ids.join(", ")
}

/// Raise every counter of `project` to the highest number the adapter holds under it, and say
/// what was off. Done tasks count too: their numbers were handed out and must not come back.
pub fn sync(project: &Project, store: &Store) -> Result<SyncReport> {
    let adapter = project.adapter();
    let tasks = adapter.list(&Filter {
        all: true,
        ..Filter::default()
    })?;

    let mut duplicates: Vec<TaskId> = Vec::new();
    // `list` sorts by id, so a repeated id is next to its twin.
    for pair in tasks.windows(2) {
        if pair[0].id == pair[1].id && duplicates.last() != Some(&pair[0].id) {
            duplicates.push(pair[0].id.clone());
        }
    }

    // One counter per parent (`None`: the top level), each as high as its highest child.
    let mut groups: BTreeMap<Option<TaskId>, Vec<&TaskId>> = BTreeMap::new();
    for t in &tasks {
        groups.entry(t.id.parent()).or_default().push(&t.id);
    }
    let mut raised = Vec::new();
    let mut above_counter = Vec::new();
    for (parent, ids) in &groups {
        let top = ids.iter().map(|id| number(id)).max().unwrap_or(0);
        let from = store.task_counter(&project.key, parent.as_ref())?;
        if top > from {
            store.seed_task_counter(&project.key, parent.as_ref(), top)?;
            above_counter.extend(ids.iter().filter(|id| number(id) > from).copied().cloned());
            raised.push(Raised {
                parent: parent.clone(),
                from,
                to: top,
            });
        }
    }
    above_counter.dedup();

    let counter = store.task_counter(&project.key, None)?;
    Ok(SyncReport {
        adapter: adapter.name(),
        counter,
        next: TaskId::new(&project.prefix, counter + 1)?,
        raised,
        above_counter,
        duplicates,
        unlabelled: adapter.unlabelled()?,
    })
}

/// `rtok task sync`'s text: the counter, then one line per kind of drift.
pub fn text(r: &SyncReport) -> String {
    let mut out = format!("{}: next id {}", r.adapter, r.next);
    if r.is_clean() && r.raised.is_empty() {
        out.push_str(", counters in step\n");
        return out;
    }
    out.push('\n');
    for x in &r.raised {
        let which = x
            .parent
            .as_ref()
            .map_or_else(|| "top level".to_string(), |p| format!("subtasks of {p}"));
        let _ = writeln!(out, "raised {which}: {} -> {}", x.from, x.to);
    }
    if !r.above_counter.is_empty() {
        let _ = writeln!(out, "above the counter: {}", join(&r.above_counter));
    }
    if !r.duplicates.is_empty() {
        let _ = writeln!(out, "duplicate ids: {}", join(&r.duplicates));
    }
    for s in &r.unlabelled {
        let was =
            s.id.as_ref()
                .map_or_else(String::new, |id| format!(" (label rtok:{id} is gone)"));
        let _ = writeln!(out, "no id label: {}{was} {}", s.title, s.url);
    }
    out
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;
    use std::time::Duration;

    use httpmock::MockServer;
    use serde_json::json;

    use super::super::adapter::TaskAdapter;
    use super::super::disk::DiskAdapter;
    use super::super::github::GithubAdapter;
    use super::super::{NewTask, Status, Task};
    use super::*;

    fn id(s: &str) -> TaskId {
        s.parse().unwrap()
    }

    fn new(title: &str) -> NewTask {
        NewTask {
            title: title.into(),
            description: String::new(),
            parent: None,
        }
    }

    fn project(adapter: Box<dyn TaskAdapter>) -> Project {
        Project::with_adapter("p".into(), std::env::temp_dir(), "A".into(), adapter)
    }

    #[test]
    fn disk_ids_above_the_counter_raise_it_and_a_second_run_is_quiet() {
        let dir = std::env::temp_dir().join(format!("rtok-tasksync-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let disk = DiskAdapter::new(dir.clone());
        disk.create(&new("Pulled"), &id("A7")).unwrap();
        disk.create(&new("Leaf"), &id("A7.2")).unwrap();
        disk.write_status(&id("A7"), Status::Done).unwrap();
        let store = Store::open_in_memory().unwrap();
        store.seed_task_counter("p", None, 3).unwrap();
        let project = project(Box::new(disk));

        let r = sync(&project, &store).unwrap();
        assert_eq!((r.counter, r.next.to_string()), (7, "A8".into()));
        assert_eq!(r.above_counter, vec![id("A7"), id("A7.2")]);
        assert_eq!(
            r.raised,
            vec![
                Raised {
                    parent: None,
                    from: 3,
                    to: 7
                },
                Raised {
                    parent: Some(id("A7")),
                    from: 0,
                    to: 2
                },
            ]
        );
        assert_eq!(
            text(&r),
            "disk: next id A8\nraised top level: 3 -> 7\nraised subtasks of A7: 0 -> 2\nabove the counter: A7, A7.2\n"
        );
        // The next create numbers past what sync saw.
        assert_eq!(store.allocate_task_id("p", "A", None).unwrap(), id("A8"));
        assert_eq!(
            store.allocate_task_id("p", "A", Some(&id("A7"))).unwrap(),
            id("A7.3")
        );

        let again = sync(&project, &store).unwrap();
        assert!(again.is_clean() && again.raised.is_empty());
        assert!(
            text(&again).ends_with("counters in step\n"),
            "{}",
            text(&again)
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_counter_ahead_of_the_adapter_is_never_lowered() {
        let dir = std::env::temp_dir().join(format!("rtok-tasksync-ahead-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let store = Store::open_in_memory().unwrap();
        store.seed_task_counter("p", None, 9).unwrap();
        let project = project(Box::new(DiskAdapter::new(dir.clone())));
        let r = sync(&project, &store).unwrap();
        assert_eq!((r.counter, r.raised.len(), r.is_clean()), (9, 0, true));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// An adapter that lists whatever it was given, twins and all.
    struct Fixed(RefCell<Vec<Task>>, Vec<Stray>);

    impl TaskAdapter for Fixed {
        fn name(&self) -> &'static str {
            "fixed"
        }
        fn create(&self, _: &NewTask, _: &TaskId) -> Result<Task> {
            unreachable!("sync never writes")
        }
        fn list(&self, filter: &Filter) -> Result<Vec<Task>> {
            Ok(filter.select(self.0.borrow().clone()))
        }
        fn get(&self, _: &TaskId) -> Result<Option<Task>> {
            unreachable!()
        }
        fn write_status(&self, _: &TaskId, _: Status) -> Result<Task> {
            unreachable!("sync never writes")
        }
        fn max_id(&self, _: &str) -> Result<Option<TaskId>> {
            unreachable!()
        }
        fn unlabelled(&self) -> Result<Vec<Stray>> {
            Ok(self.1.clone())
        }
    }

    fn task(s: &str) -> Task {
        Task {
            id: id(s),
            title: "T".into(),
            description: String::new(),
            status: Status::Open,
            parent: id(s).parent(),
            created_at: 0,
            updated_at: 0,
            external: None,
        }
    }

    #[test]
    fn duplicates_and_unlabelled_issues_are_reported() {
        let stray = Stray {
            title: "A5. Lost".into(),
            url: "https://github.com/me/app/issues/9".into(),
            id: Some(id("A5")),
        };
        let fixed = Fixed(
            RefCell::new(vec![task("A2"), task("A2"), task("A2"), task("A3")]),
            vec![stray],
        );
        let store = Store::open_in_memory().unwrap();
        store.seed_task_counter("p", None, 3).unwrap();
        let r = sync(&project(Box::new(fixed)), &store).unwrap();
        assert_eq!(r.duplicates, vec![id("A2")]);
        assert!(r.above_counter.is_empty() && r.raised.is_empty());
        assert!(!r.is_clean());
        assert_eq!(
            text(&r),
            "fixed: next id A4\nduplicate ids: A2\nno id label: A5. Lost (label rtok:A5 is gone) https://github.com/me/app/issues/9\n"
        );
    }

    #[test]
    fn github_sync_only_reads() {
        let server = MockServer::start();
        let labelled = |number: u64, labels: &[&str]| {
            json!({
                "id": number * 100, "number": number,
                "html_url": format!("https://github.com/me/app/issues/{number}"),
                "title": format!("A{number}. Issue {number}"), "body": "", "state": "open",
                "labels": labels.iter().map(|n| json!({"name": n})).collect::<Vec<_>>(),
                "created_at": "2026-10-07T10:00:00Z", "updated_at": "2026-10-07T11:00:00Z",
            })
        };
        let reads = server.mock(|when, then| {
            when.method("GET")
                .path("/repos/me/app/issues")
                .query_param("labels", "rtok");
            then.status(200).json_body(json!([
                labelled(4, &["rtok", "rtok:A4"]),
                // Labelled by hand, ahead of this machine's counter.
                labelled(11, &["rtok", "rtok:A11"]),
                labelled(6, &["rtok"]),
            ]));
        });
        let gh = GithubAdapter::new(&server.base_url(), "me/app", "t", Duration::ZERO).unwrap();
        let store = Store::open_in_memory().unwrap();
        store.seed_task_counter("p", None, 5).unwrap();
        let r = sync(&project(Box::new(gh)), &store).unwrap();
        assert_eq!(r.adapter, "github");
        assert_eq!((r.counter, r.above_counter), (11, vec![id("A11")]));
        assert_eq!(r.unlabelled.len(), 1);
        assert_eq!(r.unlabelled[0].id, Some(id("A6")));
        // list and unlabelled each read once; a write would have hit no mock and failed.
        assert_eq!(reads.calls(), 2);
    }

    #[test]
    fn the_report_serializes_for_json() {
        let r = SyncReport {
            adapter: "disk",
            counter: 2,
            next: id("A3"),
            raised: vec![Raised {
                parent: None,
                from: 0,
                to: 2,
            }],
            above_counter: vec![id("A2")],
            duplicates: vec![],
            unlabelled: vec![],
        };
        let v = serde_json::to_value(&r).unwrap();
        assert_eq!(v["next"], "A3");
        assert_eq!(v["raised"][0], json!({"parent": null, "from": 0, "to": 2}));
        assert_eq!(v["above_counter"], json!(["A2"]));
    }
}
