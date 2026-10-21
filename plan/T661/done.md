`ci / spa` failed intermittently on `GraphOverview.stories.tsx > Webgl Draws And Click Selects` (jobs 114192688574, 114122668246: `Unable to find an element with the text: ketch`) and on `Projects.stories.tsx > Link And Unlink Against Sample Server` (job 111193690455: the "not linked" line was found, then `element is not in the document`).

Root causes:
- Overview: both jobs ran on commits before T503 (#959), and T503 is this failure (it names run 38044250038). No `ci / spa` run has failed on it since.
- Link and unlink: `Stage3D.setScene` wrote `instanceColor!.needsUpdate`, but three.js leaves `instanceColor` null when there is no edge. Unlinking the sample's only link made `<Scene3D>` throw, and the router's CatchBoundary rebuilt the page under the play function. #710 fixed it an hour after the failure but added no test: with the guard removed, all 255 stories still passed. Two races decided whether the story hit it. One was the graph view key that the GraphOverview stories leave in `localStorage`, which every Vitest iframe shares because they have one origin, so it follows file order. The other was whether the lazy 3D stage had mounted before the click. Reproduced locally with the old sample: view `3d` with the stage mounted failed 2/2 with the CI message, and `2d` passed.

Fix:
- `GraphOverview.stories.tsx`: `Webgl Without Links` draws the 3D overview of two unlinked projects. It shares a `drawn3d` helper with `Webgl Draws And Click Selects`, and with the #710 guard removed it fails 2/2 (`needsUpdate`, then no `graph-3d`).
- `.storybook/preview.tsx`: a project `beforeEach` clears `localStorage`, so no story inherits another story's graph view. `First Visit Opens 3D` runs right after a 2D story with no view decorator and checks that the 3D chip is pressed. It fails 2/2 on the whole file without the clear and passes with it. It checks the choice, not the drawing: a first version that waited for the 3D canvas hit the 15 s test timeout at load average 215.

Check: `just spa-stories` 257/257 twice; `just spa-test` 300/300; `just js`: oxlint is clean, and oxfmt flags only `web/e2e/rtok.ts`, which is unformatted on `main` and untouched here.
