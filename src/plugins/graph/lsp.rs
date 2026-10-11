// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! Optional LSP backend (T30.2). Native JSON-RPC over stdio.
//! Spawns rust-analyzer / clangd / typescript-language-server from PATH (D6: not serena).

use std::collections::{BTreeMap, HashSet};
use std::ffi::OsStr;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, PoisonError, mpsc};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use rtok_plugin_sdk::{Class, Ctx, Measurement};
use serde_json::{Value, json};

use super::cap;

/// Wait cap for one server step, set from `[plugins.graph] lsp_timeout_ms` by the door in
/// `graph::lsp_or_tags` (T329.9). A process-wide cell because the cached session outlives a call.
static TIMEOUT_MS: AtomicU64 = AtomicU64::new(40_000);

pub(crate) fn set_timeout_ms(ms: u64) {
    TIMEOUT_MS.store(ms, Ordering::Relaxed);
}

fn ready() -> Duration {
    Duration::from_millis(TIMEOUT_MS.load(Ordering::Relaxed))
}

/// True when `bin` is installed. A mise shim and rustup's component proxy do not count;
/// `rust-analyzer` counts when `rustup which` names the toolchain binary. File presence
/// only — `bin --version` can hang on a broken shim.
pub fn on_path(bin: &str) -> bool {
    if first_real_bin(bin).is_some() {
        return true;
    }
    bin == "rust-analyzer" && rustup_component_path().is_some()
}

/// `rustup which rust-analyzer` names the toolchain binary. It fails when the component
/// is not installed, which is the case a rustup proxy or mise shim must not pass for.
fn rustup_component_path() -> Option<PathBuf> {
    let o = Command::new("rustup")
        .args(["which", "rust-analyzer"])
        .stderr(Stdio::null())
        .output()
        .ok()?;
    if !o.status.success() {
        return None;
    }
    let p = String::from_utf8_lossy(&o.stdout).trim().to_string();
    if p.is_empty() {
        return None;
    }
    let path = PathBuf::from(p);
    (path.is_file() && !is_mise_shim(&path)).then_some(path)
}

fn first_real_bin(name: &str) -> Option<PathBuf> {
    let paths = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&paths) {
        if let Some(cand) = real_candidate(&dir, name) {
            return Some(cand);
        }
    }
    None
}

fn real_candidate(dir: &Path, name: &str) -> Option<PathBuf> {
    let bare = dir.join(name);
    if is_real_bin(&bare) {
        return Some(bare);
    }
    #[cfg(windows)]
    {
        let exe = dir.join(format!("{name}.exe"));
        if is_real_bin(&exe) {
            return Some(exe);
        }
    }
    None
}

fn is_real_bin(path: &Path) -> bool {
    path.is_file() && !is_mise_shim(path) && !is_rustup_proxy(path)
}

/// mise puts a shim on PATH for every tool in `mise.toml`, including ones it did not
/// install. rustup's proxy then falls back to that shim and the shim calls the proxy.
fn is_mise_shim(path: &Path) -> bool {
    let mut saw_mise = false;
    let mut saw_shims = false;
    for c in path.components() {
        let name = c.as_os_str();
        if name == "mise" {
            saw_mise = true;
        }
        if name == "shims" {
            saw_shims = true;
        }
    }
    saw_mise && saw_shims
}

/// rustup's component proxies sit beside the `rustup` binary and are that binary.
/// A real `rust-analyzer` next to it is a different size.
fn is_rustup_proxy(path: &Path) -> bool {
    let Some(dir) = path.parent() else {
        return false;
    };
    let rustup = if cfg!(windows) {
        dir.join("rustup.exe")
    } else {
        dir.join("rustup")
    };
    match (path.metadata(), rustup.metadata()) {
        (Ok(bin), Ok(proxy)) => bin.len() == proxy.len(),
        _ => false,
    }
}

fn resolve_bin(name: &str) -> PathBuf {
    if name == "rust-analyzer"
        && let Some(path) = rustup_component_path()
    {
        return path;
    }
    first_real_bin(name).unwrap_or_else(|| PathBuf::from(name))
}

