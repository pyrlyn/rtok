Found 2026-09-22 in the docs pass: `site/content/docs/reference/_content.gotmpl:5-18` lists 12 pages but omits `docs/otel.md`, `docs/release.md`, `docs/plugin-plan-template.md` and `docs/getting-started.md` — mounted as assets yet never published (README/AGENTS point readers at `docs/otel.md` and `docs/release.md`). Separately `site/content/docs/getting-started.md:1-25` is a re-worded copy of `docs/getting-started.md` that already drifts — against "a repo file IS the page" and `site/hugo.toml:8-10` ("Nothing is copied").

Plan: add rows for otel, release and plugin-plan-template (document an exemption if plugin-plan-template is internal); replace the site-local getting-started with a `_content.gotmpl` row mounting `repo/docs/getting-started.md` and delete the copy.

Check: `tests/site_pages.rs` — every `docs/*.md` appears in `_content.gotmpl` (modulo a small explicit exemption list) and `site/content/docs/` holds no page duplicating a repo file; `just site` builds.

Result: `site/content/docs/reference/_content.gotmpl` mounts `docs/otel.md` and `docs/release.md`. `docs/plugin-plan-template.md` is exempt with a reason: a scaffold copied into `src/plugins/<id>/PLAN.md`, not reader prose. The hand-copied `site/content/docs/getting-started.md` is deleted, and a new `site/content/docs/_content.gotmpl` mounts `docs/getting-started.md` at the same `/docs/getting-started/` URL. Its two still-correct extras (the `not implemented` exit-0 note, a Caveats section) are folded into the repo doc. `tests/site_pages.rs`: every doc mounted or exempt, exemptions real, no site page duplicating a repo doc. `just site` builds 37 pages.

Status: done 2026-09-24
Model: Claude Code / claude-sonnet-5 (code), claude-opus-5-5 (review)
