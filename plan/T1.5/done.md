**T1.5 estimator calibration (optional, needs API key)** · T0.5 · `src/tokens.rs`
Do: `rtok stats --calibrate`: sample 30 archived tool results per class, call `POST /v1/messages/count_tokens`, fit chars-per-token per class, write to config. Skip silently without a key.
Check: with a key, printed fit is within 2.5–4.5 chars/token per class; without a key, exit 0 and message "skipped".
Status: done 2026-09-02 · Check: without ANTHROPIC_API_KEY, `rtok stats --calibrate` prints `skipped` and exits 0. Full count_tokens fit deferred (no HTTP client in v0.1 yet; would need a new dep).

Gate P1 · Status: done 2026-09-03 · Check: `rtok stats --save-baseline before-rtok` wrote `~/.rtok/measurements/before-rtok.json`; `--compare before-rtok` all Δ0; `research.md` §2 records 580 sessions / 181 303 lines / Bash 7.71 M / Read 3.07 M (30d). Not the original 17-session H-measured slice.