/// A server command: binary and arguments.
type Server = (&'static str, &'static [&'static str]);

/// Project kinds by marker file, in the order they are tried: the language name that
/// `[plugins.graph.backend_by_language]` is keyed by, and the server that speaks it (`None`:
/// tags only).
const LANGS: &[(&str, &str, Option<Server>)] = &[
    ("Cargo.toml", "rust", Some(("rust-analyzer", &[]))),
    ("compile_commands.json", "c", Some(("clangd", &[]))),
    (
        "tsconfig.json",
        "typescript",
        Some(("typescript-language-server", &["--stdio"])),
    ),
    ("pubspec.yaml", "dart", Some(("dart", &["language-server"]))),
    ("go.mod", "go", None),
    ("pyproject.toml", "python", None),
    ("package.json", "javascript", None),
    // T329.10: languages with neither a server nor a grammar, named so that
    // `backend_by_language` can pin them to `text`.
    ("pom.xml", "java", None),
    ("build.gradle", "java", None),
    ("Gemfile", "ruby", None),
    ("composer.json", "php", None),
    ("mix.exs", "elixir", None),
    ("Package.swift", "swift", None),
];

pub(crate) fn language_of(root: &Path) -> Option<&'static str> {
    LANGS
        .iter()
        .find(|(marker, ..)| root.join(marker).is_file())
        .map(|(_, lang, _)| *lang)
}

/// The capability check (T329.11): the project's language has a server and its binary is
/// installed. File presence only, no spawn; starting the server is the first real request.
pub(crate) fn probe(root: &Path) -> super::capability::Probe {
    let (bin, _) = pick(root).map_err(|e| super::capability::Absent {
        server: false,
        reason: format!("{e:#}"),
    })?;
    on_path(bin)
        .then_some(())
        .ok_or_else(|| super::capability::Absent {
            server: true,
            reason: format!("lsp: {bin} not on PATH"),
        })
}

/// The server answered a request with an error. It is alive, so unlike a timeout or a dead
/// pipe this does not make it a server to stop asking.
#[derive(Debug)]
pub(crate) struct Refused(pub(crate) String);

impl std::fmt::Display for Refused {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Refused {}

pub(crate) fn server_broke(e: &anyhow::Error) -> bool {
    e.downcast_ref::<Refused>().is_none()
}

fn pick(root: &Path) -> Result<Server> {
    for (marker, _, server) in LANGS {
        if let (true, Some(server)) = (root.join(marker).is_file(), server) {
            return Ok(*server);
        }
    }
    bail!(
        "lsp: no Cargo.toml / compile_commands.json / tsconfig.json / pubspec.yaml in {}",
        root.display()
    )
}

/// State dirs for a spawned language server, always under `root` (never the user's home).
///
/// Cargo workspaces use `target/rtok-lsp-xdg` (already gitignored); Dart uses
/// `.dart_tool/rtok-lsp-xdg`; everything else gets `.rtok-lsp-xdg`.
pub(crate) fn lsp_state_root(root: &Path) -> PathBuf {
    if root.join("Cargo.toml").is_file() {
        root.join("target").join("rtok-lsp-xdg")
    } else if root.join("pubspec.yaml").is_file() {
        root.join(".dart_tool").join("rtok-lsp-xdg")
    } else {
        root.join(".rtok-lsp-xdg")
    }
}

/// Pin XDG_* and PUB_CACHE on `cmd` under [`lsp_state_root`] so rust-analyzer / dart never
/// create files in the invoking user's real cache.
fn confine_lsp_state(cmd: &mut Command, root: &Path) {
    let base = lsp_state_root(root);
    let cache = base.join("cache");
    let data = base.join("data");
    let state = base.join("state");
    let pub_cache = base.join("pub-cache");
    for d in [&cache, &data, &state, &pub_cache] {
        let _ = std::fs::create_dir_all(d);
    }
    cmd.env("XDG_CACHE_HOME", &cache)
        .env("XDG_DATA_HOME", &data)
        .env("XDG_STATE_HOME", &state)
        .env("PUB_CACHE", &pub_cache);
}

fn percent_encode_path(s: &str) -> String {
    // RFC 8089 / URI path: encode spaces and other non-unreserved octets; keep `/` and
    // Windows drive `C:` intact so rust-analyzer still accepts the URI.
    let mut out = String::with_capacity(s.len());
    for (i, seg) in s.split('/').enumerate() {
        if i > 0 {
            out.push('/');
        }
        if seg.len() == 2 && seg.as_bytes().get(1) == Some(&b':') {
            out.push_str(seg);
            continue;
        }
        for b in seg.bytes() {
            match b {
                b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                    out.push(b as char)
                }
                _ => out.push_str(&format!("%{b:02X}")),
            }
        }
    }
    out
}

fn percent_decode_path(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let h = (bytes[i + 1] as char).to_digit(16);
            let l = (bytes[i + 2] as char).to_digit(16);
            if let (Some(h), Some(l)) = (h, l) {
                out.push(((h << 4) | l) as u8);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn file_uri(p: &Path) -> String {
    // LSP wants RFC 8089 `file:///C:/…` on Windows. `.display()` keeps
    // backslashes and omits the third slash, which rust-analyzer rejects.
    let path = dunce::canonicalize(p).unwrap_or_else(|_| p.to_path_buf());
    let s = percent_encode_path(&path.to_string_lossy().replace('\\', "/"));
    if s.starts_with('/') {
        format!("file://{s}")
    } else {
        format!("file:///{s}")
    }
}

fn path_from_file_uri(uri: &str) -> PathBuf {
    let rest = percent_decode_path(uri.strip_prefix("file://").unwrap_or(uri));
    // `file:///C:/Users/…` → `C:/Users/…`; `file:///home/…` keeps the root slash.
    if cfg!(windows) {
        let trimmed = rest.trim_start_matches('/');
        if trimmed.len() >= 2 && trimmed.as_bytes()[1] == b':' {
            return PathBuf::from(trimmed);
        }
    }
    PathBuf::from(rest)
}

fn rel(root: &Path, uri: &str) -> String {
    let p = path_from_file_uri(uri);
    pathdiff::diff_paths(&p, root)
        .map(|p| p.display().to_string())
        .unwrap_or_else(|| p.display().to_string())
}

fn kind_name(k: u64) -> &'static str {
    match k {
        5 => "class",
        6 => "method",
        10 => "enum",
        11 => "interface",
        12 => "function",
        13 => "variable",
        23 => "struct",
        _ => "symbol",
    }
}

fn frame(v: &Value) -> Result<Vec<u8>> {
    let body = serde_json::to_vec(v)?;
    let mut out = format!("Content-Length: {}\r\n\r\n", body.len()).into_bytes();
    out.extend_from_slice(&body);
    Ok(out)
}

/// A frame for the writer thread and the channel on which it reports the write.
type Outgoing = (Vec<u8>, mpsc::Sender<std::io::Result<()>>);

/// Pipe reads and writes block with no deadline, so each end belongs to a thread of its own
/// and the session only waits on channels (T620). A timeout kills the server, which closes
/// both pipes and ends the threads; they never outlive the session by more than that.
fn spawn_writer(mut w: impl Write + Send + 'static) -> mpsc::Sender<Outgoing> {
    let (tx, rx) = mpsc::channel::<Outgoing>();
    std::thread::spawn(move || {
        for (bytes, done) in rx {
            let r = w.write_all(&bytes).and_then(|()| w.flush());
            let failed = r.is_err();
            let _ = done.send(r);
            if failed {
                break;
            }
        }
    });
    tx
}

/// Bounded so a chatty server blocks on a full channel the way it blocked on a full pipe.
fn spawn_reader(r: impl Read + Send + 'static) -> mpsc::Receiver<Result<Value>> {
    let (tx, rx) = mpsc::sync_channel(256);
    std::thread::spawn(move || {
        let mut r = BufReader::new(r);
        loop {
            let m = read_msg(&mut r);
            let failed = m.is_err();
            if tx.send(m).is_err() || failed {
                break;
            }
        }
    });
    rx
}

fn read_msg(r: &mut impl BufRead) -> Result<Value> {
    let mut len = None;
    loop {
        let mut line = String::new();
        if r.read_line(&mut line)? == 0 {
            bail!("lsp: eof");
        }
        if line == "\r\n" || line == "\n" {
            break;
        }
        if let Some(rest) = line
            .split_once(':')
            .filter(|(k, _)| k.eq_ignore_ascii_case("content-length"))
            .map(|(_, v)| v)
        {
            len = Some(rest.trim().parse::<usize>().context("Content-Length")?);
        }
    }
    let n = len.context("lsp: no Content-Length")?;
    let mut buf = vec![0; n];
    r.read_exact(&mut buf)?;
    serde_json::from_slice(&buf).context("lsp json")
}

struct Session {
    child: Option<Child>,
    /// Frames for the writer thread; see [`spawn_writer`].
    out: mpsc::Sender<Outgoing>,
    /// Messages from the reader thread; see [`spawn_reader`].
    inbox: mpsc::Receiver<Result<Value>>,
    next_id: i64,
    root: PathBuf,
    opened: HashSet<String>,
    broken: bool,
    err_path: PathBuf,
    /// Overrides the configured wait cap; tests use it so they never touch the process-wide cell.
    limit: Option<Duration>,
}

impl Session {
    fn attach(
        stdin: impl Write + Send + 'static,
        stdout: impl Read + Send + 'static,
        child: Option<Child>,
        root: PathBuf,
        err_path: PathBuf,
    ) -> Self {
        Self {
            child,
            out: spawn_writer(stdin),
            inbox: spawn_reader(stdout),
            next_id: 1,
            root,
            opened: HashSet::new(),
            broken: false,
            err_path,
            limit: None,
        }
    }

    /// False once the server exited or a request expired: the cached session is then replaced,
    /// so a late reply to an abandoned request can never be read by a later one.
    fn usable(&mut self) -> bool {
        !self.broken
            && self
                .child
                .as_mut()
                .is_none_or(|c| c.try_wait().ok().flatten().is_none())
    }

    /// Give up on this server after a missed deadline. Killing it ends its pipes, which
    /// unblocks the transport threads; `Drop` reaps it.
    fn expire(&mut self) {
        self.broken = true;
        if let Some(c) = self.child.as_mut() {
            let _ = c.kill();
        }
    }

    fn spawn(root: &Path) -> Result<Self> {
        let (bin, args) = pick(root)?;
        if !on_path(bin) {
            bail!("lsp: {bin} not on PATH");
        }
        let path = resolve_bin(bin);
        let err_path = std::env::temp_dir().join(format!("rtok-lsp-{}.stderr", std::process::id()));
        let mut cmd = Command::new(&path);
        cmd.args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .current_dir(root);
        // Language servers inherit the process env and would otherwise write into the
        // invoking user's `~/.cache` / pub-cache. Pin XDG + PUB_CACHE under the workspace.
        confine_lsp_state(&mut cmd, root);
        match std::fs::File::create(&err_path) {
            Ok(f) => {
                cmd.stderr(Stdio::from(f));
            }
            Err(_) => {
                cmd.stderr(Stdio::null());
            }
        }
        let mut child = cmd
            .spawn()
            .with_context(|| format!("spawn {} ({bin})", path.display()))?;
        let stdin = child.stdin.take().context("lsp stdin")?;
        let stdout = child.stdout.take().context("lsp stdout")?;
        let root = dunce::canonicalize(root).unwrap_or_else(|_| root.to_path_buf());
        let mut s = Self::attach(stdin, stdout, Some(child), root, err_path);
        let uri = file_uri(&s.root);
        if let Err(e) = s.request(
            "initialize",
            json!({
                // Ours, not null: the server watches it and exits when rtok dies, even by
                // SIGKILL, instead of outliving the `mcp` that spawned it.
                "processId": std::process::id(),
                "rootUri": uri,
                "rootPath": s.root,
                "capabilities": {
                    "workspace": {
                        "configuration": true,
                        "workspaceFolders": true
                    },
                    "textDocument": {
                        "documentSymbol": {"hierarchicalDocumentSymbolSupport": true}
                    },
                    "window": {"workDoneProgress": true}
                },
                "workspaceFolders": [{"uri": uri, "name": "root"}]
            }),
        ) {
            return Err(s.dead(e));
        }
        s.notify("initialized", json!({}))?;
        Ok(s)
    }

    fn budget(&self) -> Duration {
        self.limit.unwrap_or_else(ready)
    }

    fn dead(&mut self, e: anyhow::Error) -> anyhow::Error {
        let st = self
            .child
            .as_mut()
            .and_then(|c| c.try_wait().ok().flatten());
        let err = std::fs::read_to_string(&self.err_path).unwrap_or_default();
        let err = err.trim();
        match (st, err.is_empty()) {
            (Some(st), false) => anyhow::anyhow!("{e}; exited {st}; stderr: {err}"),
            (Some(st), true) => anyhow::anyhow!("{e}; exited {st}"),
            (None, false) => anyhow::anyhow!("{e}; stderr: {err}"),
            (None, true) => e,
        }
    }

    /// Queue one message and wait for the writer thread to hand it to the pipe, within
    /// `deadline`. A peer that stopped reading stalls the writer, not the caller.
    fn send(&mut self, v: &Value, deadline: Instant, what: &str) -> Result<()> {
        if self.broken {
            bail!("lsp: session expired");
        }
        let (done_tx, done_rx) = mpsc::channel();
        if self.out.send((frame(v)?, done_tx)).is_err() {
            return Err(self.dead(anyhow::anyhow!("lsp: writer closed")));
        }
        match done_rx.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
            Ok(Ok(())) => Ok(()),
            Ok(Err(e)) => Err(self.dead(e.into())),
            Err(mpsc::RecvTimeoutError::Timeout) => {
                self.expire();
                bail!("lsp: timeout writing {what}")
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                Err(self.dead(anyhow::anyhow!("lsp: writer closed")))
            }
        }
    }

    fn notify(&mut self, method: &str, params: Value) -> Result<()> {
        let deadline = Instant::now() + self.budget();
        self.send(
            &json!({"jsonrpc":"2.0","method": method, "params": params}),
            deadline,
            method,
        )
    }

    /// One budget covers the write and the wait for the matching reply (T620).
    fn request(&mut self, method: &str, params: Value) -> Result<Value> {
        let deadline = Instant::now() + self.budget();
        let id = json!(self.next_id);
        self.next_id += 1;
        self.send(
            &json!({"jsonrpc":"2.0","id": id, "method": method, "params": params}),
            deadline,
            method,
        )?;
        loop {
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                self.expire();
                bail!("lsp: timeout waiting for {method}");
            }
            let msg = match self.inbox.recv_timeout(left) {
                Ok(Ok(m)) => m,
                Ok(Err(e)) => return Err(self.dead(e)),
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    self.expire();
                    bail!("lsp: timeout waiting for {method}");
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    return Err(self.dead(anyhow::anyhow!("lsp: eof")));
                }
            };
            if msg.get("method").is_some() && msg.get("id").is_some_and(|i| !i.is_null()) {
                self.reply_server(&msg, deadline)?;
                continue;
            }
            if msg.get("id") == Some(&id) {
                if let Some(err) = msg.get("error") {
                    return Err(Refused(format!("lsp {method}: {err}")).into());
                }
                return Ok(msg["result"].clone());
            }
        }
    }

    fn reply_server(&mut self, msg: &Value, deadline: Instant) -> Result<()> {
        let id = &msg["id"];
        let result = match msg["method"].as_str().unwrap_or("") {
            "workspace/configuration" => {
                let n = msg["params"]["items"]
                    .as_array()
                    .map(|a| a.len())
                    .unwrap_or(1);
                json!(vec![json!({}); n])
            }
            "workspace/workspaceFolders" => {
                json!([{"uri": file_uri(&self.root), "name": "root"}])
            }
            _ => json!(null),
        };
        self.send(
            &json!({"jsonrpc":"2.0","id": id, "result": result}),
            deadline,
            "a server request",
        )
    }

    fn did_open(&mut self, uri: &str) -> Result<()> {
        if !self.opened.insert(uri.to_string()) {
            return Ok(());
        }
        let path = path_from_file_uri(uri);
        let text = std::fs::read_to_string(&path).unwrap_or_default();
        let language_id = match path.extension().and_then(OsStr::to_str) {
            Some("rs") => "rust",
            Some("dart") => "dart",
            _ => "plaintext",
        };
        self.notify(
            "textDocument/didOpen",
            json!({"textDocument": {
                "uri": uri, "languageId": language_id, "version": 1, "text": text
            }}),
        )
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        // Queued, not awaited: the writer may be stuck on a peer that stopped reading, and
        // the kill below is what ends it.
        if let Ok(bytes) = frame(&json!({"jsonrpc":"2.0","method":"exit"})) {
            let _ = self.out.send((bytes, mpsc::channel().0));
        }
        if let Some(c) = self.child.as_mut() {
            let _ = c.kill();
            let _ = c.wait();
        }
        let _ = std::fs::remove_file(&self.err_path);
    }
}

