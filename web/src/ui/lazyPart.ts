// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { lazy, type ComponentType } from "react";

/**
 * A component from its own chunk, so a heavy optional part stays off the first paint. A chunk
 * that fails to load warns and renders nothing: the part goes dead, never the page.
 */
export function lazyPart<P extends object>(what: string, load: () => Promise<ComponentType<P>>) {
  const none: ComponentType<P> = () => null;
  return lazy(() =>
    load().then(
      (c) => ({ default: c }),
      (e: unknown) => {
        // A constant format string: `what` is ours today, but a "%" in it must never shape the log.
        console.warn("rtok: %s failed to load", what, e);
        return { default: none };
      },
    ),
  );
}
