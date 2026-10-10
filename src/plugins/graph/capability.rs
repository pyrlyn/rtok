// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T329.11: which graph mode works for a project, checked once. A request reads the record and
//! never probes again: a missing server is not looked for on every call and a dead one is not
//! restarted on every call. The record is held in memory for the hot path and mirrored into the
//! store (`plugin_state`), so another process (`rtok graph projects --json`, the web page) shows
//! what this one decided. Only a `backend` change and [`reprobe`], which the health check of
//! T329.17 owns, replace a record.

use std::collections::HashMap;
use std::path::Path;
use std::sync::{Arc, LazyLock, Mutex, MutexGuard, PoisonError};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::Result;
use rtok_plugin_sdk::Ctx;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use super::{index, lsp};

/// Seconds until a failed record may be checked again: the earliest the health check retries,
/// and how long a new process trusts a record another process wrote. Past it a restart picks
/// up a server installed in the meantime.
const RECHECK_S: i64 = 60;

/// The mode that answers a project's requests.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(rename_all = "lowercase")]
pub enum Chosen {
    Lsp,
    Tags,
}

/// One project's record, as stored and as `rtok graph projects --json` prints it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
pub struct Capability {
    pub backend: Chosen,
    /// The project's language when it has a marker file.
    pub language: Option<String>,
    /// The language has a language server at all, installed or not.
    pub server: bool,
    /// Why the server does not answer; absent while it does.
    pub reason: Option<String>,
    /// The `backend` value the record was made under; another value makes it stale.
    pub config: String,
    pub checked_at: i64,
    /// When the health check retries a failed record; absent while the server answers.
    pub next_probe_at: Option<i64>,
}

/// Why the language server cannot be used.
pub(crate) struct Absent {
    pub server: bool,
    pub reason: String,
}

/// The cheap half of the check: the marker file and the server binary, no spawn.
pub(crate) type Probe = Result<(), Absent>;

/// What the door gets back for one request.
pub(crate) enum Asked {
    /// The server is not used for this project.
    Tags(Capability),
    /// The server answered or failed this request.
    Lsp(Result<String>),
}

#[derive(Default)]
struct State {
    rec: Option<Capability>,
    /// The server has answered once in this process, so later calls need no lock.
    confirmed: bool,
}

type Slot = Arc<Mutex<State>>;

static CACHE: LazyLock<Mutex<HashMap<String, Slot>>> = LazyLock::new(Mutex::default);

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(PoisonError::into_inner)
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64)
}

fn slot(key: &str) -> Slot {
    Arc::clone(lock(&CACHE).entry(key.to_string()).or_default())
}

fn mirror_key(key: &str) -> String {
    format!("capability:{key}")
}

fn write_mirror(cx: &Ctx, key: &str, rec: &Capability) {
    if let Ok(json) = serde_json::to_string(rec) {
        // A lost write only means another process probes for itself.
        let _ = cx.plugin_state_set("graph", &mirror_key(key), &json);
    }
}

/// The record some process stored for `root`; what the CLI and the page show.
pub(crate) fn mirrored(cx: &Ctx, root: &Path) -> Option<Capability> {
    read_mirror(cx, &index::canon(root))
}

fn read_mirror(cx: &Ctx, key: &str) -> Option<Capability> {
    let raw = cx.plugin_state_get("graph", &mirror_key(key)).ok()??;
    serde_json::from_str(&raw).ok()
}

/// One line, short enough for a header; the first line of an error is the useful one.
pub(crate) fn clip(text: &str) -> String {
    text.lines()
        .next()
        .unwrap_or_default()
        .chars()
        .take(120)
        .collect()
}

pub(crate) fn reason_of(e: &anyhow::Error) -> String {
    clip(&format!("{e:#}"))
}

fn measure(root: &Path, config: &str, probe: impl FnOnce(&Path) -> Probe) -> Capability {
    let (backend, server, reason) = match probe(root) {
        Ok(()) => (Chosen::Lsp, true, None),
        Err(a) => (Chosen::Tags, a.server, Some(clip(&a.reason))),
    };
    let at = now();
    Capability {
        backend,
        language: lsp::language_of(root).map(String::from),
        server,
        reason,
        config: config.to_string(),
        checked_at: at,
        next_probe_at: (backend == Chosen::Tags).then_some(at + RECHECK_S),
    }
}

