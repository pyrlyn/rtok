Left over from T329.32. The numbers of the live cards count up to their new value over a short time and jump straight to it under `prefers-reduced-motion`; the final value is always the server's total, and an assistive-technology reader sees the final value only. Web-only.

Check: a number counts up on a new frame and lands on the server's value; with `prefers-reduced-motion` it jumps; Vitest, stories (axe); `just check`.

Result: `web/src/ui/CountUp.tsx` (`useCountUp`, `CountUp`) eases a figure to its new value over 600 ms, draws the first value as is, jumps under `prefers-reduced-motion` and lands on the server's value itself on the last frame; while it moves the changing text is `aria-hidden` and the final figure sits in an `sr-only` span. Landed on main with #993 (`1839ad321`); the branch's copy in `live/CountUp.tsx` (`dce2e9e38`) was dropped in the merge as a duplicate. Covered by `CountUp.test.tsx` and `CountUp.stories.tsx` (axe).
