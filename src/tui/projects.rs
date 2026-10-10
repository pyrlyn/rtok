// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! The Graph page's registry keys (T476, D27): select a project, link or unlink a pair. The
//! write is the web page's — [`crate::web::project_write`] over `graph projects` — so the two
//! surfaces and the CLI cannot disagree about what it does; this module is the keys, the target
//! picker and the plan the user confirms. Nothing is written before `y`.

use crossterm::event::KeyCode;

use crate::model::Snapshot;
use crate::web::protocol::ProjectRequest;

/// One registry row as the keys need it. A copy off the snapshot, so the key logic is the same
/// with the `graph` feature off (where the snapshot has no rows and the page offers nothing).
pub(super) struct Entry {
    pub id: i32,
    pub name: String,
    pub selected: bool,
    pub missing: bool,
    pub state: &'static str,
    pub links: Vec<(i32, String)>,
    /// The server's score, painted as it came (T481); with `graph` off there are no rows.
    #[cfg(feature = "graph")]
    pub health: crate::plugins::graph::health::score::Score,
    #[cfg(feature = "graph")]
    pub scope_health: Option<u8>,
}

#[cfg(feature = "graph")]
pub(super) fn entries(snapshot: &Snapshot) -> Vec<Entry> {
    snapshot
        .projects
        .iter()
        .flatten()
        .map(|p| Entry {
            id: p.id,
            name: p.name.clone(),
            selected: p.selected,
            missing: p.missing,
            state: p.state,
            links: p.links.iter().map(|l| (l.to, l.name.clone())).collect(),
            health: p.health.clone(),
            scope_health: p.scope_health,
        })
        .collect()
}

#[cfg(not(feature = "graph"))]
pub(super) fn entries(_: &Snapshot) -> Vec<Entry> {
    Vec::new()
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Verb {
    Link,
    Unlink,
}

impl Verb {
    fn word(self) -> &'static str {
        match self {
            Self::Link => "link",
            Self::Unlink => "unlink",
        }
    }
}

#[derive(Default)]
enum Stage {
    #[default]
    Idle,
    /// `from` is an id, not a row index: a refresh between keys may reorder the rows.
    Pick {
        verb: Verb,
        from: i32,
        target: usize,
        both: bool,
    },
    Confirm {
        req: ProjectRequest,
        plan: String,
    },
}

/// What the page did with a key.
pub(super) enum Key {
    Ignored,
    Handled,
    /// The user confirmed: write this, then report the plan's outcome.
    Write(ProjectRequest, String),
}

#[derive(Default)]
pub(super) struct ProjectsState {
    cursor: usize,
    stage: Stage,
    status: String,
}

/// Where a link from `from` can go (not itself, not missing, not already linked) or, for an
/// unlink, where it goes now — the same rule as the web page's `linkTargets`.
fn candidates(verb: Verb, all: &[Entry], from: &Entry) -> Vec<(i32, String)> {
    match verb {
        Verb::Link => all
            .iter()
            .filter(|p| p.id != from.id && !p.missing && !from.links.iter().any(|l| l.0 == p.id))
            .map(|p| (p.id, p.name.clone()))
            .collect(),
        Verb::Unlink => from.links.clone(),
    }
}

fn plan_for(verb: Verb, all: &[Entry], from: &Entry, to: &(i32, String), both: bool) -> String {
    let arrows = if both {
        format!("{} <-> {}", from.name, to.1)
    } else {
        format!("{} -> {}", from.name, to.1)
    };
    let never_indexed = all.iter().any(|p| p.id == to.0 && p.state == "not indexed");
    // The write indexes a never-indexed target (so the scope answers from it at once); the plan
    // says so rather than let a link look instant.
    let note = if verb == Verb::Link && never_indexed {
        format!("; {} was never indexed, so it is indexed now", to.1)
    } else {
        String::new()
    };
    format!("{} {arrows}{note}", verb.word())
}

impl ProjectsState {
    /// The row cursor, clamped to the rows the registry holds.
    pub(super) fn cursor(&self, all: &[Entry]) -> usize {
        self.cursor.min(all.len().saturating_sub(1))
    }

    pub(super) fn set_status(&mut self, status: String) {
        self.status = status;
    }

    /// The line under the table: the plan awaiting `y`, the target being picked, or the last
    /// outcome.
    pub(super) fn note(&self, all: &[Entry]) -> String {
        match &self.stage {
            Stage::Idle => self.status.clone(),
            Stage::Confirm { plan, .. } => plan.clone(),
            Stage::Pick {
                verb,
                from,
                target,
                both,
            } => {
                let Some(f) = all.iter().find(|e| e.id == *from) else {
                    return String::new();
                };
                let to = candidates(*verb, all, f)
                    .get(*target)
                    .map_or_else(|| "-".into(), |t| t.1.clone());
                let both = if *both { " (both ways)" } else { "" };
                format!("{} {} -> {to}{both}", verb.word(), f.name)
            }
        }
    }

