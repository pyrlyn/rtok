**T9.4 legacy stack folder** · — · `legacy/`
Do: in `~/GitHub/reduce-token` (separate directory, not this repo): move `docker-compose.yml`, `bifrost-config/`, `caveman/`, `headroom/`, `.env.example` into `legacy/` with a README line "kept for A/B; bifrost semantic cache retired (see research.md)".
Check: `docker compose -f legacy/docker-compose.yml config` still validates.
Status: done 2026-09-02 · Check: `ANTHROPIC_API_KEY=dummy REPO_DIR=/tmp docker compose -f ~/GitHub/reduce-token/legacy/docker-compose.yml config` validates (compose interpolates those two vars). `legacy/README.md` has the A/B line. Deviation: `~/GitHub/reduce-token` is not a git repo, so the move is filesystem-only; root README notes the stack lives in `legacy/`.
