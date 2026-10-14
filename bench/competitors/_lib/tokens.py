#!/usr/bin/env python3
"""Count tokens of stdin with a fixed tokenizer (tiktoken o200k_base). Prints one integer."""
import sys
import tiktoken

ENC = tiktoken.get_encoding("o200k_base")
data = sys.stdin.buffer.read().decode("utf-8", errors="replace")
print(len(ENC.encode(data, disallowed_special=())))
