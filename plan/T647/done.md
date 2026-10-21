`ci / spa` failed intermittently on `GraphCompare.stories.tsx > Changes On The Graph Light` (run 38309415046, PR #1020: no button `− legacy_hook, function · removed`) and on `GraphDrill.stories.tsx > More On A Large Project` (job 114262428057: 15 s test timeout), and the failing job cancels the whole run.

Root causes:
- Compare: `Scene2D` draws a node only once the layout worker has placed it. The ghost `legacy_hook` enters the layout only when Compare turns on, while `run` and `resolve` are already placed, so the play function's `findBy(run)` passed and the `getBy(legacy_hook)` right after it lost the race whenever the new layout frame came late.
- Large project: the play function took ~0.8 s locally, but the a11y check that runs after it took ~2.3 s over ~5,900 elements (600 drawn nodes); with the CPU throttled 4× through CDP the story took 18.0 s and timed out (6×: 33.5 s).

Fix:
- `web/src/pages/slowLayout.ts`: `slowNewNodes(ms)`, a story `beforeEach` that withholds the position of a node a later topology adds for `ms` after the frame that first carries it, so the slow-worker case replays on every run. `Changes On The Graph Slow Layout` uses it: with the old play function it failed 3/3 with the CI message; it now passes.
- `Changes On The Graph` awaits every node it asserts (`findBy`), since each needs its own layout frame.
- `More On A Large Project` turns its a11y check off: by the time axe runs, the "+100 more" button is gone, and the node and panel markup is checked in `Files`. Throttled 4×/6× it now takes 3.4 s/5.2 s.

Check: `just js`, `just spa-stories` (255/255) pass. `just spa-test`: the unit project does not import the changed files; on this host, under load average 50–290, 1–5 unrelated tests hit their 5 s timeout and the set changed from run to run.