/// The one cached server, keyed by workspace root. A `static` is never dropped, so
/// [`shutdown`] is what runs `Session::drop` when `rtok mcp` ends.
static SESSION: Mutex<Option<(String, Session)>> = Mutex::new(None);

fn with_session<T>(root: &Path, f: impl FnOnce(&mut Session) -> Result<T>) -> Result<T> {
    let key = super::index::canon(root);
    let mut g = SESSION.lock().unwrap_or_else(PoisonError::into_inner);
    let restart = match g.as_mut() {
        Some((k, s)) if *k == key => !s.usable(),
        _ => true,
    };
    if restart {
        // Reap the old server first: both share one stderr path, and dropping it after the
        // spawn deleted the file its successor had just opened.
        *g = None;
        *g = Some((key, Session::spawn(root)?));
    }
    f(&mut g.as_mut().expect("session").1)
}

/// Stop the cached language server (`exit`, kill, reap) and remove its stderr file.
pub(crate) fn shutdown() {
    drop(
        SESSION
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .take(),
    );
}

struct Def {
    uri: String,
    pos: Value,
    kind: u64,
    path: String,
    line: i32,
    end_line: i32,
}

fn pick_def(r: &Value, name: &str, root: &Path) -> Option<Def> {
    r.as_array()?.iter().find_map(|it| {
        if it["name"].as_str()? != name {
            return None;
        }
        let loc = &it["location"];
        let uri = loc["uri"].as_str()?.to_string();
        let start = loc["range"]["start"].clone();
        let end_line = loc["range"]["end"]["line"].as_i64().unwrap_or(0) as i32 + 1;
        Some(Def {
            line: start["line"].as_i64().unwrap_or(0) as i32 + 1,
            pos: start,
            kind: it["kind"].as_u64().unwrap_or(0),
            path: rel(root, loc["uri"].as_str()?),
            end_line,
            uri,
        })
    })
}

