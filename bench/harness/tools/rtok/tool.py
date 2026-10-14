"""rtok: `rtok run -- '<command>'`, the same single-string form the hook sends."""
from __future__ import annotations

import shlex

NAME = "rtok"
VERSION_ARGV = ["rtok", "--version"]


def argv(case_argv: list[str], binary: str = "rtok", no_ref: bool = False) -> list[str] | None:
    # `--no-ref` (rtok#1077) drops the `[rtok <id> · N lines]` reference line.
    return [binary, "run", *(["--no-ref"] if no_ref else []), "--", shlex.join(case_argv)]
