// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { act, renderHook } from "@testing-library/react";
import { expect, test, vi } from "vitest";
import { project } from "../../api/sampleRows";
import { buildScene } from "./scene";
import { useLayout } from "./useLayout";

const opts = { query: "", scopeOnly: false };
const pair = buildScene(
  [
    project(1, "a", {
      selected: true,
      links: [{ kind: "manual", name: "b", reason: null, to: 2 }],
    }),
    project(2, "b"),
  ],
  opts,
);
const trio = buildScene(
  [project(1, "a", { selected: true }), project(2, "b"), project(3, "c")],
  opts,
);

test("subscribers hear every frame, and settled holds only from the resting frame until new topology", async () => {
  vi.useFakeTimers();
  const { result, rerender, unmount } = renderHook(({ scene }) => useLayout(scene), {
    initialProps: { scene: pair },
  });
  const positions = result.current;
  let frames = 0;
  const off = positions.subscribe(() => frames++);
  expect(positions.settled).toBe(false);

  await act(() => vi.advanceTimersByTimeAsync(40));
  expect(frames).toBeGreaterThan(0);
  expect(positions.settled).toBe(false);

  await act(() => vi.advanceTimersByTimeAsync(10_000));
  expect(positions.settled).toBe(true);
  expect([...positions.map.keys()].sort()).toEqual([1, 2]);

  // The stage keeps its `positions` across a topology change, so the flag must drop on the same object.
  rerender({ scene: trio });
  expect(result.current).toBe(positions);
  expect(positions.settled).toBe(false);
  await act(() => vi.advanceTimersByTimeAsync(10_000));
  expect(positions.settled).toBe(true);
  expect([...positions.map.keys()].sort()).toEqual([1, 2, 3]);

  off();
  const heard = frames;
  rerender({ scene: pair });
  await act(() => vi.advanceTimersByTimeAsync(10_000));
  expect(frames).toBe(heard);
  unmount();
  vi.useRealTimers();
});
