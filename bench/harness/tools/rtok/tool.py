"""rtok: `rtok run -- '<command>'`, the same single-string form the hook sends."""
from __future__ import annotations

import shlex

NAME = "rtok"
VERSION_ARGV = ["rtok", "--version"]


def argv(case_argv: list[str], binary: str = "rtok") -> list[str] | None:
    return [binary, "run", "--", shlex.join(case_argv)]
