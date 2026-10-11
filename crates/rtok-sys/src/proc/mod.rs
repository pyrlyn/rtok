//! The one way rtok runs an external program (T276): a [`ProgressRunner`] that shows a spinner
//! or a progress bar on a terminal while the child works, and nothing anywhere else.
//!
//! Every `Command::new` in the workspace belongs in this module; the clippy ban lands with the
//! last call site (T276.6).

mod indicator;
mod parse;

use std::ffi::OsStr;
use std::io::{self, Read, Write};
use std::path::Path;
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError, mpsc};
use std::time::{Duration, Instant};

pub use indicator::{Hidden, Indicator, TtyIndicator, disable_progress, suspend};
pub use parse::{
    CargoProgress, GitProgress, JsonLines, Percent, ProgressCallback, ProgressParser, Stream, Unit,
    Update,
};

/// How long [`ProgressRunner::run`] keeps reading once the child has exited. A descendant it
/// left running (`git fsmonitor--daemon`, a backgrounded server) can hold a pipe's write end for
/// good, so waiting for end-of-file would hang.
const DRAIN_AFTER_EXIT: Duration = Duration::from_millis(200);

/// What a finished `.run()` hands back; the same shape as `Command::output`.
pub type Captured = std::process::Output;

/// Where the child's output goes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Output {
    /// Keep stdout and stderr; an indicator shows until the child exits.
    Capture,
    /// Forward both streams live, hiding the indicator around each write.
    Stream,
    /// The child owns the terminal (`tspin`, `ketch`); no indicator.
    Inherit,
    /// A background child with null stdio; no indicator. `.spawn()` only.
    Detached,
}

/// How the wait is drawn.
pub enum Progress {
    /// No indicator: a quick read nobody waits for.
    Off,
    /// A spinner: the wait cannot be measured.
    Unknown,
    /// A spinner that turns into a bar when the parser reports a total.
    Parse(Box<dyn ProgressParser>),
    /// [`Progress::Parse`] with a closure, for a one-off format that needs no parser type.
    Callback(ProgressCallback),
}

pub struct ProgressRunner {
    label: String,
    cmd: Command,
    output: Output,
    progress: Progress,
    timeout: Option<Duration>,
    stdin: Option<Stdio>,
    shim: bool,
}

