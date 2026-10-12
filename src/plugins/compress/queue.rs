// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T385.9.2: the background queue of the P28 compressor. The proxy hands over the archive ids
//! a request just rewrote and returns at once; each job runs on the tokio runtime and writes
//! its summary beside the archive row, so a request only ever *reads* what an earlier job left.
//! Absent when `[plugins.compress.llm] enabled` is off, so a disabled build spawns nothing.

use std::collections::HashSet;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use super::llm::{Auth, Compressor};
use crate::config::Config;
use crate::store::Store;

/// Ids remembered as already tried; cleared when full so a long-lived proxy stays bounded.
const SEEN_CAP: usize = 10_000;

pub struct Queue {
    compressor: Compressor,
    // Its own handle: a job outlives the request whose `Runtime` offered it.
    store: Store,
    seen: Mutex<HashSet<String>>,
    in_flight: AtomicUsize,
    max_in_flight: usize,
}

/// Frees the slot even when a job panics.
struct Slot(Arc<Queue>);

impl Drop for Slot {
    fn drop(&mut self) {
        self.0.in_flight.fetch_sub(1, Ordering::AcqRel);
    }
}

impl Queue {
    /// `None` while the flag is off.
    pub fn new(cfg: &Config) -> anyhow::Result<Option<Arc<Self>>> {
        let Some(compressor) = Compressor::new(cfg)? else {
            return Ok(None);
        };
        let store = Store::open(&cfg.core.db_path)?;
        store.set_store_raw(cfg.core.store_raw);
        Ok(Some(Arc::new(Self {
            compressor,
            store,
            seen: Mutex::new(HashSet::new()),
            in_flight: AtomicUsize::new(0),
            max_in_flight: cfg.plugins.compress.llm.max_in_flight.max(1) as usize,
        })))
    }

    /// Queue `ids` and return without waiting. An id is tried once per process; ids past the
    /// in-flight cap are not taken, so the next request that offers them starts them.
    pub fn submit(self: &Arc<Self>, auth: Option<Auth>, ids: Vec<String>) {
        // No credential, or no runtime to run on (a sync caller): the extractive view stays.
        let (Some(auth), Ok(runtime)) = (auth, tokio::runtime::Handle::try_current()) else {
            return;
        };
        for id in ids {
            if self.in_flight.load(Ordering::Acquire) >= self.max_in_flight {
                break;
            }
            {
                let Ok(mut seen) = self.seen.lock() else {
                    return;
                };
                if seen.len() >= SEEN_CAP {
                    seen.clear();
                }
                if !seen.insert(id.clone()) {
                    continue;
                }
            }
            self.in_flight.fetch_add(1, Ordering::AcqRel);
            let slot = Slot(self.clone());
            let auth = auth.clone();
            runtime.spawn(async move {
                // `run` warns about every failure itself.
                slot.0.compressor.run(&slot.0.store, &auth, &id).await;
            });
        }
    }
}
