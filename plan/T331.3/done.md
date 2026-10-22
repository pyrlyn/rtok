Part of T331. Section 2 of T331: effective set per agent, normalization, the "not duplicates" cases, the keep recommendation, the report and `problems[]` entries with `keep`. Report only. Depends on T331.1 and T331.2.

Check: the "Duplicate hooks across configs" and "Not duplicates" scenarios of T331; `just check`.

Execution: `src/doctor/dupes.rs` groups every hook entry the T331.1 and T331.2 scans saw (not only the problems) by agent, event, matcher and command; `hooks.rs` records each entry with its keep rank (plugin, project `settings.json`, user file, `.local` file) and a normalized command (words split, variables and `~` expanded, a `./` path made absolute where the host documents its base, quoting and spacing removed). `Problem` gains `group` and `keep`; each copy is one `duplicate-hook` finding with `fixable` false until T331.6. The text always prints `duplicate hooks none found` or the groups with their copies.

Result: `rtok doctor` (text, `--json`, Doctor page) lists the hooks one agent loads more than once, how many times each runs, every copy with its file and key path, and the copy to keep. `research.md` has no host that runs identical hooks once, so no "harmless on host" case exists yet; the copies are reported as running twice for every host.

Model: Claude Code / sonnet-5
