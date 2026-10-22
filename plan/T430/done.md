`std::fs::canonicalize` on Windows returns `\\?\` paths. `starts_with` and `==` then disagree with `C:\…` and with ASCII case, so worktree identity, the read guard and "do not index `$HOME`" miss real directories.

`src/fs.rs` has one helper: `canon`, `path_starts_with`, `same_path`, `strip_prefix`. `dunce` drops the verbatim prefix when the path does not need it, and on Windows the component check ignores ASCII case. `read`, `search`, `worktree`, `doctor` and junk symlink targets use it. `docs/windows.md` says so.

Check: `windows_path_identity_folds_verbatim_prefix_and_ascii_case`, `resolved_drops_the_verbatim_prefix`, `under_ascii_case_insensitive_matches_windows_prefix`, `display_rel_strips_ascii_case_insensitive_prefix`.
