// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { expect, test } from "vitest";
import { hasIcon } from "./Icon";
import { OPERATION_ICONS, operationIcon } from "./operations";

test("a verb takes its operation's icon, whatever its form or case", () => {
  expect(operationIcon("install")).toBe("install");
  expect(operationIcon("Installing")).toBe("install");
  expect(operationIcon("expand")).toBe("expand");
  expect(operationIcon("worktree add")).toBe("worktrees");
});

test("a stem the other contains wins by order: uninstall is a removal, unlink a link", () => {
  expect(operationIcon("uninstall")).toBe("remove");
  expect(operationIcon("unlink")).toBe("link");
});

test("a verb that names no operation falls back to the kind's icon", () => {
  expect(operationIcon("select")).toBe("info");
  expect(operationIcon("", "success")).toBe("success");
  expect(operationIcon("toggle", "warn")).toBe("warning");
  expect(operationIcon("toggle", "error")).toBe("error");
});

test("every operation and kind icon exists under brand/icons/ui or the base pack", () => {
  const names = [...OPERATION_ICONS.map(([, icon]) => icon), "success", "info", "warning", "error"];
  expect(names.filter((n) => !hasIcon(n))).toEqual([]);
});
