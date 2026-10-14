#!/usr/bin/env python3
"""Count tokens with tiktoken o200k_base. Reads stdin bytes; prints one integer."""
from __future__ import annotations

import sys

import tiktoken

ENC = tiktoken.get_encoding("o200k_base")


def count_bytes(data: bytes) -> int:
    text = data.decode("utf-8", errors="replace")
    return len(ENC.encode(text, disallowed_special=()))


def main() -> None:
    print(count_bytes(sys.stdin.buffer.read()))


if __name__ == "__main__":
    main()