impl State {
    /// The record for `config`: the one held, else one a peer process stored and the health
    /// check has not yet come due for, else a fresh check. Another `config` value is a
    /// replaced record: this is what clears exactly the projects a `backend` change reaches.
    fn current(
        &mut self,
        cx: &Ctx,
        key: &str,
        root: &Path,
        config: &str,
        probe: impl FnOnce(&Path) -> Probe,
    ) -> Capability {
        if let Some(rec) = self.rec.as_ref().filter(|r| r.config == config) {
            return rec.clone();
        }
        self.confirmed = false;
        let adopted = read_mirror(cx, key)
            .filter(|m| m.config == config && m.next_probe_at.is_none_or(|due| now() < due));
        let rec = adopted.unwrap_or_else(|| {
            let rec = measure(root, config, probe);
            write_mirror(cx, key, &rec);
            rec
        });
        self.rec = Some(rec.clone());
        rec
    }

    /// A server that was working stops being asked, once, with the reason.
    fn downgrade(&mut self, cx: &Ctx, key: &str, reason: String) {
        let Some(rec) = self.rec.as_mut().filter(|r| r.backend == Chosen::Lsp) else {
            return;
        };
        rec.backend = Chosen::Tags;
        rec.reason = Some(reason);
        rec.checked_at = now();
        rec.next_probe_at = Some(rec.checked_at + RECHECK_S);
        write_mirror(cx, key, rec);
    }
}

/// One request through the record. The first server call of a process runs under the project's
/// lock, so concurrent first requests wait for it and see its outcome instead of each starting
/// a server. A server that breaks (anything but an error answer) is downgraded to tags.
pub(crate) fn ask(
    cx: &Ctx,
    root: &Path,
    config: &str,
    probe: impl FnOnce(&Path) -> Probe,
    server: impl FnOnce() -> Result<String>,
) -> Asked {
    let key = index::canon(root);
    let slot = slot(&key);
    let mut st = lock(&slot);
    let rec = st.current(cx, &key, root, config, probe);
    if rec.backend == Chosen::Tags {
        return Asked::Tags(rec);
    }
    let first = !st.confirmed;
    let held = first.then_some(st);
    let answer = server();
    let mut st = held.unwrap_or_else(|| lock(&slot));
    match &answer {
        Ok(_) => st.confirmed = true,
        Err(e) if lsp::server_broke(e) => st.downgrade(cx, &key, reason_of(e)),
        Err(_) => {}
    }
    Asked::Lsp(answer)
}

/// The server is installed for this project, per the record (a check on first use only).
pub(crate) fn server_ready(
    cx: &Ctx,
    root: &Path,
    config: &str,
    probe: impl FnOnce(&Path) -> Probe,
) -> bool {
    let key = index::canon(root);
    let slot = slot(&key);
    lock(&slot).current(cx, &key, root, config, probe).backend == Chosen::Lsp
}