    /// One key on the Graph page. While a target is being picked or a plan awaits `y`, every key
    /// is the picker's: a stray `q` or digit must not leave the page with a plan half made.
    pub(super) fn key(&mut self, code: KeyCode, all: &[Entry]) -> Key {
        match std::mem::take(&mut self.stage) {
            Stage::Confirm { req, plan } if code == KeyCode::Char('y') => Key::Write(req, plan),
            Stage::Confirm { .. } => {
                self.status = "declined; nothing written".into();
                Key::Handled
            }
            Stage::Pick {
                verb,
                from,
                mut target,
                mut both,
            } => {
                let Some(f) = all.iter().find(|e| e.id == from) else {
                    self.status = "that project is gone".into();
                    return Key::Handled;
                };
                let cands = candidates(verb, all, f);
                match code {
                    KeyCode::Esc => return Key::Handled,
                    KeyCode::Up => target = target.saturating_sub(1),
                    KeyCode::Down => target = (target + 1).min(cands.len().saturating_sub(1)),
                    KeyCode::Char('b') => both = !both,
                    KeyCode::Enter if target < cands.len() => {
                        let to = &cands[target];
                        let (from, to_id) = (from.to_string(), to.0.to_string());
                        let req = match verb {
                            Verb::Link => ProjectRequest::Link {
                                from,
                                to: to_id,
                                both,
                            },
                            Verb::Unlink => ProjectRequest::Unlink {
                                from,
                                to: to_id,
                                both,
                            },
                        };
                        self.stage = Stage::Confirm {
                            req,
                            plan: plan_for(verb, all, f, to, both),
                        };
                        return Key::Handled;
                    }
                    _ => {}
                }
                self.stage = Stage::Pick {
                    verb,
                    from,
                    target,
                    both,
                };
                Key::Handled
            }
            Stage::Idle => self.idle_key(code, all),
        }
    }

    fn idle_key(&mut self, code: KeyCode, all: &[Entry]) -> Key {
        let Some(row) = all.get(self.cursor(all)) else {
            return Key::Ignored;
        };
        match code {
            KeyCode::Up => self.cursor = self.cursor(all).saturating_sub(1),
            KeyCode::Down => self.cursor = (self.cursor(all) + 1).min(all.len() - 1),
            KeyCode::Char('s') => {
                self.stage = Stage::Confirm {
                    req: ProjectRequest::Select {
                        project: row.id.to_string(),
                    },
                    plan: format!("select {}", row.name),
                };
                return Key::Handled;
            }
            KeyCode::Char(c @ ('l' | 'u')) => {
                let verb = if c == 'l' { Verb::Link } else { Verb::Unlink };
                if candidates(verb, all, row).is_empty() {
                    self.status = match verb {
                        Verb::Link => format!("nothing to link {} to", row.name),
                        Verb::Unlink => format!("{} has no links", row.name),
                    };
                } else {
                    self.stage = Stage::Pick {
                        verb,
                        from: row.id,
                        target: 0,
                        both: false,
                    };
                }
                return Key::Handled;
            }
            _ => return Key::Ignored,
        }
        self.status.clear();
        Key::Handled
    }
}

#[cfg(all(test, feature = "graph"))]
mod tests {
    use std::path::PathBuf;

    use crossterm::event::KeyModifiers;

    use super::*;
    use crate::config::Config;
    use crate::plugin::Runtime;
    use crate::plugins::graph::projects::{Action, rows, run};
    use crate::store::Origin;
    use crate::tui::app::{App, keys_for, tests::config};
    use crate::tui::view::tests::screen;

    /// A registry of alpha (1), beta (2) and gamma (3) on the config's own temp home.
    fn registry(cfg: &Config, roots: &[PathBuf]) {
        let rt = Runtime::open(cfg.clone(), "t476-fixture").unwrap();
        for root in roots {
            rt.store.register_project(root, Origin::Manual).unwrap();
        }
    }

    fn roots() -> Vec<PathBuf> {
        let base = crate::testutil::tmp_dir("tui-projects-roots");
        ["alpha", "beta", "gamma"]
            .map(|n| {
                let dir = base.join(n);
                std::fs::create_dir_all(&dir).unwrap();
                dir
            })
            .to_vec()
    }

