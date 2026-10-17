"""Minimal MCP stdio client (newline-delimited JSON-RPC), no shell, no SDK.

Used the same way for every server: initialize, notifications/initialized, then
tools/list and/or tools/call. Returns parsed results so callers count tokens of the
content a model would actually receive.
"""
from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Optional


class Mcp:
    def __init__(self, argv: list, cwd: Path, env: dict):
        self.p = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                                  encoding="utf-8")
        self.n = 0
        self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                                    "clientInfo": {"name": "rtok-bench", "version": "1"}})
        self.notify("notifications/initialized")

    def notify(self, method: str, params: Optional[dict] = None) -> None:
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "method": method, "params": params or {}}) + "\n")
        self.p.stdin.flush()

    def request(self, method: str, params: dict) -> dict:
        self.n += 1
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.n, "method": method, "params": params}) + "\n")
        self.p.stdin.flush()
        while True:
            line = self.p.stdout.readline()
            if not line:
                raise RuntimeError(f"server closed during {method}")
            try:
                msg = json.loads(line)
            except ValueError:
                continue
            if msg.get("id") == self.n:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg["result"]

    def tools(self) -> list:
        return self.request("tools/list", {})["tools"]

    def call_text(self, name: str, args: dict) -> str:
        res = self.request("tools/call", {"name": name, "arguments": args})
        return "\n".join(c.get("text", json.dumps(c)) for c in res.get("content", []))

    def close(self) -> None:
        self.p.kill()
        self.p.wait()
