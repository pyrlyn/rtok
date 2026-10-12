# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

"""T624: the trace-id extraction behind tools/otel-check.sh, offline (no Docker, no Jaeger).
Run: `mise exec -- pytest tools/tests`.
"""

import json
import subprocess
import sys
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent.parent / "otel_trace_id.py"
GOOD = "0123456789abcdef0123456789abcdef"
OTHER = "fedcba9876543210fedcba9876543210"


def _run(body: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT)], input=body, capture_output=True, text=True, check=False
    )


@pytest.mark.parametrize("indent", [None, 2])
def test_valid_trace_id_is_kept(indent):
    body = json.dumps({"data": [{"traceID": GOOD, "spans": []}]}, indent=indent)
    done = _run(body)
    assert (done.returncode, done.stdout.strip()) == (0, GOOD)


def test_first_valid_trace_wins_over_malformed_ones():
    body = json.dumps({"data": [{"traceID": "short"}, {"traceID": GOOD}, {"traceID": OTHER}]})
    assert _run(body).stdout.strip() == GOOD


@pytest.mark.parametrize(
    "body",
    [
        '{"data": []}',
        '{"data": null}',
        '{"data": [{"traceID": "ABCDEF0123456789ABCDEF0123456789"}]}',
        '{"data": [{"traceID": "0123456789abcdef"}]}',
        '{"data": [{"spans": []}, 7]}',
        "[]",
        "not json",
        "",
    ],
)
def test_missing_or_invalid_id_fails_clearly(body):
    done = _run(body)
    assert done.returncode == 1
    assert done.stdout == ""
    assert "no valid trace id" in done.stderr
