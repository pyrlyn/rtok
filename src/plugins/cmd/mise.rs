// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

//! `mise` output (T677): the tool-install and download chatter in front of a task, gone.
//!
//! `mise run`/`mise exec` print one line per download, checksum and extract step (plus
//! progress-bar frames) before the command the agent actually asked for. Written for rtok
//! from the output shape alone; no code copied. Everything else — the `[task] $ cmd` echo,
//! the command's own output, `✓ installed`, every WARN/ERROR line — is kept byte for byte,
//! so the inner command's formatter (see `formatters::resolve`) still sees what it needs.

use regex::Regex;
use std::sync::OnceLock;

/// `mise node@20.1.0 download …` — a step the install prints, not a result.
fn step() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(
            r"^mise [\w.:/+-]+@\S+ (download|downloading|extract|extracting|checksum|verify|verifying|install|installing|compile|configure|make)\b",
        )
        .expect("static pattern")
    })
}

/// A progress bar (`[=====>   ]`) or a byte counter (`12.3MiB/45MiB`).
fn progress() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(r"\[[#=>. -]{4,}\]|\d+(\.\d+)? ?[KMGT]i?B ?/ ?\d+(\.\d+)? ?[KMGT]i?B")
            .expect("static pattern")
    })
}

/// A line that must survive whatever else is dropped.
fn problem(line: &str) -> bool {
    let low = line.to_ascii_lowercase();
    ["error", "warn", "fail", "panic"]
        .iter()
        .any(|w| low.contains(w))
}

/// The last `\r` frame of a line (what the terminal shows), without colour codes.
fn visible(raw: &str) -> String {
    let last = raw.rsplit('\r').find(|s| !s.is_empty()).unwrap_or("");
    super::formatters::strip_ansi(last)
}

/// `output` without install/progress lines, or `None` when there were none (or dropping
/// them would not make it shorter). One marker line says how many went.
pub fn tidy(output: &str) -> Option<String> {
    let mut out: Vec<&str> = Vec::new();
    let mut dropped = 0usize;
    let mut marker_at = None;
    for raw in output.lines() {
        let line = visible(raw);
        let noise = !problem(&line) && (step().is_match(&line) || progress().is_match(&line));
        if noise {
            dropped += 1;
            marker_at.get_or_insert(out.len());
        } else {
            out.push(raw);
        }
    }
    if dropped == 0 {
        return None;
    }
    let mut lines: Vec<String> = out.iter().map(|l| l.to_string()).collect();
    lines.insert(
        marker_at.unwrap_or(0),
        format!("mise: {dropped} install/progress lines dropped"),
    );
    let got = lines.join("\n");
    (got.len() < output.len()).then_some(got)
}

#[cfg(test)]
mod tests {
    use super::*;

    const INSTALL: &str = "mise node@20.11.0 download node-v20.11.0-darwin-arm64.tar.gz\n\
mise node@20.11.0 checksum node-v20.11.0-darwin-arm64.tar.gz\n\
mise node@20.11.0 extract node-v20.11.0-darwin-arm64.tar.gz\n\
mise node@20.11.0 node-v20.11.0-darwin-arm64/bin/node -v\n\
mise node@20.11.0 ✓ installed\n\
[test] $ cargo test\n\
test download::ok ... ok\n\
test result: ok. 1 passed\n";

    #[test]
    fn install_steps_go_and_everything_else_stays() {
        let got = tidy(INSTALL).unwrap();
        assert!(
            got.starts_with("mise: 3 install/progress lines dropped\n"),
            "{got}"
        );
        for kept in [
            "mise node@20.11.0 ✓ installed",
            "mise node@20.11.0 node-v20.11.0-darwin-arm64/bin/node -v",
            "[test] $ cargo test",
            "test download::ok ... ok",
            "test result: ok. 1 passed",
        ] {
            assert!(got.contains(kept), "{kept} lost: {got}");
        }
    }

    #[test]
    fn progress_frames_collapse_and_carriage_returns_show_the_last_frame() {
        let raw = "mise node@20 download x.tar.gz [=>        ] 1.0MiB/45MiB\r\
mise node@20 download x.tar.gz [=====>    ] 20MiB/45MiB\n\
\x1b[32m  [#########>] 45MiB/45MiB\x1b[0m\n\
done\n";
        let got = tidy(raw).unwrap();
        assert!(got.ends_with("\ndone"), "{got}");
        assert!(
            got.starts_with("mise: 2 install/progress lines dropped"),
            "{got}"
        );
    }

    #[test]
    fn warnings_and_errors_are_never_dropped_even_on_a_step_line() {
        let raw = "mise node@20 download x.tar.gz\n\
mise node@20 checksum x.tar.gz\n\
mise node@20 extract x.tar.gz\n\
mise node@20 download y.tar.gz\n\
mise node@20 download failed: connection reset\n\
mise WARN  deprecated setting\n\
mise ERROR task failed\n";
        let got = tidy(raw).unwrap();
        assert!(got.contains("download failed: connection reset"), "{got}");
        assert!(got.contains("mise WARN  deprecated setting"), "{got}");
        assert!(got.contains("mise ERROR task failed"), "{got}");
    }

    #[test]
    fn output_without_noise_is_left_alone() {
        assert!(tidy("[test] $ cargo test\ntest result: ok\n").is_none());
        assert!(tidy("").is_none());
    }
}