/// Check one project again and replace its record. The only caller besides a `backend` change
/// is the health check (T329.17); a request never calls it.
#[cfg_attr(not(test), allow(dead_code))]
pub(crate) fn reprobe(
    cx: &Ctx,
    root: &Path,
    config: &str,
    probe: impl FnOnce(&Path) -> Probe,
) -> Capability {
    let key = index::canon(root);
    let slot = slot(&key);
    let mut st = lock(&slot);
    let rec = measure(root, config, probe);
    write_mirror(cx, &key, &rec);
    st.rec = Some(rec.clone());
    st.confirmed = false;
    rec
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering::SeqCst};

    use super::*;
    use crate::plugin::Runtime;

    /// What a fake server and a fake tags index were asked, and how often the check ran.
    #[derive(Default)]
    struct Counts {
        probes: AtomicUsize,
        server: AtomicUsize,
        tags: AtomicUsize,
    }

    fn missing() -> Absent {
        Absent {
            server: true,
            reason: "lsp: rust-analyzer not on PATH".into(),
        }
    }

    /// One request through the door. `installed` is what the probe finds; `server` is the
    /// language server's answer.
    fn request(
        cx: &Ctx,
        root: &Path,
        n: &Counts,
        installed: &AtomicBool,
        server: impl Fn() -> Result<String> + Send,
    ) -> String {
        super::super::door(
            cx,
            root,
            "symbol",
            &[],
            |_| {
                n.probes.fetch_add(1, SeqCst);
                if installed.load(SeqCst) {
                    Ok(())
                } else {
                    Err(missing())
                }
            },
            || {
                n.server.fetch_add(1, SeqCst);
                server()
            },
            || {
                n.tags.fetch_add(1, SeqCst);
                Ok("tags answer".into())
            },
        )
        .unwrap()
    }

    fn answer() -> Result<String> {
        Ok("a.rs:1 function".into())
    }

    fn project(tag: &str, backend: &str) -> (Runtime, PathBuf) {
        let (mut c, dir) = crate::testutil::config(tag);
        c.plugins.graph.backend = backend.into();
        let root = dir.join("proj");
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("Cargo.toml"), "[package]\nname = \"x\"\n").unwrap();
        (Runtime::open(c, tag).unwrap(), root)
    }

    #[test]
    fn a_hundred_requests_after_the_first_run_no_check() {
        let (rt, root) = project("t32911-once", "auto");
        let cx = Ctx::new(&rt);
        let (n, installed) = (Counts::default(), AtomicBool::new(true));
        for _ in 0..101 {
            assert_eq!(
                request(&cx, &root, &n, &installed, answer),
                "(lsp)\na.rs:1 function"
            );
        }
        assert_eq!(n.probes.load(SeqCst), 1);
        assert_eq!(n.tags.load(SeqCst), 0);

        let (rt, root) = project("t32911-once-missing", "lsp");
        let cx = Ctx::new(&rt);
        let (n, installed) = (Counts::default(), AtomicBool::new(false));
        for _ in 0..101 {
            let out = request(&cx, &root, &n, &installed, || panic!("no server"));
            assert_eq!(
                out,
                "(tags; lsp: lsp: rust-analyzer not on PATH)\ntags answer"
            );
        }
        // The missing server was looked for once and never asked.
        assert_eq!((n.probes.load(SeqCst), n.server.load(SeqCst)), (1, 0));
    }

    #[test]
    fn a_server_installed_later_is_picked_up_by_a_reprobe_only() {
        let (rt, root) = project("t32911-reprobe", "auto");
        let cx = Ctx::new(&rt);
        let (n, installed) = (Counts::default(), AtomicBool::new(false));
        for _ in 0..100 {
            request(&cx, &root, &n, &installed, answer);
        }
        installed.store(true, SeqCst);
        for _ in 0..100 {
            let out = request(&cx, &root, &n, &installed, answer);
            assert!(
                out.starts_with("(tags; lsp: rust-analyzer not on PATH)"),
                "{out}"
            );
        }
        assert_eq!(n.probes.load(SeqCst), 1);
        let config = super::super::backend_name(&cx, &root);
        let rec = reprobe(&cx, &root, &config, |_| {
            n.probes.fetch_add(1, SeqCst);
            Ok(())
        });
        assert_eq!((rec.backend, rec.reason), (Chosen::Lsp, None));
        for _ in 0..100 {
            let out = request(&cx, &root, &n, &installed, answer);
            assert_eq!(out, "(lsp)\na.rs:1 function");
        }
        assert_eq!((n.probes.load(SeqCst), n.server.load(SeqCst)), (2, 100));
    }

    #[test]
    fn a_server_that_breaks_is_downgraded_once_but_an_error_answer_is_not() {
        let (rt, root) = project("t32911-break", "auto");
        let cx = Ctx::new(&rt);
        let (n, installed) = (Counts::default(), AtomicBool::new(true));
        let calls = AtomicUsize::new(0);
        let flaky = || match calls.fetch_add(1, SeqCst) {
            0 => answer(),
            1 => Err(lsp::Refused("lsp textDocument/definition: bad params".into()).into()),
            _ => Err(anyhow::anyhow!("lsp: eof; exited signal: 9")),
        };
        let outs: Vec<_> = (0..4)
            .map(|_| request(&cx, &root, &n, &installed, flaky))
            .collect();
        assert_eq!(outs[0], "(lsp)\na.rs:1 function");
        // An error answer falls back for that request, and the server is asked again.
        assert!(
            outs[1].starts_with("(tags; lsp: lsp textDocument/definition"),
            "{outs:?}"
        );
        assert!(
            outs[2].starts_with("(tags; lsp: eof; exited signal: 9)"),
            "{outs:?}"
        );
        // The crash is recorded once; the fourth request never reaches the server.
        assert_eq!(outs[3], outs[2]);
        assert_eq!((n.server.load(SeqCst), n.probes.load(SeqCst)), (3, 1));
        let rec = mirrored(&cx, &root).unwrap();
        assert_eq!(rec.backend, Chosen::Tags);
        assert!(rec.reason.unwrap().contains("eof"));
        assert!(rec.next_probe_at.is_some());
    }

    #[test]
    fn concurrent_first_requests_share_one_check_and_one_server_attempt() {
        for working in [true, false] {
            let (rt, root) = project(&format!("t32911-flight-{working}"), "auto");
            let (n, installed) = (Counts::default(), AtomicBool::new(true));
            let gate = std::sync::Barrier::new(8);
            std::thread::scope(|s| {
                for _ in 0..8 {
                    s.spawn(|| {
                        let cx = Ctx::new(&rt);
                        gate.wait();
                        request(&cx, &root, &n, &installed, || {
                            std::thread::sleep(std::time::Duration::from_millis(30));
                            if working {
                                answer()
                            } else {
                                Err(anyhow::anyhow!("lsp: eof"))
                            }
                        });
                    });
                }
            });
            assert_eq!(n.probes.load(SeqCst), 1);
            // A server that cannot start is tried by the first request alone.
            let server = if working { 8 } else { 1 };
            assert_eq!(n.server.load(SeqCst), server, "working: {working}");
        }
    }

    #[test]
    fn a_backend_change_rechecks_only_the_projects_it_reaches() {
        let (mut c, dir) = crate::testutil::config("t32911-config");
        c.plugins.graph.backend = "auto".into();
        let (a, b) = (dir.join("a"), dir.join("b"));
        for (root, marker) in [(&a, "Cargo.toml"), (&b, "tsconfig.json")] {
            fs::create_dir_all(root).unwrap();
            fs::write(root.join(marker), "{}").unwrap();
        }
        let mut changed = c.clone();
        changed
            .plugins
            .graph
            .backend_by_language
            .insert("rust".into(), "lsp".into());
        let (before, after) = (
            Runtime::open(c, "t32911-config").unwrap(),
            Runtime::open(changed, "t32911-config").unwrap(),
        );
        let installed = AtomicBool::new(true);
        let counts = |rt: &Runtime| {
            let (na, nb) = (Counts::default(), Counts::default());
            let cx = Ctx::new(rt);
            request(&cx, &a, &na, &installed, answer);
            request(&cx, &b, &nb, &installed, answer);
            (na.probes.load(SeqCst), nb.probes.load(SeqCst))
        };
        assert_eq!(counts(&before), (1, 1));
        assert_eq!(counts(&before), (0, 0));
        // The rust override changes a's value (`auto` to `lsp`); b's stays `auto`.
        assert_eq!(counts(&after), (1, 0));
        assert_eq!(mirrored(&Ctx::new(&after), &a).unwrap().config, "lsp");
        assert_eq!(mirrored(&Ctx::new(&after), &b).unwrap().config, "auto");
    }

    #[test]
    fn another_process_adopts_a_fresh_record_and_checks_a_due_one_itself() {
        let (rt, root) = project("t32911-mirror", "auto");
        let (n, installed) = (Counts::default(), AtomicBool::new(false));
        request(&Ctx::new(&rt), &root, &n, &installed, answer);
        let key = index::canon(&root);
        let mut rec = mirrored(&Ctx::new(&rt), &root).unwrap();
        assert_eq!(rec.backend, Chosen::Tags);
        assert_eq!(rec.language.as_deref(), Some("rust"));
        assert_eq!(rec.next_probe_at, Some(rec.checked_at + RECHECK_S));

        // A new process has an empty memory and the same store.
        lock(&CACHE).remove(&key);
        let second = Counts::default();
        let out = request(&Ctx::new(&rt), &root, &second, &installed, answer);
        assert!(
            out.starts_with("(tags; lsp: rust-analyzer not on PATH)"),
            "{out}"
        );
        assert_eq!(second.probes.load(SeqCst), 0);

        // Once the health check would have come due, a restart looks again.
        rec.next_probe_at = Some(now() - 1);
        write_mirror(&Ctx::new(&rt), &key, &rec);
        lock(&CACHE).remove(&key);
        installed.store(true, SeqCst);
        let out = request(&Ctx::new(&rt), &root, &second, &installed, answer);
        assert_eq!(out, "(lsp)\na.rs:1 function");
        assert_eq!(second.probes.load(SeqCst), 1);
    }
}
