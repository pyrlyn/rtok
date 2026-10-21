Same creator request as T246.1: `agents remove <host>` takes back a shipped skill copy only as rtok wrote it; a copy the user edited is asked about.

Result: `SkillCopy::run` on remove checks `edited_since_marked`: anything under the owned copy newer than its `.rtok-owned` marker (which `copy_owned` writes last) is a user write — an edited or an added file. Such a copy goes through `rtok_agent_sdk::keep_edited` (T246.3): `?` on a dry run, `--yes` or a yes removes it, else `leave <dest> (changed by you; remove by hand)`. Comparing times instead of bytes with `skills/<name>` keeps a copy from an older rtok (whose shipped skill has since changed) removable without a question, and needs no hash dependency.

Check: `skill_copy_remove_asks_before_taking_an_edited_copy` (a file dated after the marker keeps the copy without `--yes`, `--yes` takes it); SDK 29/29; `just check` green. The T250.3 card also got the T251 hint (tolerate a broken pipe on the hook stdin write).

Status: done 2026-09-24
Model: Claude Code / claude-opus-5-5
