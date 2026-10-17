"""rtk (github.com/rtk-ai/rtk, Apache-2.0): `rtk <cmd> ...`; `cat` is `rtk read`."""
from __future__ import annotations

NAME = "rtk"
VERSION_ARGV = ["rtk", "--version"]
SUPPORTED = {"cargo", "git", "ls", "find", "grep", "rg", "cat", "tree", "wc"}


def argv(case_argv: list[str]) -> list[str] | None:
    head, rest = case_argv[0], case_argv[1:]
    if head not in SUPPORTED:
        return None
    if head == "cat":
        return ["rtk", "read", *rest]
    return ["rtk", head, *rest]
