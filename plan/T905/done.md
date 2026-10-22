`docs/research/competitors-techniques.md` (sqz coverage) left `podman` open: the `docker ps` formatter matched only `docker`, so `podman ps` fell through to the default head/tail rule. Idea I-133.

`podman ps` takes the same arm as `docker ps`. Unrecognized output still returns `None` and stays on the rule path. The golden is the `docker ps` fixture with the argv changed, so the saving is that measurement (59 %), not a new corpus.

Check: `podman_ps` golden matches `docker_ps` and clears `min_saving: 59`; a non-table `podman ps` body is not `kind = formatter`.

Result: `format` matches `("docker" | "podman", "ps")`. `tests/cmd_golden/podman_ps.in` is the docker fixture. Docs name both families on the T58.5 row (`docs/cmd-rules.md` and `docs/ru`, `docs/uk`).

Status: done 2026-10-19
Model: Cursor / grok 4.7
