#!/usr/bin/env bash
# Runs inside the container. Same commands, three ways: raw, `rtk <cmd>`, `rtok run <cmd>`.
# Output: /out/tokens.tsv, /out/hyperfine/*.json, /out/versions.txt, /out/samples/*.
set -euo pipefail
RUNS=${RUNS:-30}
export HOME=/tmp/benchhome RTOK_HOME=/tmp/benchhome/.rtok NO_COLOR=1 TERM=dumb CARGO_NET_OFFLINE=true
mkdir -p "$HOME" /out/hyperfine /out/samples
tok() { /opt/tok/bin/python /bench/_lib/tokens.py; }
cd /fixture
# A fixed dirty tree so `git status` and `git diff` have content.
git checkout --quiet -- . && git clean -fdq
sed -i '1i // bench edit' crates/core/main.rs
printf 'fn bench_added() {}\n' >> crates/searcher/src/lib.rs
touch bench-untracked.txt

{
  echo "rtok: $(rtok --version)"
  echo "rtk: $(rtk --version)"
  echo "rustc: $(rustc -vV | tr '\n' ' ')"
  echo "cargo: $(cargo -V)"
  echo "fixture: $(git config --get remote.origin.url) @ $(git rev-parse HEAD)"
  echo "tokenizer: tiktoken $(/opt/tok/bin/python -c 'import tiktoken;print(tiktoken.__version__)') o200k_base"
  echo "hyperfine: $(hyperfine --version)"
  echo "nproc: $(nproc)  mem: $(awk '/MemTotal/{print $2" kB"}' /proc/meminfo)"
} > /out/versions.txt

CASES=(
  "git status"
  "git log -n 50"
  "git log --stat -n 10"
  "git diff"
  "ls -la"
  "find . -name *.rs -not -path ./target/*"
  "grep -rn fn\ main --include=*.rs ."
  "cargo build --locked"
  "cargo test --locked"
)
printf 'case\traw\tcompetitor\trtok\n' > /out/tokens.tsv
for c in "${CASES[@]}"; do
  key=$(echo "$c" | tr -c 'a-zA-Z0-9' '_' | sed 's/_*$//')
  raw=$(bash -c "$c" 2>&1 | tee "/out/samples/$key.raw" | tok || true)
  comp=$(bash -c "rtk $c" 2>&1 | tee "/out/samples/$key.rtk" | tok || true)
  rt=$(bash -c "rtok run $c" 2>&1 | tee "/out/samples/$key.rtok" | tok || true)
  printf '%s\t%s\t%s\t%s\n' "$c" "$raw" "$comp" "$rt" >> /out/tokens.tsv
  hyperfine --style none --warmup 3 --runs "$RUNS" -i \
      --export-json "/out/hyperfine/$key.json" \
      "$c" "rtk $c" "rtok run $c" >/dev/null
done
/opt/tok/bin/python /bench/_lib/summarize.py /out
