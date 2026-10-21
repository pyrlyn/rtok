The header shows when the last snapshot arrived ("updated 3 s ago") next to the link pill, and a pause button freezes the rendered snapshot while the socket stays open, so a table does not move under the reader. Paused state is announced and visible.

Check: unit test for the age formatter; a story for paused and live; pausing keeps rows stable while frames arrive (unit test on the query layer).

Result: `createApi` gains `pause()`/`resume()` (`web/src/api/query.tsx`): while paused, snapshot and snapshot_error frames fold into a held copy and the cache keeps the rendered snapshot, so `dataUpdatedAt` is the age on screen; resume applies the held snapshot, and expand/doctor/set replies keep working. `LiveStatus` (`web/src/LiveStatus.tsx`) shows "updated 3s ago" (reusing `ago`) beside the link pill, a "paused" pill, an `aria-pressed` pause/play button (new `pause.svg`/`play.svg`) and a polite live region; it ticks its own 1 s clock so the Shell does not re-render. Tests: `ageLabel` and component tick/pause/resume (`LiveStatus.test.tsx`), rows stable while paused (`query.test.ts`), Live/Paused/WaitingForFirstSnapshot stories (axe), and an e2e pause/resume test. Web unit 194 passed; `just spa-e2e` 18 passed; `just spa-stories` 129 of 130 (the one failure, `Unknown.stories.tsx`, is a load flake that passes alone); `just dup js` and typecheck clean. #828.

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
