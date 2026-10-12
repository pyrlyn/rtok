// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T561 (D27): the Graph page's one-project view, the terminal counterpart of the web page's
//! drill-down (T329.22). It draws nothing of its own: the nodes and the count the cap left out
//! are [`drill::run`]'s answer, the function the page's `graph` request answers with, at the
//! page's own cap. They go to the live calls pane, which counts the calls folded into "+N more"
//! on them (`CallsStore::folded_counters`), and the project becomes the scope of the pane's
//! "outside scope" mark, as it does on the page.

use std::sync::mpsc;

use crate::config::Config;
use crate::web::calls_store::DrawnNode;

/// What the one-project view draws: the nodes within the cap and how many it left out.
#[derive(Default)]
pub(super) struct Drawn {
    pub nodes: Vec<DrawnNode>,
    pub more: usize,
}

type Answer = Result<Drawn, String>;

#[derive(Default)]
pub(super) struct Drill {
    /// The registry id and name of the project the page is drilled into.
    open: Option<(i32, String)>,
    drawn: Drawn,
    /// The answer of a load running off the key loop; closing or drilling elsewhere drops it.
    pending: Option<mpsc::Receiver<Answer>>,
    note: String,
}

impl Drill {
    pub(super) fn project(&self) -> Option<i32> {
        self.open.as_ref().map(|(id, _)| *id)
    }

    pub(super) fn drawn(&self) -> &Drawn {
        &self.drawn
    }

    /// The line under the registry table: what the view holds, or why it holds nothing.
    pub(super) fn note(&self) -> &str {
        &self.note
    }

    /// Opens the view on a project, or closes it when that project is already the one drilled
    /// into. `background` is whether the key loop must stay free (the running TUI).
    pub(super) fn toggle(&mut self, id: i32, name: String, cfg: &Config, background: bool) {
        let leaving = self.project() == Some(id);
        *self = Self::default();
        if leaving {
            return;
        }
        self.note = format!("drilling into {name} …");
        self.open = Some((id, name));
        if !background {
            self.apply(load(cfg, id));
            return;
        }
        let (tx, rx) = mpsc::channel();
        let cfg = cfg.clone();
        let spawned = std::thread::Builder::new()
            .name("rtok-tui-drill".into())
            .spawn(move || tx.send(load(&cfg, id)));
        match spawned {
            Ok(_) => self.pending = Some(rx),
            Err(e) => self.note = format!("drill did not start: {e}"),
        }
    }

    /// Takes a finished load, if any. Called by the loop between keys.
    pub(super) fn poll(&mut self) {
        let Some(rx) = &self.pending else {
            return;
        };
        let answer = match rx.try_recv() {
            Ok(answer) => answer,
            Err(mpsc::TryRecvError::Empty) => return,
            Err(mpsc::TryRecvError::Disconnected) => Err("drill stopped without an answer".into()),
        };
        self.pending = None;
        self.apply(answer);
    }

    fn apply(&mut self, answer: Answer) {
        let name = self.open.as_ref().map_or("", |(_, n)| n.as_str());
        match answer {
            Ok(drawn) => {
                self.note = format!(
                    "{name}: {} nodes drawn, +{} more",
                    drawn.nodes.len(),
                    drawn.more
                );
                self.drawn = drawn;
            }
            Err(e) => self.note = format!("{name}: {e}"),
        }
    }
}

#[cfg(not(feature = "graph"))]
fn load(_: &Config, _: i32) -> Answer {
    Err("the graph feature is not built in".into())
}

#[cfg(feature = "graph")]
fn load(cfg: &Config, id: i32) -> Answer {
    use crate::plugin::Runtime;
    use crate::plugins::graph::drill::{self, DrillRequest};

    let rt = Runtime::open(cfg.clone(), "tui-drill").map_err(|e| format!("{e:#}"))?;
    // The default limit is the page's own (`PAGE` in `DrillView.tsx`).
    let req = DrillRequest {
        project: id.to_string(),
        ..DrillRequest::default()
    };
    let graph = drill::run(&rt, &req).map_err(|e| format!("{e:#}"))?;
    if graph.state != "ok" {
        return Err(graph.state.into());
    }
    Ok(Drawn {
        more: graph.more,
        nodes: graph
            .nodes
            .into_iter()
            .map(|n| DrawnNode {
                label: n.label,
                root: n.path,
            })
            .collect(),
    })
}
