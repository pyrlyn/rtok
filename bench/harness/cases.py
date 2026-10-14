"""Bench cases. Each runs in /fixture (rtok at FIXTURE_REF) after a reset and
an optional setup that edits the tree. argv is run without a shell."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

FIXTURE = Path("/fixture")


def _append(rel: str, text: str) -> None:
    p = FIXTURE / rel
    with p.open("a", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def failing_test() -> None:
    _append(
        "crates/rtok-config/src/lib.rs",
        "\n#[cfg(test)]\nmod bench_fail {\n    #[test]\n    fn bench_fails() {\n"
        '        assert_eq!(1 + 1, 3, "bench: intentional failure");\n    }\n}\n',
    )


def touch_log() -> None:
    _append("crates/rtok-log/src/lib.rs", "\n// bench: incremental rebuild\n")


def warn_log() -> None:
    _append("crates/rtok-log/src/lib.rs", "\n#[allow(dead_code)]\nfn bench_warn() { let unused = 1; }\n")


def error_log() -> None:
    _append("crates/rtok-log/src/lib.rs", '\n#[allow(dead_code)]\nfn bench_err() -> u32 { "x" }\n')


def dirty_tree() -> None:
    _append("README.md", "\nBench line one.\nBench line two.\n")
    _append("src/plugins/cmd/filter.rs", "\n// bench: edited\n")
    p = FIXTURE / "rules/default.toml"
    text = p.read_text(encoding="utf-8")
    p.write_text(text.replace("max_lines = 40", "max_lines = 41", 1), encoding="utf-8", newline="\n")
    (FIXTURE / "bench-untracked.txt").write_text("x\n", encoding="utf-8")
    (FIXTURE / "bench-dir").mkdir(exist_ok=True)
    (FIXTURE / "bench-dir" / "note.md").write_text("note\n", encoding="utf-8")


@dataclass(frozen=True)
class Case:
    id: str
    group: str
    argv: list[str]
    setup: object = None
    env: dict[str, str] = field(default_factory=dict)


T1 = {"RUST_TEST_THREADS": "1"}
EXCL = ["--exclude-dir=target", "--exclude-dir=.git"]

CASES: list[Case] = [
    Case("cargo_test_pass", "cargo test", ["cargo", "test", "--locked", "-p", "rtok-config"], env=T1),
    Case("cargo_test_fail", "cargo test", ["cargo", "test", "--locked", "-p", "rtok-config"], failing_test, T1),
    Case("cargo_build_noop", "cargo build", ["cargo", "build", "--locked", "-p", "rtok-log"]),
    Case("cargo_build_incremental", "cargo build", ["cargo", "build", "--locked", "-p", "rtok-log"], touch_log),
    Case("cargo_build_warning", "cargo build", ["cargo", "build", "--locked", "-p", "rtok-log"], warn_log),
    Case("cargo_build_error", "cargo build", ["cargo", "build", "--locked", "-p", "rtok-log"], error_log),
    Case("find_rs", "find", ["find", ".", "-name", "*.rs", "-not", "-path", "./target/*"]),
    Case("find_src_files", "find", ["find", "src/plugins", "-type", "f"]),
    Case("find_cargo_toml", "find", ["find", "crates", "-name", "Cargo.toml"]),
    Case("find_dirs", "find", ["find", "src", "-maxdepth", "2", "-type", "d"]),
    Case("ls_la_root", "ls -la", ["ls", "-la"]),
    Case("ls_la_subdir", "ls -la", ["ls", "-la", "src/plugins/cmd"]),
    Case("ls_la_crates", "ls -la", ["ls", "-la", "crates"]),
    Case("git_status_clean", "git status", ["git", "status"]),
    Case("git_status_dirty", "git status", ["git", "status"], dirty_tree),
    Case("git_diff", "git diff", ["git", "diff"], dirty_tree),
    Case("git_diff_stat", "git diff", ["git", "diff", "--stat"], dirty_tree),
    Case("git_log_20", "git log", ["git", "log", "-n", "20"]),
    Case("git_log_oneline_50", "git log", ["git", "log", "--oneline", "-n", "50"]),
    Case("git_log_stat_5", "git log", ["git", "log", "--stat", "-n", "5"]),
    Case("grep_fn_main", "grep", ["grep", "-rn", "fn main", "--include=*.rs", *EXCL, "."]),
    Case("grep_settings_src", "grep", ["grep", "-rn", "Settings", "src/plugins/cmd"]),
    Case("rg_pub_fn", "rg", ["rg", "-n", "pub fn", "src/plugins/cmd"]),
    Case("rg_files_with", "rg", ["rg", "-l", "Settings", "src"]),
    Case("cat_rust", "cat", ["cat", "src/plugins/cmd/filter.rs"]),
    Case("cat_toml", "cat", ["cat", "Cargo.toml"]),
    Case("tree_plugins", "tree", ["tree", "src/plugins/cmd"]),
    Case("tree_crates_l2", "tree", ["tree", "-L", "2", "crates"]),
    Case("wc_l", "wc", ["wc", "-l", "Cargo.toml", "README.md", "rules/default.toml", "src/main.rs"]),
]
