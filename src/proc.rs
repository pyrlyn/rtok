// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Child-process capture that never waits on a pipe's EOF: shared by `rtok run` (T235.1) and
//! the host `--version` probe (T280).

use std::io::Read;
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError, mpsc};
use std::time::{Duration, Instant};

/// How long [`capture`] keeps reading once the process has exited. A descendant it left
/// running (`git fsmonitor--daemon`, `gradle --daemon`, a backgrounded server, a host CLI's
/// first-run helper) can hold the pipe's write end for good, so waiting for EOF would hang.
const DRAIN_AFTER_EXIT: Duration = Duration::from_millis(200);

/// How often [`capture`] checks a time-limited child.
const POLL: Duration = Duration::from_millis(10);

/// What [`capture`] read, and the exit code ([`exit_code`]): `None` when the process was
/// killed at the time limit.
pub(crate) struct Captured {
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
    #[cfg_attr(not(feature = "cmd"), allow(dead_code))]
    pub code: Option<i32>,
    /// Bytes read from the pipes but discarded because the `cap` of [`capture_capped`] was
    /// full. Always 0 for [`capture`].
    #[cfg_attr(not(feature = "cmd"), allow(dead_code))]
    pub dropped: u64,
}

/// What the two pipe readers of one capture share: a byte budget for stdout and stderr
/// together, and the count of bytes that did not fit.
struct Budget {
    left: AtomicU64,
    dropped: AtomicU64,
}

impl Budget {
    /// Take up to `n` bytes from the budget; the rest of `n` is counted as dropped.
    fn take(&self, n: usize) -> usize {
        let n = n as u64;
        let got = self
            .left
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |left| {
                Some(left - left.min(n))
            })
            .map_or(0, |left| left.min(n));
        self.dropped.fetch_add(n - got, Ordering::Relaxed);
        got as usize
    }
}

/// Run `cmd` with stdin closed and stdout/stderr piped. Waits for the process rather than for
/// EOF (unlike `Command::output`): after it exits, what its pipes already hold is drained for
/// up to [`DRAIN_AFTER_EXIT`], and a reader still blocked by a descendant is left behind.
/// With `limit`, a process still running after it is killed.
pub(crate) fn capture(cmd: Command, limit: Option<Duration>) -> std::io::Result<Captured> {
    capture_capped(cmd, limit, None)
}

/// [`capture`] that keeps at most `cap` bytes of stdout and stderr together. Bytes past it are
/// still read, so the child never blocks on a full pipe and exits on its own, but are counted
/// in [`Captured::dropped`] instead of stored: `yes | head -c 10G` must not take the
/// memory the unwrapped host command would not.
pub(crate) fn capture_capped(
    mut cmd: Command,
    limit: Option<Duration>,
    cap: Option<u64>,
) -> std::io::Result<Captured> {
    let budget = Arc::new(Budget {
        left: AtomicU64::new(cap.unwrap_or(u64::MAX)),
        dropped: AtomicU64::new(0),
    });
    let mut child = cmd
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?;
    let (tx, rx) = mpsc::channel();
    let out = drain(child.stdout.take(), tx.clone(), Arc::clone(&budget));
    let err = drain(child.stderr.take(), tx, Arc::clone(&budget));
    let code = match limit {
        None => exit_code(child.wait()?),
        Some(limit) => wait_until(&mut child, Instant::now() + limit)?,
    };
    let deadline = Instant::now() + DRAIN_AFTER_EXIT;
    for _ in 0..2 {
        if rx
            .recv_timeout(deadline.saturating_duration_since(Instant::now()))
            .is_err()
        {
            break;
        }
    }
    let take =
        |b: &Mutex<Vec<u8>>| std::mem::take(&mut *b.lock().unwrap_or_else(PoisonError::into_inner));
    Ok(Captured {
        stdout: take(&out),
        stderr: take(&err),
        code,
        dropped: budget.dropped.load(Ordering::Relaxed),
    })
}

/// The code a shell would report for `status`: its own code, or `128 + signal` for a Unix
/// signal death, where `ExitStatus::code()` is `None` and callers that default it to 1 hide
/// an OOM kill (137) or a timeout (143) behind an ordinary failure (T366). `None` stays only
/// for a status with neither, which no supported platform produces.
pub(crate) fn exit_code(status: ExitStatus) -> Option<i32> {
    #[cfg(unix)]
    if let Some(sig) = std::os::unix::process::ExitStatusExt::signal(&status) {
        return Some(128 + sig);
    }
    status.code()
}

