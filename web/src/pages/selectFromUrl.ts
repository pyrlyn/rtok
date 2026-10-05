// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { useSearch } from "@tanstack/react-router";
import { useEffect } from "react";

/** The command palette opens a page on one row with `?id=` (T414.9); the page selects it. */
export function useSelectFromUrl(select: (id: string) => void) {
  const { id } = useSearch({ strict: false }) as { id?: string };
  useEffect(() => {
    if (id) select(id);
  }, [id, select]);
}
