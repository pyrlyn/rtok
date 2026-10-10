// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { describe, expect, test } from "vitest";
import { project } from "../../../api/sampleRows";
import { buildScene } from "../scene";
import type { Vec3 } from "../useLayout";
import { boxAround, easeOut, lerpBox, sphereOf } from "./camera";

const scene = buildScene([project(1, "rtok"), project(2, "ketch"), project(3, "pyrlyn")], {
  query: "",
  scopeOnly: false,
});
const id = (name: string) => scene.nodes.find((n) => n.label === name)!.id;
const radius = (name: string) => scene.nodes.find((n) => n.label === name)!.radius;
const at = (name: string, p: Vec3) => [id(name), p] as const;
const map = new Map<number, Vec3>([
  at("rtok", [0, 0, 0]),
  at("ketch", [400, 0, 0]),
  at("pyrlyn", [0, 300, 0]),
]);

describe("2D framing", () => {
  test("without ids the box holds every node and its padding", () => {
    const b = boxAround(scene, map);
    expect(b.x).toBeCloseTo(-radius("rtok") - 30);
    expect(b.x + b.w).toBeCloseTo(400 + radius("ketch") + 30);
    expect(b.y + b.h).toBeCloseTo(300 + radius("pyrlyn") + 30);
  });

  test("with ids the box holds only those nodes and is smaller than the whole", () => {
    const whole = boxAround(scene, map);
    const one = boxAround(scene, map, [id("ketch")]);
    expect(one.w).toBeLessThan(whole.w);
    const centre = { x: one.x + one.w / 2, y: one.y + one.h / 2 };
    expect(centre.x).toBeCloseTo(400);
    expect(centre.y).toBeCloseTo(0);
  });

  test("a lone node is not zoomed in to a point", () => {
    const b = boxAround(scene, map, [id("rtok")]);
    expect(b.w).toBeGreaterThanOrEqual(140);
    expect(b.h).toBeGreaterThanOrEqual(140);
  });

  test("two nodes frame both", () => {
    const b = boxAround(scene, map, [id("rtok"), id("ketch")]);
    expect(b.x).toBeLessThan(0);
    expect(b.x + b.w).toBeGreaterThan(400);
    expect(b.y + b.h).toBeLessThan(300);
  });

  test("ids the layout has not placed frame the whole picture; no nodes give the default box", () => {
    expect(boxAround(scene, map, [999])).toEqual(boxAround(scene, map));
    expect(boxAround(scene, new Map())).toEqual({ x: -100, y: -100, w: 200, h: 200 });
  });

  test("easing starts at the old box, ends at the new one and does not overshoot", () => {
    const a = { x: 0, y: 0, w: 100, h: 100 };
    const b = { x: 50, y: -20, w: 300, h: 200 };
    expect(lerpBox(a, b, 0)).toEqual(a);
    expect(lerpBox(a, b, 1)).toEqual(b);
    expect(easeOut(0)).toBe(0);
    expect(easeOut(1)).toBe(1);
    expect(easeOut(0.5)).toBeGreaterThan(0.5);
    expect(easeOut(0.5)).toBeLessThan(1);
  });
});

describe("3D framing", () => {
  const pts = [
    { p: [0, 0, 0] as Vec3, r: 5 },
    { p: [100, 0, 0] as Vec3, r: 5 },
  ];

  test("the sphere is centred between the nodes and reaches their far surface", () => {
    const s = sphereOf(pts, 20)!;
    expect(s.center).toEqual([50, 0, 0]);
    expect(s.radius).toBeCloseTo(55);
  });

  test("a lone node keeps the floor radius", () => {
    expect(sphereOf([{ p: [3, 4, 5], r: 4 }], 20)).toEqual({ center: [3, 4, 5], radius: 20 });
  });

  test("no nodes, no sphere", () => {
    expect(sphereOf([], 20)).toBeNull();
  });
});