impl ProgressRunner {
    /// `label` is the message beside the spinner: the action and its target, present tense,
    /// ending in `…`.
    pub fn new<I, S>(label: &str, program: impl AsRef<OsStr>, args: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<OsStr>,
    {
        let mut cmd = Command::new(program);
        cmd.args(args);
        Self {
            label: label.to_string(),
            cmd,
            output: Output::Capture,
            progress: Progress::Unknown,
            timeout: None,
            stdin: None,
            shim: false,
        }
    }

    /// A bar over `total` items that rtok works through itself (worktrees to remove, runs to
    /// time). Commands run inside it keep their own spinner under the bar.
    pub fn batch(label: &str, total: u64, unit: Unit) -> Batch {
        Batch::new(indicator::pick_bar(label, total, unit), total, unit)
    }

    /// A call with no indicator.
    pub fn quiet<I, S>(program: impl AsRef<OsStr>, args: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<OsStr>,
    {
        Self::new("", program, args).progress(Progress::Off)
    }

    pub fn cwd(mut self, dir: impl AsRef<Path>) -> Self {
        self.cmd.current_dir(dir);
        self
    }

    pub fn env(mut self, key: impl AsRef<OsStr>, val: impl AsRef<OsStr>) -> Self {
        self.cmd.env(key, val);
        self
    }

    /// Without this a piped or detached child gets a closed stdin; an inherited one keeps ours.
    pub fn stdin(mut self, stdin: Stdio) -> Self {
        self.stdin = Some(stdin);
        self
    }

    /// One argument passed to the program without Windows quoting, for a `cmd.exe` body that
    /// is already quoted for `cmd.exe`: `Command::arg` would quote it again with MSVC rules,
    /// which `cmd.exe` does not parse. A plain `.arg` elsewhere.
    pub fn raw_arg(mut self, arg: impl AsRef<OsStr>) -> Self {
        #[cfg(windows)]
        std::os::windows::process::CommandExt::raw_arg(&mut self.cmd, arg);
        #[cfg(not(windows))]
        self.cmd.arg(arg);
        self
    }

    /// Windows CLIs installed through npm (`npx`, `claude`) ship as `.cmd` or `.bat` shims, which
    /// `CreateProcess` cannot start and `PATHEXT` never reaches; run those through
    /// `cmd.exe /D /C`, as the shell does. A no-op elsewhere and for anything that is an `.exe`
    /// or a path.
    pub fn windows_shim(mut self) -> Self {
        self.shim = true;
        self
    }

    /// Kill the child after `limit` and fail with [`io::ErrorKind::TimedOut`]. Only the child is
    /// killed, not what it started.
    pub fn timeout(mut self, limit: Duration) -> Self {
        self.timeout = Some(limit);
        self
    }

    pub fn progress(mut self, progress: Progress) -> Self {
        self.progress = progress;
        self
    }

    pub fn output(mut self, output: Output) -> Self {
        self.output = output;
        self
    }

    /// Run to the end. The indicator is cleared before this returns, so the caller's own
    /// output or error lands on a clean line.
    pub fn run(mut self) -> io::Result<Captured> {
        match self.output {
            Output::Capture | Output::Stream => Ok(self.run_piped(None)?.0),
            Output::Inherit => Ok(Captured {
                status: {
                    self.prepare(false);
                    self.cmd.status()?
                },
                stdout: Vec::new(),
                stderr: Vec::new(),
            }),
            Output::Detached => Err(use_spawn("a detached child")),
        }
    }

    /// [`Self::run`] that keeps at most `cap` bytes of stdout and stderr together. Bytes past it
    /// are still read, so the child never blocks on a full pipe, but are counted in the second
    /// value instead of stored: `yes | head -c 10G` must not take memory the unwrapped command
    /// would not.
    pub fn run_capped(self, cap: u64) -> io::Result<(Captured, u64)> {
        match self.output {
            Output::Capture | Output::Stream => self.run_piped(Some(cap)),
            Output::Inherit | Output::Detached => Err(use_spawn("a child that owns its output")),
        }
    }

    /// Start a child the caller owns. `Capture` pipes stdout and stderr without an indicator;
    /// `Detached` nulls them; `Inherit` shares ours.
    pub fn spawn(mut self) -> io::Result<Child> {
        match self.output {
            Output::Stream => return Err(use_spawn("a streamed child")),
            Output::Capture => {
                self.cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
            }
            Output::Detached => {
                self.cmd.stdout(Stdio::null()).stderr(Stdio::null());
            }
            Output::Inherit => {}
        }
        self.prepare(self.output != Output::Inherit);
        self.cmd.spawn()
    }

    /// Settings that wait for the last moment: the shim replaces the command, so stdin cannot be
    /// applied to it earlier. `closed` is the stdin of a child nobody feeds.
    fn prepare(&mut self, closed: bool) {
        if self.shim && cfg!(windows) && needs_cmd_host(&self.cmd.get_program().to_string_lossy()) {
            self.cmd = via_cmd_host(&self.cmd);
        }
        match self.stdin.take() {
            Some(stdin) => self.cmd.stdin(stdin),
            None if closed => self.cmd.stdin(Stdio::null()),
            None => &mut self.cmd,
        };
    }

    /// Waits for the child, not for end-of-file: after it exits, what its pipes already hold is
    /// read for up to [`DRAIN_AFTER_EXIT`], and a reader still blocked by a descendant is left
    /// behind with whatever it had read.
    fn run_piped(mut self, cap: Option<u64>) -> io::Result<(Captured, u64)> {
        self.cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
        self.prepare(true);
        let mut child = self.cmd.spawn()?;
        let ind: Arc<dyn Indicator> = match self.progress {
            Progress::Off => Arc::new(Hidden),
            _ => Arc::from(indicator::pick(&self.label)),
        };
        let parser = Arc::new(Mutex::new(match self.progress {
            Progress::Parse(p) => Some(p),
            Progress::Callback(f) => Some(Box::new(parse::Callback(f)) as Box<dyn ProgressParser>),
            _ => None,
        }));
        let kept = Arc::new(Kept {
            left: AtomicU64::new(cap.unwrap_or(u64::MAX)),
            dropped: AtomicU64::new(0),
            forward: self.output == Output::Stream,
        });
        let (done, finished) = mpsc::channel();
        let mut sinks = Vec::new();
        let pipes: [(Stream, Option<Box<dyn Read + Send>>); 2] = [
            (
                Stream::Stdout,
                child.stdout.take().map(|p| Box::new(p) as _),
            ),
            (
                Stream::Stderr,
                child.stderr.take().map(|p| Box::new(p) as _),
            ),
        ];
        for (which, pipe) in pipes {
            let sink = Arc::new(Mutex::new(Vec::new()));
            sinks.push(Arc::clone(&sink));
            let pump = Pump {
                which,
                ind: Arc::clone(&ind),
                parser: Arc::clone(&parser),
                kept: Arc::clone(&kept),
                sink,
            };
            let done = done.clone();
            std::thread::spawn(move || {
                pump.run(pipe);
                let _ = done.send(());
            });
        }
        drop(done);
        let status = wait(&mut child, self.timeout);
        let deadline = Instant::now() + DRAIN_AFTER_EXIT;
        for _ in 0..2 {
            let left = deadline.saturating_duration_since(Instant::now());
            if finished.recv_timeout(left).is_err() {
                break;
            }
        }
        ind.finish();
        let take = |b: &Mutex<Vec<u8>>| {
            std::mem::take(&mut *b.lock().unwrap_or_else(PoisonError::into_inner))
        };
        let (stdout, stderr) = (take(&sinks[0]), take(&sinks[1]));
        let dropped = kept.dropped.load(Ordering::Relaxed);
        Ok((
            Captured {
                status: status?,
                stdout,
                stderr,
            },
            dropped,
        ))
    }
}

/// True when `command` is a Windows batch or `PATHEXT` shim that `CreateProcess` cannot run: a
/// `.cmd` or `.bat` path, or a bare name (`npx`, `uvx`) that `PATHEXT` may resolve to one.
pub fn needs_cmd_host(command: &str) -> bool {
    let lower = command.to_ascii_lowercase();
    if lower.ends_with(".exe") || lower.ends_with(".com") {
        return false;
    }
    if lower.ends_with(".cmd") || lower.ends_with(".bat") {
        return true;
    }
    !command.contains(['/', '\\', ':'])
}

/// `cmd.exe /D /C <program> <args>` with the directory and environment of `old`.
fn via_cmd_host(old: &Command) -> Command {
    let mut cmd = Command::new("cmd.exe");
    cmd.arg("/D")
        .arg("/C")
        .arg(old.get_program())
        .args(old.get_args());
    if let Some(dir) = old.get_current_dir() {
        cmd.current_dir(dir);
    }
    for (key, val) in old.get_envs() {
        match val {
            Some(val) => cmd.env(key, val),
            None => cmd.env_remove(key),
        };
    }
    cmd
}

/// A bar over a known number of items, from [`ProgressRunner::batch`]. It counts finished
/// items: [`Batch::step`] finishes the previous one and names the next.
pub struct Batch {
    ind: Box<dyn Indicator>,
    total: u64,
    pos: u64,
    unit: Unit,
    running: bool,
}

impl Batch {
    fn new(ind: Box<dyn Indicator>, total: u64, unit: Unit) -> Self {
        Self {
            ind,
            total,
            pos: 0,
            unit,
            running: false,
        }
    }

    /// Items finished so far.
    pub fn position(&self) -> u64 {
        self.pos
    }

    pub fn total(&self) -> u64 {
        self.total
    }

    /// The previous item is done; `msg` names the one that starts now.
    pub fn step(&mut self, msg: &str) {
        self.complete();
        self.running = true;
        self.draw(Some(msg.to_string()));
    }

    /// The last item is done; the bar is cleared.
    pub fn finish(mut self) {
        self.complete();
        self.draw(None);
        self.ind.finish();
    }

    fn complete(&mut self) {
        if std::mem::take(&mut self.running) {
            self.pos = (self.pos + 1).min(self.total);
        }
    }

    fn draw(&self, msg: Option<String>) {
        self.ind.set(&Update {
            pos: self.pos,
            total: Some(self.total),
            unit: self.unit,
            msg,
        });
    }
}

fn use_spawn(what: &str) -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidInput,
        format!("{what} does not fit this call: see ProgressRunner::run and ::spawn"),
    )
}