    fn graph_app(roots: &[PathBuf]) -> (App, Config) {
        let mut cfg = config();
        cfg.tui.tab = "graph".into();
        registry(&cfg, roots);
        (App::new(&cfg), cfg)
    }

    /// Selection and links per project id: what the registry holds, minus timestamps.
    fn registry_state(cfg: &Config) -> Vec<(i32, bool, Vec<i32>)> {
        let rt = Runtime::open(cfg.clone(), "t476-state").unwrap();
        rows(&rt)
            .unwrap()
            .into_iter()
            .map(|p| (p.id, p.selected, p.links.iter().map(|l| l.to).collect()))
            .collect()
    }

    fn press(app: &mut App, keys: &str) {
        for c in keys.chars() {
            let code = match c {
                '^' => KeyCode::Up,
                'v' => KeyCode::Down,
                '\n' => KeyCode::Enter,
                '\u{1b}' => KeyCode::Esc,
                c => KeyCode::Char(c),
            };
            assert!(!app.key(code, KeyModifiers::NONE), "{c:?} must not quit");
        }
    }

    #[test]
    fn keys_write_what_the_cli_commands_write() {
        let roots = roots();
        let (mut app, cfg) = graph_app(&roots);
        let before = registry_state(&cfg);

        press(&mut app, "vs");
        let plan = screen(&app);
        assert!(plan.contains("select beta"), "the plan comes first: {plan}");
        assert_eq!(registry_state(&cfg), before, "nothing is written before y");
        press(&mut app, "y");
        assert!(screen(&app).contains("select beta: done"));

        // gamma is the second candidate once alpha is first; both ways, then confirm.
        press(&mut app, "lvb\n");
        let plan = screen(&app);
        assert!(plan.contains("link beta <-> gamma"), "{plan}");
        assert!(
            plan.contains("gamma was never indexed"),
            "the plan says the write indexes the target: {plan}"
        );
        press(&mut app, "y");
        assert!(screen(&app).contains("linked beta -> gamma"));

        // Only beta's link to gamma goes; gamma's link back stays.
        press(&mut app, "u\ny");
        assert!(screen(&app).contains("unlinked beta -> gamma"));

        // The same three actions through the function `rtok graph projects` runs.
        let other = config();
        registry(&other, &roots);
        let rt = Runtime::open(other.clone(), "t476-cli").unwrap();
        run(&rt, Action::Select("2".into()), false).unwrap();
        run(
            &rt,
            Action::Link {
                to: "3".into(),
                from: Some("2".into()),
                both: true,
                reason: None,
            },
            false,
        )
        .unwrap();
        run(
            &rt,
            Action::Unlink {
                to: "3".into(),
                from: Some("2".into()),
                both: false,
            },
            false,
        )
        .unwrap();
        assert_eq!(registry_state(&cfg), registry_state(&other));
        assert_eq!(
            registry_state(&cfg),
            vec![(1, false, vec![]), (2, true, vec![]), (3, false, vec![2])]
        );
    }

    #[test]
    fn declining_writes_nothing() {
        let (mut app, cfg) = graph_app(&roots());
        let before = registry_state(&cfg);
        // Any key but y declines a plan; Esc leaves the picker; q must not leave the page.
        press(&mut app, "sn");
        assert!(screen(&app).contains("declined; nothing written"));
        press(&mut app, "lvb\n\u{1b}");
        assert!(screen(&app).contains("declined"));
        press(&mut app, "ls");
        assert!(
            screen(&app).contains("link alpha -> beta"),
            "s is a picker key while a target is chosen"
        );
        press(&mut app, "\u{1b}u");
        assert!(screen(&app).contains("alpha has no links"));
        press(&mut app, "sq");
        assert_eq!(registry_state(&cfg), before);
    }

    #[test]
    fn a_refused_write_shows_the_reason_on_the_status_line() {
        let roots = roots();
        let (mut app, cfg) = graph_app(&roots);
        std::fs::remove_dir_all(&roots[0]).unwrap();
        app.key(KeyCode::Char('r'), KeyModifiers::NONE);
        let before = registry_state(&cfg);
        press(&mut app, "sy");
        assert!(screen(&app).contains("no longer exists"));
        assert_eq!(registry_state(&cfg), before);
    }

    #[test]
    fn the_key_hints_are_the_keys_table() {
        let (mut app, _) = graph_app(&roots());
        let shown = screen(&app);
        for (key, desc) in keys_for("graph") {
            assert!(
                shown.contains(key) && shown.contains(desc),
                "{key}: {shown}"
            );
        }
        press(&mut app, "?");
        assert!(screen(&app).contains("keys — graph"));
    }
}
