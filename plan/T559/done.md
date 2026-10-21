Confirmed by an actual-source probe: [host_keys](src/plugins/guard/skill.rs#L157) checks raw line prefixes, so valid frontmatter such as `"model": value` or `'allowed-tools': value` bypasses the exemption. [decide](src/plugins/guard/skill.rs#L125) then archives and denies an oversized skill whose host-applied model/tool/context behavior must be preserved. The T62.1 contract is semantic frontmatter key presence, not a particular YAML spelling.

Validation: the probe parsed quoted model/allowed-tools keys with the repository's serde-saphyr version and confirmed host_keys returns false for the same valid YAML.

Execution plan:

1. Add oversized-skill fixtures with single/double-quoted top-level host keys and assert no digest denial or saving row.
2. Inspect the frontmatter mapping with the existing YAML parser, preserving host behavior for allowed-tools/model/context/agent; malformed or ambiguous frontmatter must fail open.
3. Cover unquoted keys, CRLF and ordinary prose mentioning those words, while retaining digest behavior for valid frontmatter without host-affecting keys.

Check: equivalent YAML spellings receive the same exemption, host behavior is not lost through a digest denial, and `just check` passes.

Result: `host_keys` parses the frontmatter with `serde_saphyr` into a string-keyed map, so quoted keys are recognised; frontmatter that does not parse counts as exempt and is never digested.

Check: `host_keys_are_recognised_whatever_their_yaml_spelling`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