fn wait(child: &mut Child, limit: Option<Duration>) -> io::Result<ExitStatus> {
    let Some(limit) = limit else {
        return child.wait();
    };
    let end = Instant::now() + limit;
    loop {
        if let Some(status) = child.try_wait()? {
            return Ok(status);
        }
        if Instant::now() >= end {
            let _ = child.kill();
            let _ = child.wait();
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "the command timed out",
            ));
        }
        std::thread::sleep(Duration::from_millis(10));
    }
}

type SharedParser = Mutex<Option<Box<dyn ProgressParser>>>;

/// What the two readers of one run share: a byte budget for both streams together, and the count
/// of bytes that did not fit.
struct Kept {
    left: AtomicU64,
    dropped: AtomicU64,
    forward: bool,
}

impl Kept {
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

/// One stream's reader: keeps it (or forwards it live), and feeds its lines to the parser.
struct Pump {
    which: Stream,
    ind: Arc<dyn Indicator>,
    parser: Arc<SharedParser>,
    kept: Arc<Kept>,
    sink: Arc<Mutex<Vec<u8>>>,
}

impl Pump {
    fn run(self, reader: Option<Box<dyn Read + Send>>) {
        let Some(mut reader) = reader else {
            return;
        };
        let mut buf = [0u8; 8192];
        let mut lines = parse::Lines::default();
        let mut feed = |line: &str| {
            let mut slot = self.parser.lock().unwrap_or_else(PoisonError::into_inner);
            if let Some(update) = slot.as_mut().and_then(|p| p.feed(self.which, line)) {
                self.ind.set(&update);
            }
        };
        loop {
            let n = match reader.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => n,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            };
            if self.kept.forward {
                self.ind.suspend(&mut || echo(self.which, &buf[..n]));
            } else {
                let keep = self.kept.take(n);
                if keep > 0 {
                    self.sink
                        .lock()
                        .unwrap_or_else(PoisonError::into_inner)
                        .extend_from_slice(&buf[..keep]);
                }
            }
            lines.push(&buf[..n], &mut feed);
        }
        lines.finish(&mut feed);
    }
}

