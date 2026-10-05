// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import type { StorybookConfig } from "@storybook/react-vite";
import { fileURLToPath } from "node:url";
import { mergeConfig, searchForWorkspaceRoot } from "vite";

const config: StorybookConfig = {
  stories: ["../src/**/*.stories.@(ts|tsx)"],
  addons: ["@storybook/addon-a11y", "@storybook/addon-vitest"],
  framework: "@storybook/react-vite",
  core: { disableTelemetry: true },
  // Storybook replaces the dev server's fs allow list from vite.config.ts, which would block the
  // fonts and icons served from the brand pack (T414).
  viteFinal: (vite) =>
    mergeConfig(vite, {
      server: {
        fs: {
          allow: [
            searchForWorkspaceRoot(process.cwd()),
            fileURLToPath(new URL("../../brand", import.meta.url)),
          ],
        },
      },
    }),
};

export default config;