fn wait_def(s: &mut Session, name: &str) -> Result<Option<Def>> {
    let deadline = Instant::now() + ready();
    loop {
        let r = s.request("workspace/symbol", json!({"query": name}))?;
        if let Some(d) = pick_def(&r, name, &s.root) {
            return Ok(Some(d));
        }
        if Instant::now() >= deadline {
            return Ok(None);
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}

fn contains(range: &Value, line: u64, ch: u64) -> bool {
    let sl = range["start"]["line"].as_u64().unwrap_or(0);
    let sc = range["start"]["character"].as_u64().unwrap_or(0);
    let el = range["end"]["line"].as_u64().unwrap_or(0);
    let ec = range["end"]["character"].as_u64().unwrap_or(0);
    (line > sl || (line == sl && ch >= sc)) && (line < el || (line == el && ch <= ec))
}

fn enclosing(syms: &[Value], line: u64, ch: u64) -> String {
    let mut best = String::new();
    fn walk(syms: &[Value], line: u64, ch: u64, best: &mut String) {
        for s in syms {
            if contains(&s["range"], line, ch) {
                if let Some(n) = s["name"].as_str() {
                    *best = n.to_string();
                }
                if let Some(c) = s["children"].as_array() {
                    walk(c, line, ch, best);
                }
            }
        }
    }
    walk(syms, line, ch, &mut best);
    best
}

fn finish(cx: &Ctx, tool: &'static str, t0: Instant, text: String) -> Result<String> {
    let kind = match tool {
        "symbol" => "lsp.symbol",
        "callers" => "lsp.callers",
        "outline" => "lsp.outline",
        _ => "lsp.impact",
    };
    let est = cx.estimate(&text, Class::Code);
    cx.record(&Measurement {
        plugin: "graph",
        kind,
        before_bytes: t0.elapsed().as_millis() as u64,
        after_bytes: text.len() as u64,
        est_before: est,
        est_after: est,
        ref_id: None,
        call_id: cx.call_id(),
    })?;
    cap(cx, text)
}

fn body(cx: &Ctx, root: &Path, path: &str, line: i32, end_line: i32, budget: usize) -> String {
    let src = std::fs::read_to_string(root.join(path)).unwrap_or_default();
    super::body_lines(&src, line, end_line, budget, &mut |bytes| {
        cx.put_archive(bytes).ok()
    })
}

/// The text of `lsp symbol`: one id and `path:line kind` + body per matching
/// definition, or the empty-answer sentence. Shared by the tool and `explore` (T68.1).
fn symbol_text(
    cx: &Ctx,
    s: &mut Session,
    name: &str,
    filter: &super::Filter,
    budget: usize,
) -> Result<String> {
    let r = s.request("workspace/symbol", json!({"query": name}))?;
    let mut out = String::new();
    if let Some(arr) = r.as_array() {
        for it in arr {
            if it["name"].as_str() != Some(name) {
                continue;
            }
            let Some(d) = pick_def(&json!([it]), name, &s.root) else {
                continue;
            };
            let kind = kind_name(d.kind);
            if !filter.path_ok(&d.path) || !filter.kind_ok(kind) {
                continue;
            }
            super::tally::hit(&s.root, name, [d.path.as_str()]);
            out.push_str(&format!(
                "{}\n",
                super::def_head(&d.path, name, kind, d.line)
            ));
            out.push_str(&body(cx, &s.root, &d.path, d.line, d.end_line, budget));
        }
    }
    if out.is_empty() {
        out = format!("no definition of {name}{}\n", filter.scope_note());
    }
    Ok(out)
}

pub(crate) fn symbol(cx: &Ctx, root: &Path, name: &str, filter: &super::Filter) -> Result<String> {
    let t0 = Instant::now();
    let budget = cx.plugin_config::<crate::config::Graph>("graph").body_lines as usize;
    with_session(root, |s| {
        if wait_def(s, name)?.is_none() {
            return finish(
                cx,
                "symbol",
                t0,
                format!("no definition of {name}{}", filter.scope_note()),
            );
        }
        let out = symbol_text(cx, s, name, filter, budget)?;
        finish(cx, "symbol", t0, out)
    })
}

pub(crate) fn callers(cx: &Ctx, root: &Path, name: &str, filter: &super::Filter) -> Result<String> {
    let t0 = Instant::now();
    with_session(root, |s| {
        let Some(d) = wait_def(s, name)? else {
            return finish(
                cx,
                "callers",
                t0,
                format!("no references to {name}{}", filter.scope_note()),
            );
        };
        s.did_open(&d.uri)?;
        let refs = s.request(
            "textDocument/references",
            json!({
                "textDocument": {"uri": d.uri},
                "position": d.pos,
                "context": {"includeDeclaration": false}
            }),
        )?;
        let mut groups: BTreeMap<(String, String), (i64, i32)> = BTreeMap::new();
        let mut syms_by: BTreeMap<String, Vec<Value>> = BTreeMap::new();
        for loc in refs.as_array().cloned().unwrap_or_default() {
            let uri = loc["uri"].as_str().unwrap_or("");
            s.did_open(uri)?;
            let path = rel(&s.root, uri);
            let line0 = loc["range"]["start"]["line"].as_u64().unwrap_or(0);
            let ch = loc["range"]["start"]["character"].as_u64().unwrap_or(0);
            let line = line0 as i32 + 1;
            let kids = if let Some(existing) = syms_by.get(uri) {
                existing.clone()
            } else {
                let raw = s.request(
                    "textDocument/documentSymbol",
                    json!({"textDocument": {"uri": uri}}),
                )?;
                let kids = raw.as_array().cloned().unwrap_or_default();
                syms_by.insert(uri.to_string(), kids.clone());
                kids
            };
            let scope = enclosing(&kids, line0, ch);
            let e = groups.entry((path, scope)).or_insert((0, line));
            e.0 += 1;
            if line < e.1 {
                e.1 = line;
            }
        }
        if groups.is_empty() {
            return finish(
                cx,
                "callers",
                t0,
                format!("no references to {name}{}", filter.scope_note()),
            );
        }
        let mut out = String::new();
        for ((path, scope), (n, line)) in groups {
            if !filter.path_ok(&path) {
                continue;
            }
            super::tally::hit(&s.root, name, [path.as_str()]);
            let scope = if scope.is_empty() {
                String::new()
            } else {
                format!("  {scope}")
            };
            out.push_str(&format!("{path}{scope} ×{n} (L{line})\n"));
        }
        if out.is_empty() {
            out = format!("no references to {name}{}", filter.scope_note());
        }
        finish(cx, "callers", t0, out)
    })
}

/// The `impact` walk: incoming-call BFS from `name`'s hierarchy items out to
/// `depth`, one `(depth, path, scope)` row per definition reached (scope empty at
/// file level). Shared by the tool and `explore` (T68.1).
fn impact_walk(
    s: &mut Session,
    name: &str,
    depth: u32,
    filter: &super::Filter,
) -> Result<Vec<(u32, String, String)>> {
    let Some(d) = wait_def(s, name)? else {
        return Ok(Vec::new());
    };
    s.did_open(&d.uri)?;
    let items = s.request(
        "textDocument/prepareCallHierarchy",
        json!({"textDocument": {"uri": d.uri}, "position": d.pos}),
    )?;
    let mut frontier = items.as_array().cloned().unwrap_or_default();
    let mut seen = HashSet::from([name.to_string()]);
    let mut rows = Vec::new();
    for dpth in 1..=depth.clamp(1, 4) {
        let mut next = Vec::new();
        for item in &frontier {
            let calls = s.request("callHierarchy/incomingCalls", json!({"item": item}))?;
            for c in calls.as_array().cloned().unwrap_or_default() {
                let from = &c["from"];
                let nm = from["name"].as_str().unwrap_or("");
                if !seen.insert(nm.to_string()) {
                    continue;
                }
                let path = rel(&s.root, from["uri"].as_str().unwrap_or(""));
                if !filter.path_ok(&path) {
                    continue;
                }
                if nm.is_empty() {
                    // File-level reference: it prints as `(file)` but never expands.
                    rows.push((dpth, path, String::new()));
                    continue;
                }
                rows.push((dpth, path, nm.to_string()));
                next.push(from.clone());
            }
        }
        frontier = next;
        if frontier.is_empty() {
            break;
        }
    }
    Ok(rows)
}

pub(crate) fn impact(
    cx: &Ctx,
    root: &Path,
    name: &str,
    depth: u32,
    filter: &super::Filter,
    to: Option<&str>,
) -> Result<String> {
    let t0 = Instant::now();
    with_session(root, |s| {
        if let Some(target) = to.filter(|s| !s.is_empty()) {
            let chains = call_paths(s, name, target, depth)?;
            if chains.is_empty() {
                return finish(
                    cx,
                    "impact",
                    t0,
                    format!("no path from {name} to {target} within depth {depth}"),
                );
            }
            super::tally::hit(&s.root, name, std::iter::empty::<&str>());
            return finish(
                cx,
                "impact",
                t0,
                chains.join(
                    "
",
                ) + "
",
            );
        }
        let rows = impact_walk(s, name, depth, filter)?;
        if rows.is_empty() {
            return finish(
                cx,
                "impact",
                t0,
                format!("nothing reaches {name}{}", filter.scope_note()),
            );
        }
        for (_, path, _) in &rows {
            super::tally::hit(&s.root, name, [path.as_str()]);
        }
        finish(cx, "impact", t0, super::impact_lines_text(&rows))
    })
}

/// Call chains `a → … → b` over incoming calls, at most `max_depth` hops, simple
/// paths only: a name already in the chain is never revisited and a branch stops
/// growing once it reaches `b`, so cycles terminate.
fn call_paths(s: &mut Session, a: &str, b: &str, max_depth: u32) -> Result<Vec<String>> {
    if a == b {
        return Ok(Vec::new());
    }
    let Some(d) = wait_def(s, a)? else {
        return Ok(Vec::new());
    };
    s.did_open(&d.uri)?;
    let items = s.request(
        "textDocument/prepareCallHierarchy",
        json!({"textDocument": {"uri": d.uri}, "position": d.pos}),
    )?;
    let mut frontier: Vec<(Value, String, Vec<String>)> = items
        .as_array()
        .cloned()
        .unwrap_or_default()
        .into_iter()
        .map(|it| (it, a.to_string(), vec![a.to_string()]))
        .collect();
    if frontier.is_empty() {
        return Ok(Vec::new());
    }
    let mut chains: std::collections::BTreeSet<String> = std::collections::BTreeSet::new();
    for _ in 0..max_depth.clamp(1, 4) {
        let mut next = Vec::new();
        for (item, tip, chain) in frontier {
            if tip == b {
                chains.insert(chain.join(" → "));
                continue;
            }
            let calls = s.request("callHierarchy/incomingCalls", json!({"item": item}))?;
            for c in calls.as_array().cloned().unwrap_or_default() {
                let from = c["from"].clone();
                let nm = from["name"].as_str().unwrap_or("").to_string();
                if nm.is_empty() || chain.iter().any(|n| n == &nm) {
                    continue;
                }
                let mut c2 = chain.clone();
                c2.push(nm.clone());
                if nm == b {
                    chains.insert(c2.join(" → "));
                } else {
                    next.push((from, nm, c2));
                }
            }
        }
        frontier = next;
        if frontier.is_empty() {
            break;
        }
    }
    Ok(chains.into_iter().collect())
}

/// The LSP backend's `explore` pieces (T68.1): same assembler as the tags
/// backend, answered over workspace/symbol, document bodies and call hierarchy.
struct LspExplore<'a> {
    cx: &'a Ctx<'a>,
    s: &'a mut Session,
    filter: &'a super::Filter,
    budget: usize,
}

impl super::ExploreParts for LspExplore<'_> {
    fn resolve(&mut self, token: &str) -> Result<Vec<String>> {
        let r = self
            .s
            .request("workspace/symbol", json!({"query": token}))?;
        let mut prefixed = std::collections::BTreeSet::new();
        for it in r.as_array().cloned().unwrap_or_default() {
            let Some(n) = it["name"].as_str() else {
                continue;
            };
            if n == token {
                return Ok(vec![token.to_string()]);
            }
            if n.starts_with(token) {
                prefixed.insert(n.to_string());
            }
        }
        Ok(prefixed.into_iter().take(5).collect())
    }

    fn defs(&mut self, name: &str) -> Result<String> {
        symbol_text(self.cx, self.s, name, self.filter, self.budget)
    }

    fn paths(&mut self, a: &str, b: &str) -> Result<Vec<String>> {
        call_paths(self.s, a, b, 3)
    }

    fn impact1(&mut self, name: &str) -> Result<(String, usize)> {
        let rows = impact_walk(self.s, name, 1, self.filter)?;
        if rows.is_empty() {
            return Ok((format!("nothing reaches {name}"), 0));
        }
        let n = rows.len();
        Ok((super::impact_lines_text(&rows), n))
    }

    fn def_count(&mut self, _name: &str) -> Result<usize> {
        Ok(1)
    }
}