fn echo(which: Stream, bytes: &[u8]) {
    let _ = match which {
        Stream::Stdout => io::stdout()
            .write_all(bytes)
            .and_then(|()| io::stdout().flush()),
        Stream::Stderr => io::stderr()
            .write_all(bytes)
            .and_then(|()| io::stderr().flush()),
    };
}

#[cfg(all(test, unix))]
mod tests {
    use std::sync::Arc;

    use super::*;

    fn sh(script: &str) -> ProgressRunner {
        ProgressRunner::new("testing…", "sh", ["-c", script])
    }

    #[test]
    fn capture_returns_what_command_output_would() {
        let got = sh("printf out; printf err >&2; exit 3").run().unwrap();
        let want = Command::new("sh")
            .args(["-c", "printf out; printf err >&2; exit 3"])
            .output()
            .unwrap();
        assert_eq!(got, want);
        assert_eq!(got.status.code(), Some(3));
    }

    #[test]
    fn a_missing_program_is_the_spawn_error() {
        let err = ProgressRunner::quiet("rtok-no-such-program", ["x"])
            .run()
            .unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
    }

    #[test]
    fn stdin_is_closed_unless_given_and_cwd_and_env_reach_the_child() {
        let closed = sh("cat").run().unwrap();
        assert!(closed.status.success() && closed.stdout.is_empty());
        let dir = std::env::temp_dir();
        let got = sh("printf '%s|%s' \"$PWD\" \"$T276\"")
            .cwd(&dir)
            .env("T276", "v")
            .run()
            .unwrap();
        let text = String::from_utf8_lossy(&got.stdout).into_owned();
        assert!(text.ends_with("|v"), "{text}");
    }

