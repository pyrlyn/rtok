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
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub use indicator::{Hidden, Indicator, TtyIndicator, disable_progress, suspend};
pub use parse::{GitProgress, Percent, ProgressParser, Stream, Unit, Update};

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
}

pub struct ProgressRunner {
    label: String,
    cmd: Command,
    output: Output,
    progress: Progress,
    timeout: Option<Duration>,
    stdin_set: bool,
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
            stdin_set: false,
        }
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
        self.cmd.stdin(stdin);
        self.stdin_set = true;
        self
    }

    /// Kill the child after `limit` and fail with [`io::ErrorKind::TimedOut`]. Only the child is
    /// killed, not what it started: a grandchild that keeps the pipes open delays `.run()`.
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
            Output::Capture | Output::Stream => self.run_piped(),
            Output::Inherit => Ok(Captured {
                status: self.cmd.status()?,
                stdout: Vec::new(),
                stderr: Vec::new(),
            }),
            Output::Detached => Err(use_spawn("a detached child")),
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
        if !self.stdin_set && self.output != Output::Inherit {
            self.cmd.stdin(Stdio::null());
        }
        self.cmd.spawn()
    }

    fn run_piped(mut self) -> io::Result<Captured> {
        self.cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
        if !self.stdin_set {
            self.cmd.stdin(Stdio::null());
        }
        let mut child = self.cmd.spawn()?;
        let ind = match self.progress {
            Progress::Off => Box::new(Hidden) as Box<dyn Indicator>,
            _ => indicator::pick(&self.label),
        };
        let parser = Mutex::new(match self.progress {
            Progress::Parse(p) => Some(p),
            _ => None,
        });
        let forward = self.output == Output::Stream;
        let (out, err) = (child.stdout.take(), child.stderr.take());
        let (ind, parser) = (&*ind, &parser);
        let (stdout, stderr, status) = std::thread::scope(|s| {
            let out = s.spawn(move || pump(out, Stream::Stdout, ind, parser, forward));
            let err = s.spawn(move || pump(err, Stream::Stderr, ind, parser, forward));
            let status = wait(&mut child, self.timeout);
            (
                out.join().unwrap_or_default(),
                err.join().unwrap_or_default(),
                status,
            )
        });
        ind.finish();
        Ok(Captured {
            status: status?,
            stdout,
            stderr,
        })
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

/// Drain one stream: keep it (or forward it live), and feed its lines to the parser.
fn pump(
    reader: Option<impl Read>,
    which: Stream,
    ind: &dyn Indicator,
    parser: &SharedParser,
    forward: bool,
) -> Vec<u8> {
    let (mut kept, mut buf) = (Vec::new(), [0u8; 8192]);
    let Some(mut reader) = reader else {
        return kept;
    };
    let mut lines = parse::Lines::default();
    let mut feed = |line: &str| {
        let mut slot = parser
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(update) = slot.as_mut().and_then(|p| p.feed(which, line)) {
            ind.set(&update);
        }
    };
    loop {
        let n = match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => break,
        };
        if forward {
            ind.suspend(&mut || echo(which, &buf[..n]));
        } else {
            kept.extend_from_slice(&buf[..n]);
        }
        lines.push(&buf[..n], &mut feed);
    }
    lines.finish(&mut feed);
    kept
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
}
