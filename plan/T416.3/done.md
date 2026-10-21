`config init`, `config set`, `memory sync`, `agents install`, `agents uninstall`, `agents update` and `doctor --fix` gain `--stat`: the per-file stat lines and the totals line instead of the diff. The default `--dry-run` diff ends with the same totals line.

Check: trycmd cases for `--dry-run` and `--dry-run --stat` on `config set` and one installer; `surface_parity` and `config_coverage` green; `just check` green.

Result: `config init`, `config set`, `memory sync`, `agents install/uninstall/update` and `doctor --fix` take `--stat` (requires `--dry-run`, or `--fix` on `doctor`); every default `--dry-run` diff now ends with the totals line. `src/stat_preview.rs` counts the diff lines for `change_preview`. Installers show one stat row per host (the step lines carry no path). trycmd: `config-set-stat`; `tests/agents_install.rs` covers the installer case.
