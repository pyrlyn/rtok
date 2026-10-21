Source audit only; no product code changed or real services operated. Reviewed start/stop/restart/upgrade, supervisor state publication and locking, child logging and backoff, plus process signalling helpers and CLI dispatch. This sampled pass does not establish exhaustive coverage of every lifecycle race or platform.

Execution plan:

1. Trace start, stop, restart, upgrade and supervisor state publication, locking, backoff and child cleanup against their tests and completed tasks.
2. Compare stale state and process identity checks across entrypoints. Inspect log forwarding and restart boundaries without assuming a saved PID still belongs to the service.
3. Reproduce concrete gaps using isolated state files and a recording process adapter, never signalling real processes or invoking host applications. Deduplicate exact cases against lifecycle and platform tasks.
4. Add only confirmed findings with ordered fix steps and checks, complete this audit entry, validate plan/todo parity and the documentation diff, then update PR #986.

Result: filed T597 for stop/kill trusting stale saved PIDs without the boot/identity protection used by start and quiesce. Compared T73, T80 and platform/process tasks including T83.3: none records this stop-path omission. No additional lifecycle or log-forwarding candidate was sufficiently established to file.

Evidence revision: `73b07eafb56ffa9bc5ac7b76ff06ffbbde1d31b0`; main remained `ee8d66288b1d1b850ba24b7f9a9a70481695c3ca`. An offline scratch executable compiled the actual Service/State, file/read, targets and stop functions with minimal config/style adapters and a recording process adapter. With `since=1`, normal mode sent TERM to both simulated unrelated positive PIDs; force mode sent KILL to both. Both removed the state file; missing state sent no signals. Production signal helpers were source-reviewed and perform no birth-identity check. No real process was signalled or queried by the probe, and full CLI/kernel PID reuse was not exercised.

Validation: `CHECK_BASE=ee8d66288b1d1b850ba24b7f9a9a70481695c3ca mise exec -- just check` passed; the selective gate reported no code changes and skipped Cargo tests. `git diff --check` passed, and plan/todo contain 116 unique active IDs with equal sets.

Status: done 2026-10-11
Model: OpenAI / GPT-6