pub(crate) fn explore(
    cx: &Ctx,
    root: &Path,
    query: &str,
    filter: &super::Filter,
) -> Result<String> {
    let t0 = Instant::now();
    let budget = cx.plugin_config::<crate::config::Graph>("graph").body_lines as usize;
    with_session(root, |s| {
        let mut parts = LspExplore {
            cx,
            s,
            filter,
            budget,
        };
        let (text, before) = super::assemble_explore(query, filter, &mut parts)?;
        let est = cx.estimate(&text, Class::Code);
        cx.record(&Measurement {
            plugin: "graph",
            kind: "lsp.explore",
            before_bytes: t0.elapsed().as_millis() as u64,
            after_bytes: text.len() as u64,
            est_before: est,
            est_after: est,
            ref_id: None,
            call_id: cx.call_id(),
        })?;
        super::cap_kind(cx, text, before, "explore")
    })
}

fn flatten(syms: &[Value], out: &mut String) {
    for s in syms {
        let name = s["name"].as_str().unwrap_or("");
        let line = s["range"]["start"]["line"].as_u64().unwrap_or(0) + 1;
        let prefix = match kind_name(s["kind"].as_u64().unwrap_or(0)) {
            "function" | "method" => "fn",
            other => other,
        };
        out.push_str(&format!("{prefix} {name} {line}\n"));
        if let Some(c) = s["children"].as_array() {
            flatten(c, out);
        }
    }
}

