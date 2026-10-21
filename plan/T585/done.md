Source audit only; no product code changed. Sampled the filesystem/project resolver and logfile delegation, plus CLI `emit`, report flag resolution and the task/claim consumers of project identity. Checked relevant root instructions and the existing task/config/report contracts before filing only reproducible gaps.

Execution plan:

1. Inventory CLI output/config entrypoints and trace representative output through argument parsing, Figment precedence, shared emit/write helpers, and filesystem/project identity helpers. Inspect log append delegation without assuming every CLI export uses the same path.
2. Compare relative and absolute local Git origins in isolated checkouts against Git's own resolution, then check whether project identity aligns with the documented task-counter and claim-scope contract.
3. Compare omitted versus explicit report formats using actual Clap argument parsing, report flag mapping and a lower-precedence Figment configuration.
4. Deduplicate against T441.3, T22.1/T22.2, D12/D14, CLI trycmd tasks and existing path/atomic-write findings. File only reproduced contract violations, leaving ordinary output overwrite and unproven platform concerns unfiled.

Result: filed T586 for different relative local origins colliding in one project key and equivalent relative/absolute spellings of a local remote splitting identity. Filed T587 because an explicit `--format md` is indistinguishable from the Clap default and therefore fails to override configured HTML/PDF. Ordinary report output overwrite was not a new finding because no contrary contract was established; no separate logfile issue was found. This was a sampled audit, not an exhaustive review of every CLI exporter or the full configuration crate.

Evidence revision: `0e99dda0932c3f926b275a61d204d1dbd7d77ea9`. An offline harness compiled the actual project resolver/normalization and report argument/flag functions with a standard filesystem adapter and Figment config. Two scratch Git checkouts with origin `../remote.git` resolved to different local bare repositories and refs, while rtok returned the same `../remote` key; changing one origin to the same remote's absolute path changed its key. For report flags, configured HTML remained effective under explicit `--format md`, while explicit PDF overrode it. An initial scratch setup failed because its ref pointed at an object not copied into the bare repository; the fixture was corrected and the passing controls were rerun. No network, real repository data, API or host config was used. Store allocation, claim lookup, full report rendering and output-file integration were source-traced, not exercised.

Validation: `CHECK_BASE=1d5889ab36b485befdda0355687a394bf8380077 mise exec -- just check` passed for the documentation-only diff; the selective gate found no code changes, so no Cargo tests were selected. `git diff --check` passed, source links were reviewed, and active plan/todo IDs matched uniquely.

Status: done 2026-10-11
Model: OpenAI / GPT-6
