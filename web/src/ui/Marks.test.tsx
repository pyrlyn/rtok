// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// @vitest-environment happy-dom
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, test } from "vitest";
import { ShareBar } from "./Marks";

const width = (share: number) => {
    const { container } = render(<ShareBar title="t" share={share} rows={[]} />);
    return (container.firstElementChild!.firstElementChild as HTMLElement).style.width;
};

afterEach(cleanup);

describe("ShareBar", () => {
    test("fills the share of the track", () => {
        expect(width(0.25)).toBe("25.0%");
    });

    test("clamps a share outside 0..1 and reads a non-number as empty", () => {
        expect(width(3)).toBe("100.0%");
        expect(width(-1)).toBe("0.0%");
        expect(width(Number.NaN)).toBe("0.0%");
    });

    test("is a picture only: the figure sits in the cell beside it", () => {
        const { container } = render(<ShareBar title="t" share={0.5} rows={[]} />);
        expect(container.firstElementChild?.getAttribute("aria-hidden")).toBe("true");
    });
});