    #[test]
    fn a_slow_child_is_killed_at_the_timeout() {
        let start = Instant::now();
        let err = sh("exec sleep 5")
            .timeout(Duration::from_millis(100))
            .run()
            .unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::TimedOut);
        assert!(start.elapsed() < Duration::from_secs(3));
        assert!(
            sh("exit 0")
                .timeout(Duration::from_secs(5))
                .run()
                .unwrap()
                .status
                .success()
        );
    }

    struct Record(Arc<Mutex<Vec<(Stream, String)>>>);

    impl ProgressParser for Record {
        fn feed(&mut self, stream: Stream, line: &str) -> Option<Update> {
            self.0.lock().unwrap().push((stream, line.to_string()));
            None
        }
    }

    #[test]
    fn the_parser_sees_both_streams_split_on_carriage_returns() {
        let seen = Arc::new(Mutex::new(Vec::new()));
        let done = sh("printf 'a\\rb\\n' >&2; printf 'c\\n'")
            .progress(Progress::Parse(Box::new(Record(seen.clone()))))
            .run()
            .unwrap();
        assert_eq!(done.stdout, b"c\n");
        assert_eq!(done.stderr, b"a\rb\n");
        let mut got = seen.lock().unwrap().clone();
        got.sort_by_key(|(_, l)| l.clone());
        let want = [
            (Stream::Stderr, "a"),
            (Stream::Stderr, "b"),
            (Stream::Stdout, "c"),
        ];
        assert_eq!(got, want.map(|(s, l)| (s, l.to_string())));
    }

    #[test]
    fn stream_and_inherit_leave_nothing_to_capture() {
        let streamed = sh("exit 4").output(Output::Stream).run().unwrap();
        assert_eq!(
            (streamed.status.code(), streamed.stdout.len()),
            (Some(4), 0)
        );
        let inherited = sh("exit 0").output(Output::Inherit).run().unwrap();
        assert!(inherited.status.success());
    }

    #[test]
    fn spawn_and_run_refuse_the_modes_that_do_not_fit() {
        let child = sh("exit 0").output(Output::Detached).spawn().unwrap();
        let out = child.wait_with_output().unwrap();
        assert!(out.status.success() && out.stdout.is_empty());
        let err = sh("exit 0").output(Output::Detached).run().unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
        let err = sh("exit 0").output(Output::Stream).spawn().unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
        let piped = sh("printf x").spawn().unwrap().wait_with_output().unwrap();
        assert_eq!(piped.stdout, b"x");
    }

    struct Seen(Arc<Mutex<Vec<Update>>>);

    impl Indicator for Seen {
        fn set(&self, update: &Update) {
            self.0.lock().unwrap().push(update.clone());
        }
        fn message(&self, _: &str) {}
        fn suspend(&self, f: &mut dyn FnMut()) {
            f();
        }
        fn finish(&self) {
            self.0.lock().unwrap().push(Update {
                pos: u64::MAX,
                total: None,
                unit: Unit::Items,
                msg: None,
            });
        }
    }

    #[test]
    fn a_batch_counts_finished_items_and_names_the_running_one() {
        let seen = Arc::new(Mutex::new(Vec::new()));
        let mut batch = Batch::new(Box::new(Seen(seen.clone())), 2, Unit::Items);
        assert_eq!((batch.position(), batch.total()), (0, 2));
        batch.step("a");
        assert_eq!(batch.position(), 0);
        batch.step("b");
        assert_eq!(batch.position(), 1);
        batch.finish();
        let got: Vec<_> = seen
            .lock()
            .unwrap()
            .iter()
            .map(|u| (u.pos, u.total, u.msg.clone()))
            .collect();
        let want = [
            (0, Some(2), Some("a")),
            (1, Some(2), Some("b")),
            (2, Some(2), None),
            (u64::MAX, None, None),
        ];
        let want: Vec<_> = want
            .map(|(p, t, m)| (p, t, m.map(String::from)))
            .into_iter()
            .collect();
        assert_eq!(got, want);
    }

    #[test]
    fn a_batch_never_counts_past_its_total_and_a_hidden_one_draws_nothing() {
        let mut batch = Batch::new(Box::new(Hidden), 1, Unit::Items);
        for item in ["a", "b", "c"] {
            batch.step(item);
        }
        assert_eq!(batch.position(), 1);
        batch.finish();
        // Under test stderr is not a terminal, so the runner's own pick is Hidden too.
        ProgressRunner::batch("x…", 3, Unit::Items).finish();
    }

    #[test]
    fn a_batch_bar_and_a_command_spinner_share_one_target() {
        let multi =
            indicatif::MultiProgress::with_draw_target(indicatif::ProgressDrawTarget::hidden());
        let bar = TtyIndicator::bar(&multi, "removing…", 3, Unit::Items);
        let spinner = TtyIndicator::new(&multi, "git…");
        bar.set(&Update {
            pos: 1,
            total: Some(3),
            unit: Unit::Items,
            msg: Some("x".into()),
        });
        spinner.finish();
        bar.finish();
    }

    #[test]
    fn a_callback_reads_both_streams_and_can_end_the_spinner_phase() {
        let seen = Arc::new(Mutex::new(Vec::new()));
        let log = seen.clone();
        let cb = move |s: Stream, line: &str| {
            log.lock().unwrap().push((s, line.to_string()));
            None
        };
        let done = sh("printf 'one\\n'; printf 'two\\n' >&2")
            .progress(Progress::Callback(Box::new(cb)))
            .run()
            .unwrap();
        assert_eq!(
            (done.stdout.as_slice(), done.stderr.as_slice()),
            (&b"one\n"[..], &b"two\n"[..])
        );
        let mut got = seen.lock().unwrap().clone();
        got.sort_by_key(|(_, l)| l.clone());
        assert_eq!(
            got,
            [
                (Stream::Stdout, "one".to_string()),
                (Stream::Stderr, "two".to_string())
            ]
        );
    }

    #[test]
    fn the_cmd_host_wraps_npm_shims_not_exes() {
        assert!(needs_cmd_host("npx"));
        assert!(needs_cmd_host("uvx"));
        assert!(needs_cmd_host(r"C:\Program Files\nodejs\npx.cmd"));
        assert!(needs_cmd_host("tool.bat"));
        assert!(!needs_cmd_host("rtok.exe"));
        assert!(!needs_cmd_host(r"C:\Users\u\.ketch\bin\rtok.exe"));
        assert!(!needs_cmd_host("/usr/bin/uvx"));
    }

    #[test]
    fn the_cmd_host_command_keeps_args_directory_and_environment() {
        let mut old = Command::new("npx");
        old.args(["-y", "pkg"]).current_dir("/tmp").env("A", "1");
        old.env_remove("B");
        let new = via_cmd_host(&old);
        assert_eq!(new.get_program(), "cmd.exe");
        let args: Vec<_> = new.get_args().collect();
        assert_eq!(args, ["/D", "/C", "npx", "-y", "pkg"]);
        assert_eq!(new.get_current_dir(), Some(Path::new("/tmp")));
        let envs: Vec<_> = new.get_envs().collect();
        assert_eq!(
            envs,
            [
                (OsStr::new("A"), Some(OsStr::new("1"))),
                (OsStr::new("B"), None)
            ]
        );
    }

    #[test]
    fn the_shim_is_a_no_op_off_windows() {
        let got = ProgressRunner::quiet("sh", ["-c", "cat; printf ok"])
            .windows_shim()
            .stdin(Stdio::null())
            .run()
            .unwrap();
        assert_eq!(got.stdout, b"ok");
    }

    /// T276.4: a backgrounded grandchild keeps the pipes open after the shell exits; `.run()`
    /// returns on the shell's exit with its output, not at end-of-file.
    #[test]
    fn a_grandchild_holding_the_pipe_does_not_hold_the_run() {
        let start = Instant::now();
        let got = sh("sleep 5 & printf out").run().unwrap();
        assert!(start.elapsed() < Duration::from_secs(3));
        assert_eq!(got.stdout, b"out");
    }

    /// T276.4: bytes past the cap are read and counted, not stored; the exit status survives.
    #[test]
    fn a_capped_run_keeps_the_cap_and_counts_the_rest() {
        let (got, dropped) = sh("head -c 5000 /dev/zero | tr '\\0' x; exit 2")
            .run_capped(1000)
            .unwrap();
        assert_eq!((got.stdout.len(), dropped), (1000, 4000));
        assert_eq!(got.status.code(), Some(2));
        let (got, dropped) = sh("printf hi").run_capped(1000).unwrap();
        assert_eq!((got.stdout.as_slice(), dropped), (&b"hi"[..], 0));
        let err = sh("exit 0")
            .output(Output::Inherit)
            .run_capped(1)
            .unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn a_raw_arg_reaches_the_child_as_one_argument() {
        let got = ProgressRunner::quiet("sh", ["-c"])
            .raw_arg("printf '%s' \"$0\"")
            .run()
            .unwrap();
        assert_eq!(got.stdout, b"sh");
    }
}
