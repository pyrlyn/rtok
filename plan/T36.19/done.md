**T36.19 a setup backup is never overwritten** · — · `crates/rtok-agent-sdk/src/lib.rs`
Do: the `.bak` collision loop gives up after 99 iterations and then `fs::copy` overwrites an existing backup.
Check: with 100 pre-existing `.bak-*` names the install still refuses to clobber one (unique name or an error). `cargo test -p rtok-agent-sdk`, all 9 tests pass, including `backup_skips_a_hundred_preexisting_names_without_clobbering`.
Complexity: 1/5
Status: done 2026-09-11 · Model: Composer 2.5