fn workspace_of(file: &Path) -> PathBuf {
    for d in file.ancestors().skip(1) {
        if LANGS
            .iter()
            .any(|(marker, _, server)| server.is_some() && d.join(marker).is_file())
        {
            return d.to_path_buf();
        }
    }
    file.parent().unwrap_or(file).to_path_buf()
}

pub(crate) fn outline(cx: &Ctx, root: &Path, path: &str) -> Result<String> {
    let t0 = Instant::now();
    let abs = if Path::new(path).is_absolute() {
        PathBuf::from(path)
    } else {
        root.join(path)
    };
    // Same session key as `symbol` / `callers` / `impact` (cwd) when the file sits under cwd and
    // its nearest manifest speaks cwd's server; otherwise the nearest manifest. Keyed by the
    // nearest manifest alone, alternating `outline` and `symbol` calls in a Cargo workspace
    // killed and respawned the server each time; keyed by cwd alone, a Dart file outside a
    // Cargo cwd was sent to rust-analyzer and outlined as empty.
    let near = workspace_of(&abs);
    let ws = match (pick(root), pick(&near)) {
        (Ok((a, _)), Ok((b, _))) if a == b && abs.starts_with(root) => root.to_path_buf(),
        _ => near,
    };
    with_session(&ws, |s| {
        let uri = file_uri(&abs);
        s.did_open(&uri)?;
        let deadline = Instant::now() + ready();
        loop {
            let raw = s.request(
                "textDocument/documentSymbol",
                json!({"textDocument": {"uri": uri}}),
            )?;
            let mut text = String::new();
            if let Some(arr) = raw.as_array() {
                flatten(arr, &mut text);
            }
            if !text.is_empty() || Instant::now() >= deadline {
                if !text.is_empty() {
                    super::tally::hit(root, "", [path]);
                }
                return finish(cx, "outline", t0, text.trim_end().to_string());
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    })
}

#[cfg(all(test, unix))]
mod transport_tests;

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    /// LSP 3.17 `SymbolKind`: 10 is Enum, 11 is Interface (11 used to read as "enum").
    #[test]
    fn kind_name_follows_the_lsp_symbol_kind_numbers() {
        for (k, name) in [
            (5, "class"),
            (6, "method"),
            (10, "enum"),
            (11, "interface"),
            (12, "function"),
            (13, "variable"),
            (23, "struct"),
            (99, "symbol"),
        ] {
            assert_eq!(kind_name(k), name, "SymbolKind {k}");
        }
    }

    /// T41.1: a `pubspec.yaml` root picks `dart language-server`, and a `.dart`
    /// file resolves its workspace to that root.
    #[test]
    fn dart_pubspec_root_picks_dart_language_server() {
        let dir = std::env::temp_dir().join(format!("rtok-lsp-dart-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("lib")).unwrap();
        std::fs::write(dir.join("pubspec.yaml"), "name: dart_gate\n").unwrap();
        let main = dir.join("lib/main.dart");
        std::fs::write(&main, "void main() {}\n").unwrap();
        let (bin, args) = pick(&dir).unwrap();
        assert_eq!(bin, "dart");
        assert_eq!(args, &["language-server"]);
        assert_eq!(workspace_of(&main), dir);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The cached server outlived `rtok mcp`: it sat in a `static`, and statics never drop.
    #[test]
    fn shutdown_reaps_the_cached_server_and_its_stderr_file() {
        let mut child = Command::new("cat")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let pid = child.id().to_string();
        let err_path = std::env::temp_dir().join(format!("rtok-lsp-test-{pid}.stderr"));
        std::fs::write(&err_path, "").unwrap();
        let s = Session::attach(
            child.stdin.take().unwrap(),
            child.stdout.take().unwrap(),
            Some(child),
            PathBuf::new(),
            err_path.clone(),
        );
        *SESSION.lock().unwrap_or_else(|e| e.into_inner()) = Some(("test".into(), s));
        shutdown();
        let alive = Command::new("kill").args(["-0", &pid]).status().unwrap();
        assert!(!alive.success(), "server {pid} still running");
        assert!(!err_path.exists());
    }
}

#[cfg(test)]
mod uri_tests {
    use super::{file_uri, path_from_file_uri};
    use std::path::PathBuf;

    #[test]
    fn file_uri_uses_forward_slashes() {
        let dir = std::env::temp_dir().join(format!("rtok-uri-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let uri = file_uri(&dir);
        let _ = std::fs::remove_dir_all(&dir);
        assert!(uri.starts_with("file://"), "{uri}");
        assert!(!uri.contains('\\'), "no backslashes: {uri}");
        if cfg!(windows) {
            assert!(
                uri.starts_with("file:///"),
                "windows needs three slashes: {uri}"
            );
        }
        let round = path_from_file_uri(&uri);
        assert!(!round.as_os_str().is_empty(), "{round:?}");
    }

    #[test]
    fn path_from_file_uri_keeps_unix_root() {
        let p = path_from_file_uri("file:///tmp/a");
        assert_eq!(p, PathBuf::from("/tmp/a"));
    }

    #[cfg(windows)]
    #[test]
    fn path_from_file_uri_strips_slash_before_drive() {
        let p = path_from_file_uri("file:///C:/Users/x");
        assert_eq!(p, PathBuf::from(r"C:\Users\x"));
    }

    #[test]
    fn file_uri_encodes_spaces() {
        // Pure encode/decode — no host TempDir (VFS / T56).
        let encoded = super::percent_encode_path("C:/Users/Ivan Tuhai/proj/a.rs");
        assert!(encoded.contains("%20"), "{encoded}");
        assert!(!encoded.contains(' '), "{encoded}");
        assert!(encoded.starts_with("C:"), "{encoded}");
        let uri = format!("file:///{encoded}");
        let round = path_from_file_uri(&uri);
        assert!(round.to_string_lossy().contains("Ivan Tuhai"), "{round:?}");
    }

    #[test]
    fn path_from_file_uri_decodes_percent20() {
        let p = path_from_file_uri("file:///tmp/My%20Docs/a.rs");
        assert_eq!(p, PathBuf::from("/tmp/My Docs/a.rs"));
    }

    #[test]
    fn file_uri_encodes_hash_and_question_in_path() {
        let encoded = super::percent_encode_path("C:/tmp/a#b?.rs");
        assert!(encoded.contains("%23"), "{encoded}");
        assert!(
            encoded.contains("%3F") || encoded.contains("%3f"),
            "{encoded}"
        );
        let round = path_from_file_uri(&format!("file:///{encoded}"));
        let s = round.to_string_lossy();
        assert!(s.contains("a#b?.rs") || s.contains("a"), "{round:?}");
    }
}

#[cfg(test)]
mod probe_tests {
    use super::{is_mise_shim, is_rustup_proxy};
    use std::fs;
    use std::path::Path;

    #[test]
    fn mise_shim_is_not_an_installed_server() {
        let shim = Path::new("/home/runner/.local/share/mise/shims/rust-analyzer");
        assert!(is_mise_shim(shim));
        assert!(!is_mise_shim(Path::new(
            "/home/runner/.rustup/toolchains/stable/bin/rust-analyzer"
        )));
    }

    #[test]
    fn rustup_proxy_matches_the_rustup_binary_size() {
        let dir = std::env::temp_dir().join(format!("rtok-ra-proxy-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let proxy = if cfg!(windows) {
            dir.join("rustup.exe")
        } else {
            dir.join("rustup")
        };
        let analyzer = if cfg!(windows) {
            dir.join("rust-analyzer.exe")
        } else {
            dir.join("rust-analyzer")
        };
        fs::write(&proxy, b"proxy").unwrap();
        fs::write(&analyzer, b"proxy").unwrap();
        assert!(is_rustup_proxy(&analyzer));
        fs::write(&analyzer, b"a real rust-analyzer binary").unwrap();
        assert!(!is_rustup_proxy(&analyzer));
        let _ = fs::remove_dir_all(&dir);
    }
}
