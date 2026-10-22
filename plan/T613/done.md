Confirmed on `2d91199fdb507af71eedbcf1b7ed127a06b39784` using the actual in-memory Store. Register a sub-agent with `register_agent(host, session, Some("sub-1"), ...)` before that session's main row, then register the main agent and repeat the sub-agent registration. [register_agent](crates/rtok-store/src/agents.rs#L202) initially obtains no parent from `main_agent_id`, inserts `parent_id = NULL`, and its conflict-update fields never repair that parent. The repeated call returns the same sub-agent id but leaves it unattached; `agent_children(main)` is empty. Hook dispatch directly passes the input's sub-agent key to this method, so the first observed event of a session being a sub-agent event is not rejected or repaired by the caller.

The broken relation also affects [session_views](src/model/agents.rs#L73). Both rows become roots, and the first root matching the host/session is selected as the session's main agent. The probe registered the child more than one second earlier to make the store's ordering deterministic: `agents_of_sessions` returned it first, and the actual model selected it instead of the real main row. This can display the wrong agent identity/activity/state and omit the intended child relationship. T282's existing parent test registers the main row first; T284 covers ordinary nesting, and T324 covers revival on repeat registration. None covers a late main row repairing an existing child.

Execution plan:

1. Add store regressions for sub-agent-first registration, subsequent main registration and child re-registration. Assert stable ids, exactly one main row and correct `parent_id`/`agent_children` results. Retain the main-first control and isolate host/session identities.
2. Establish the parent relation in the registry layer for either arrival order. Ensure or reconcile the main row and repair existing unparented sub-agent rows through typed Diesel operations, without changing a legitimate relationship or clearing activity/status fields. Make the operation safe under concurrent registrations and partial failures.
3. Add model and hook-dispatch regressions for a first observed sub-agent event followed by a main event. Assert that the true main agent is selected and the child is nested; include ended/live filtering so an orphan's state cannot hide or mislabel the session. Preserve same-id repeat behavior and existing T324 revival semantics.
4. Cover pre-existing orphan rows through the chosen reconciliation path without requiring their ids to change. Run focused store/agent/model/hook tests and `just check`.

Check: event arrival order no longer changes parentage or which agent represents the session. The isolated executable linked the actual `rtok-store` crate, used its in-memory migrations and public methods, imported actual agent-view types and compiled the production pure model functions. The two orders and repeated-registration controls were executed without raw SQL or mocked store behavior. SessionTotals were synthetic; full CLI output, live hosts, real agents and concurrent OS processes were not exercised.

Result: A sub-agent registered before its main agent is attached once the main registers, and orphans already in the database are repaired in place.

Check: `a_sub_agent_registered_before_its_main_is_attached_to_it`, `existing_orphan_sub_agents_are_repaired_in_place`, `a_session_seen_first_through_a_sub_agent_keeps_its_main_agent`, `a_sub_agent_event_before_the_main_one_still_nests_under_the_main_row`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
