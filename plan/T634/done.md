Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` by running the actual [publisher](tools/pypi-publish.sh#L18) with temporary artifacts and fake git/cargo/twine executables. Both options overwrite one `mode` variable: `--dry-run --testpypi` invokes `twine upload --repository-url https://test.pypi.org/legacy/`, whereas the reverse order and `--dry-run` alone only invoke `twine check`. A dry run can therefore upload if the repository-selection flag follows it. No network upload occurred in the probe.

Execution plan:

1. Add a fake-twine command test for both flag orders, repeated flags and each standalone option. Require every invocation containing `--dry-run` to perform no upload.
2. Store repository selection and dry-run state independently, or reject the combination explicitly before any publishing command. Keep the target repository visible in dry-run output.
3. Preserve version/artifact validation and production wheel completeness checks for the chosen target; verify validation failures never upload.
4. Run publisher script checks and `just check`.

Check: flag order cannot turn a requested dry run into a publishing operation. The actual shell script ran with synthetic distribution files and a logging-only twine stub; package validity and registry authentication were not exercised.

Result: `tools/pypi-publish.sh` keeps dry run and target in separate variables, so `--dry-run --testpypi` never uploads; the dry-run output names the target.

Check: `tests/pypi_publish.rs`: `a_dry_run_never_uploads_in_any_flag_order` and three more, fake tools only.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
