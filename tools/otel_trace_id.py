# Copyright (c) 2026 Ivan Tugay
# SPDX-License-Identifier: GPL-3.0-or-later
# Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

"""T624: print the first well-formed trace id of a Jaeger `/api/traces` response on stdin.

Parsing the JSON (not quote-stripping a regex match) keeps the id whatever the whitespace or
field order. Exits 1 with a message on stderr when the body is not JSON or holds no valid id.
"""

import json
import re
import sys

TRACE_ID = re.compile(r"[0-9a-f]{32}")


def first_trace_id(body: str) -> str | None:
    try:
        data = json.loads(body).get("data")
    except (ValueError, AttributeError):
        return None
    for trace in data if isinstance(data, list) else []:
        trace_id = trace.get("traceID") if isinstance(trace, dict) else None
        if isinstance(trace_id, str) and TRACE_ID.fullmatch(trace_id):
            return trace_id
    return None


if __name__ == "__main__":
    found = first_trace_id(sys.stdin.read())
    if found is None:
        print("otel-check: no valid trace id in the Jaeger response", file=sys.stderr)
        sys.exit(1)
    print(found)
