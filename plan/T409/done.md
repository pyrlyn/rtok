Why: the creator asked to remove task names from the shell output (2026-10-04). `--help`, the man page, the generated completion scripts, `rtok config init` and some runtime messages printed internal plan ids (`T70.2`, `D21`, `I-101`, `§16`) that mean nothing to a user.

Done means: no clap doc comment, `about`, arg help, runtime message or `config/default.toml` comment prints a plan id; developer-only traceability moves to plain `//` comments; a guard test renders every `--help`, the man page, every completion script and the config template and fails on a plan-id match; the trycmd goldens match the new text.

Check: `just check`; the guard test in `tests/no_plan_ids.rs`.

Result: ids removed from 31 clap doc comments in `src/cli.rs` (each id moved into a plain `//` line above the doc comment), 54 comment lines in `config/default.toml`, and 7 runtime strings (Grok doctor/setup lines, the `tools_rewrite` doctor advice, the retire-not-stack report advice, the expand-rate report blurb in HTML and Markdown). `tests/no_plan_ids.rs` walks `Cli::command()` (hidden subcommands too), writes every man page, generates every completion script and reads `config/default.toml`, and fails on `T\d+(.\d+)*`, `D\d+`, `I-\d+`, `P\d+.\d+` or `§\d+` (a pattern test pins lookalikes such as `UTF-8`, `x86_64`, `ID3`, `P1`). The trycmd goldens were regenerated and the diff read: only id removals and the three rephrasings that left a dangling clause.

Status: done 2026-10-04
Model: Claude Code / sonnet-5-5
