Confirmed through actual Clap argument parsing and [report_flags](src/cli/report.rs#L32): [Args.format](src/cli/report.rs#L63) defaults to Md, and the flag layer treats every Md value as omitted. With report.format=html in a lower-precedence config layer, explicitly passing --format md produces no format override and the effective value remains html; --format pdf correctly overrides it. [run](src/cli/report.rs#L89) merges this flag layer and selects the renderer using cfg.report.format. Thus the explicit CLI request can produce HTML or PDF instead of Markdown. D12 and docs/config.md promise flags override configuration; T22.1/T22.2 and T60 do not cover explicit selection of the built-in default.

Execution plan:

1. Add argument/config regressions for configured html and pdf with omitted --format, explicit md and explicit pdf/html. Assert omission preserves configuration but every explicit format wins; include combinations with --out and --since.
2. Preserve whether --format was supplied, for example with an Option<ReportFormat>, and insert every explicitly supplied value into the flag layer. Keep md as the configuration default and preserve the documented --ai precedence.
3. Cover environment and project/user config layers, help/completion output, and a CLI rendering fixture that verifies Markdown bytes for an explicit --format md over configured HTML/PDF.

Check: explicit format flags always select the requested renderer, an omitted flag still respects lower-precedence settings, and `just check` passes. The scratch probe used actual ReportFormat/Args/report_flags with Clap and a Figment config layer; full report construction and file output were not run.

Result: `report --format` is `Option<ReportFormat>` and every supplied value enters the flag layer, so an explicit `--format md` beats a configured html or pdf. Help no longer prints `[default: md]`.

Check: `tests/report.rs::explicit_format_md_overrides_configured_format` failed before and passes after; `help-subcommands.trycmd` updated.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
