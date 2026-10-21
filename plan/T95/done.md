Plan: one POSIX script `plugins/cline/hooks/rtok-hook` taking the event from its file name; `plugins/cline/README.md` with `## Docs`; a `tests/` check (executable, fail-open without `rtok`, every linked event known to `adapt_cline`).

Do (Muse Spark / rtok): `plugins/cline/hooks/rtok-hook` (`#!/bin/sh`, executable) runs `rtok hook <event> --host cline`, prints `{}` + ketch hint on stderr with `rtok` missing; `plugins/cline/README.md` with hand install, honoured / not-honoured notes and `## Docs` Cline links; `tests/cline_plugin.rs` pins the three behaviours.

Status: done 2026-09-22
Check result: `tests/cline_plugin.rs` 3 passed; `host_docs` 2 passed.
Model: Muse Spark / rtok
