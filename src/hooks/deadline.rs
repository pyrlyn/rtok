// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! T921: a hook call that outlives its deadline prints `{}` and exits 0 instead of running into
//! the host's timeout, which stalls the tool call for 5 s and skips rtok without a word.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::time::Duration;

/// Who writes the answer: the hook when it finishes, or the watchdog when time runs out. The
/// first to [`Gate::claim`] it prints; the other stays silent, so stdout never gets two answers.
#[derive(Clone, Default)]
pub struct Gate(Arc<AtomicBool>);

impl Gate {
    pub fn claim(&self) -> bool {
        !self.0.swap(true, Ordering::SeqCst)
    }
}

/// Stops the watchdog armed by [`arm`] when dropped.
pub struct Armed {
    _stop: mpsc::Sender<()>,
}

/// Run `expired` on a watchdog thread once `limit` passes without the returned [`Armed`] being
/// dropped. `expired` owns the exit: [`arm_stdout`] is the real one.
pub fn arm(limit: Duration, expired: impl FnOnce() + Send + 'static) -> Armed {
    let (stop, rx) = mpsc::channel::<()>();
    std::thread::spawn(move || {
        if rx.recv_timeout(limit) == Err(mpsc::RecvTimeoutError::Timeout) {
            expired();
        }
    });
    Armed { _stop: stop }
}

/// [`arm`] with the real expiry: claim `gate`, write `{}` to stdout, note it on stderr, exit 0.
pub fn arm_stdout(event: &str, limit: Duration, gate: &Gate) -> Armed {
    let (event, gate) = (event.to_string(), gate.clone());
    arm(limit, move || {
        if !gate.claim() {
            return;
        }
        eprintln!(
            "rtok: hook {event} passed its {} ms deadline; failing open",
            limit.as_millis()
        );
        let mut out = std::io::stdout().lock();
        let _ = std::io::Write::write_all(&mut out, b"{}");
        let _ = std::io::Write::flush(&mut out);
        std::process::exit(0);
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_watchdog_fires_once_the_limit_passes() {
        let (tx, rx) = mpsc::channel();
        let _armed = arm(Duration::from_millis(20), move || {
            let _ = tx.send(());
        });
        assert!(rx.recv_timeout(Duration::from_secs(5)).is_ok());
    }

    #[test]
    fn a_finished_hook_disarms_the_watchdog() {
        let (tx, rx) = mpsc::channel();
        drop(arm(Duration::from_millis(50), move || {
            let _ = tx.send(());
        }));
        assert!(rx.recv_timeout(Duration::from_millis(300)).is_err());
    }

    #[test]
    fn only_the_first_claim_wins() {
        let gate = Gate::default();
        assert!(gate.claim());
        assert!(!gate.claim());
    }
}
