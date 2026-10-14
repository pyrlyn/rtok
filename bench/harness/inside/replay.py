#!/opt/tok/bin/python
"""PATH shim: replay the captured output when called with the captured argv
in the captured cwd; otherwise exec the real binary (same fixture state)."""
import json
import os
import sys
from pathlib import Path

SHIM_DIR = str(Path(__file__).resolve().parent / "bin")


def real(name: str) -> str:
    for d in os.environ.get("PATH", "").split(os.pathsep):
        if d and os.path.realpath(d) != os.path.realpath(SHIM_DIR):
            cand = Path(d) / name
            if cand.is_file() and os.access(cand, os.X_OK):
                return str(cand)
    sys.exit(f"replay: no real {name}")


def main() -> None:
    name = Path(sys.argv[0]).name
    spec_path = os.environ.get("BENCH_REPLAY")
    if spec_path:
        spec = json.loads(Path(spec_path).read_text(encoding="utf-8"))
        if spec["argv"] == [name, *sys.argv[1:]] and os.path.realpath(os.getcwd()) == spec["cwd"]:
            d = Path(spec_path).parent
            sys.stderr.buffer.write((d / "stderr").read_bytes())
            sys.stderr.flush()
            sys.stdout.buffer.write((d / "stdout").read_bytes())
            sys.stdout.flush()
            sys.exit(spec["exit"])
    exe = real(name)
    os.execv(exe, [exe, *sys.argv[1:]])


main()