/// The child's exit code, or `None` after killing it at `deadline`.
fn wait_until(child: &mut Child, deadline: Instant) -> std::io::Result<Option<i32>> {
    loop {
        if let Some(status) = child.try_wait()? {
            return Ok(exit_code(status));
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Ok(None);
        }
        std::thread::sleep(POLL);
    }
}

/// Read `pipe` to EOF on its own thread into the returned buffer, as far as `budget` allows;
/// signal `done` at EOF.
fn drain(
    pipe: Option<impl Read + Send + 'static>,
    done: mpsc::Sender<()>,
    budget: Arc<Budget>,
) -> Arc<Mutex<Vec<u8>>> {
    let buf = Arc::new(Mutex::new(Vec::new()));
    let sink = Arc::clone(&buf);
    std::thread::spawn(move || {
        if let Some(mut pipe) = pipe {
            let mut chunk = [0u8; 8192];
            while let Ok(n @ 1..) = pipe.read(&mut chunk) {
                let keep = budget.take(n);
                if keep > 0 {
                    sink.lock()
                        .unwrap_or_else(PoisonError::into_inner)
                        .extend_from_slice(&chunk[..keep]);
                }
            }
        }
        let _ = done.send(());
    });
    buf
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    /// T235.1: a backgrounded grandchild keeps the capture pipe open after the shell exits.
    /// `capture` returns on the shell's exit with its output and code, not at the pipe's EOF.
    #[test]
    fn capture_returns_when_the_child_exits_though_a_grandchild_holds_the_pipe() {
        let mut cmd = Command::new("sh");
        cmd.args(["-c", "echo hi; sleep 20 & exit 4"]);
        let start = Instant::now();
        let c = capture(cmd, None).unwrap();
        assert!(
            start.elapsed() < Duration::from_secs(10),
            "{:?}",
            start.elapsed()
        );
        assert_eq!((c.stdout.as_slice(), c.code), (&b"hi\n"[..], Some(4)));
    }

    /// T366: `ExitStatus::code()` is `None` for a signal death; the shell convention is `128 + signal`.
    #[test]
    fn capture_reports_128_plus_the_signal_of_a_killed_child() {
        for (script, code) in [
            ("kill -TERM $$", 143),
            ("kill -KILL $$", 137),
            ("exit 7", 7),
        ] {
            let mut cmd = Command::new("sh");
            cmd.args(["-c", script]);
            assert_eq!(capture(cmd, None).unwrap().code, Some(code), "{script}");
        }
    }

    /// T280: a process still running at the limit is killed, and what it printed is kept.
    #[test]
    fn capture_kills_a_child_still_running_at_the_limit() {
        let mut cmd = Command::new("sh");
        cmd.args(["-c", "echo 1.2.3; sleep 20"]);
        let start = Instant::now();
        let c = capture(cmd, Some(Duration::from_millis(300))).unwrap();
        assert!(
            start.elapsed() < Duration::from_secs(5),
            "{:?}",
            start.elapsed()
        );
        assert_eq!((c.stdout.as_slice(), c.code), (&b"1.2.3\n"[..], None));
    }

    /// T448: past the cap the pipe is still drained (the child finishes and keeps its exit
    /// code), the first `cap` bytes are kept across both streams, and the rest is counted.
    #[test]
    fn capture_capped_keeps_the_cap_and_counts_the_rest() {
        let mut cmd = Command::new("sh");
        cmd.args([
            "-c",
            "head -c 50000 /dev/zero; head -c 7000 /dev/zero >&2; exit 3",
        ]);
        let c = capture_capped(cmd, None, Some(1000)).unwrap();
        assert_eq!(c.stdout.len() + c.stderr.len(), 1000);
        assert_eq!(c.dropped, 57_000 - 1000);
        assert_eq!(c.code, Some(3));
    }

    /// T448: output under the cap is untouched and nothing is reported dropped.
    #[test]
    fn capture_capped_under_the_cap_drops_nothing() {
        let mut cmd = Command::new("sh");
        cmd.args(["-c", "printf abc; printf def >&2"]);
        let c = capture_capped(cmd, None, Some(6)).unwrap();
        assert_eq!(
            (c.stdout.as_slice(), c.stderr.as_slice()),
            (&b"abc"[..], &b"def"[..])
        );
        assert_eq!(c.dropped, 0);
    }
}
