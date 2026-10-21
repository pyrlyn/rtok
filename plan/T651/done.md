`Output::Inherit` used to wait with `Command::status`, which cannot take a deadline, so a 30 ms timeout on `sleep 0.25` returned success after 267 ms. Inherit now spawns and uses the same bounded wait as Capture and Stream: the child is killed at the deadline and the run returns `TimedOut`.

Landed in `888b0548` (#1053). This card records that the check already holds on main.

Check: the inherited slow-child regression fails before the fix and passes after it; inherited streams remain inherited; Capture and Stream timeout behavior stays intact; `just check` passes.

Check result: `an_inherited_slow_child_is_killed_at_the_timeout` returns `TimedOut` inside a second for `sleep 2` with a 100 ms limit. `an_inherited_child_inside_its_timeout_keeps_its_own_status` keeps exit code 3 and empty captured streams, and a zero timeout still times out. `an_inherited_child_that_cannot_start_fails_to_spawn_not_to_time_out` returns `NotFound`. `a_slow_child_is_killed_at_the_timeout` still covers Capture.

Status: done 2026-10-19
