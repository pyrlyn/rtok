// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Per-lane in-flight cap (T385.7): a bulk burst must not take every connection to the
//! provider, nor its rate limit, from the interactive agent.
//!
//! Each capped lane has its own slots, so a full lane only ever delays itself — the `agent`
//! lane has no gate at all. Past the slots a few requests wait in line; past the line the
//! caller is told to come back (`429` + `Retry-After`) instead of piling up unbounded waiters
//! that each hold a client connection and a request body in memory.

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use tokio::sync::{OwnedSemaphorePermit, Semaphore};

/// What a caller turned away by a full queue is told to wait before retrying. One second:
/// a queued bulk request frees its slot in about one upstream round trip.
pub const RETRY_AFTER_S: u64 = 1;

/// Holding one is holding an upstream slot on its lane; dropping it frees the slot.
pub type Slot = OwnedSemaphorePermit;

pub struct Gate {
    slots: Arc<Semaphore>,
    waiting: AtomicUsize,
    max_queued: usize,
}

impl Gate {
    /// `None` for `max_in_flight = 0`: that lane is not capped.
    pub fn new(max_in_flight: u32, max_queued: u32) -> Option<Self> {
        (max_in_flight > 0).then(|| Self {
            slots: Arc::new(Semaphore::new(max_in_flight as usize)),
            waiting: AtomicUsize::new(0),
            max_queued: max_queued as usize,
        })
    }

    /// A slot now, or after waiting in line; `None` when the line is full.
    pub async fn enter(&self) -> Option<Slot> {
        if let Ok(slot) = self.slots.clone().try_acquire_owned() {
            return Some(slot);
        }
        if self.waiting.fetch_add(1, Ordering::AcqRel) >= self.max_queued {
            self.waiting.fetch_sub(1, Ordering::AcqRel);
            return None;
        }
        // Released however the wait ends, a client hanging up while queued included.
        let _queued = Queued(&self.waiting);
        // The semaphore is never closed, so the wait only ends with a slot.
        self.slots.clone().acquire_owned().await.ok()
    }

    /// Requests waiting for a slot right now.
    pub fn waiting(&self) -> usize {
        self.waiting.load(Ordering::Acquire)
    }
}

struct Queued<'a>(&'a AtomicUsize);

impl Drop for Queued<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::AcqRel);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_means_no_gate() {
        assert!(Gate::new(0, 8).is_none());
    }

    #[tokio::test]
    async fn slots_then_line_then_turned_away() {
        let gate = Arc::new(Gate::new(1, 1).expect("capped"));
        let first = gate.enter().await.expect("free slot");
        let queued = tokio::spawn({
            let gate = gate.clone();
            async move { gate.enter().await.is_some() }
        });
        while gate.waiting() == 0 {
            tokio::task::yield_now().await;
        }
        assert!(gate.enter().await.is_none(), "line of one is full");
        drop(first);
        assert!(
            queued.await.expect("join"),
            "the queued request gets the slot"
        );
        assert_eq!(gate.waiting(), 0);
    }

    #[tokio::test]
    async fn a_dropped_waiter_leaves_the_line() {
        let gate = Arc::new(Gate::new(1, 1).expect("capped"));
        let _held = gate.enter().await.expect("free slot");
        let waiter = tokio::spawn({
            let gate = gate.clone();
            async move { gate.enter().await.is_some() }
        });
        while gate.waiting() == 0 {
            tokio::task::yield_now().await;
        }
        waiter.abort();
        let _ = waiter.await;
        assert_eq!(gate.waiting(), 0);
    }
}
