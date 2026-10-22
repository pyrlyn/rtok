Left over from T329.32. On screens narrower than 900 px the live part stacks below the explorer and its metrics collapse to a one-line strip (running, calls, saved) that expands to the full cards. Web-only.

Check: under 900 px the metrics show as a strip that expands and collapses; at 900 px and above nothing changes; Vitest, stories (axe) and Playwright at 375 px; `just check`.

Result: `live/MetricsFold.tsx` shows running, calls and saved as one disclosure button (`aria-expanded`, `aria-controls`, "More" / "Less") under 900 px and keeps the full cards in the DOM, hidden by CSS, so the 900 px breakpoint is one media query with no resize listener; from 900 px up nothing changes. Landed on main with #993 (`1839ad321`); the branch's own strip (`bf1c181f7`: `MetricsStrip` with `useMinWidth` and a conditional render of the cards) was dropped in the merge as a duplicate. Covered by `MetricsFold.test.tsx`, the `MetricsFold` story (axe) and `e2e/metrics-fold.spec.ts`.
