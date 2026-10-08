// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

import { Link, Outlet, useNavigate, useRouterState } from "@tanstack/react-router";
import { Suspense, useEffect, useRef, useState } from "react";
import logo from "@brand/logo/rtok-mark.svg";
import { useConnection, useReconnect, useSnapshot } from "./api/query";
import { LiveStatus } from "./LiveStatus";
import { Orb } from "./Orb";
import { Kbd, paletteKeys } from "./palette/Kbd";
import type { Target } from "./palette/Palette";
import { useShortcuts } from "./palette/shortcuts";
import { PAGES, type Page } from "./pages";
import { Empty, ErrorState, Offline } from "./states";
import { useTheme } from "./theme";
import { focusRing } from "./ui/cx";
import { Icon } from "./ui/Icon";
import { lazyPart } from "./ui/lazyPart";

// React Aria comes with the palette, so neither weighs on the first paint.
const Palette = lazyPart("the command palette", () =>
    import("./palette/Palette").then((m) => m.Palette),
);
const ShortcutHelp = lazyPart("the shortcut sheet", () =>
    import("./palette/Palette").then((m) => m.ShortcutHelp),
);

// Narrow screens get a bottom tab bar (icon over label, centred); from md up it is the sidebar.
const navLink = `${focusRing} flex min-w-16 shrink-0 flex-col items-center justify-center gap-1 rounded-md px-2 py-1.5 text-2xs md:h-9 md:min-w-0 md:flex-row md:justify-start md:gap-3 md:px-2.5 md:py-0 md:text-xs text-fg-muted hover:bg-surface-2 hover:text-fg aria-[current=page]:bg-accent/15 aria-[current=page]:text-accent-fg`;

export function Shell() {
    const connection = useConnection();
    const { data } = useSnapshot();
    const { dark, toggle } = useTheme();
    const pathname = useRouterState({ select: (s) => s.location.pathname });
    const title = PAGES.find((p) => `/${p.id}` === pathname)?.id ?? "not found";
    const heading = useRef<HTMLHeadingElement>(null);
    const mounted = useRef(false);
    const reconnect = useReconnect();
    // Latched: the socket's own retries pass through `connecting`, which must not flash the
    // shell back in until a link is actually open.
    const [offline, setOffline] = useState(false);
    if (connection === "closed" && !offline) setOffline(true);
    if (connection === "open" && offline) setOffline(false);
    const navigate = useNavigate();
    const [palette, setPalette] = useState(false);
    const [help, setHelp] = useState(false);
    // Latched: the overlays load on first use, then stay mounted so closing them goes through
    // React Aria, which hands focus back to whatever opened them.
    const [overlaysUsed, setOverlaysUsed] = useState(false);
    if ((palette || help) && !overlaysUsed) setOverlaysUsed(true);
    const go = (t: Target) => navigate({ to: `/${t.page}`, search: t.id ? { id: t.id } : {} });
    useShortcuts({
        palette: () => setPalette((open) => !open),
        help: () => setHelp(true),
        go: (page) => {
            setHelp(false);
            go({ page });
        },
    });

    useEffect(() => {
        document.title = `${title} · rtok`;
        // A client-side route change moves no focus on its own; without this a keyboard or
        // screen-reader user stays on the link they pressed and hears nothing.
        if (mounted.current) heading.current?.focus();
        mounted.current = true;
    }, [title]);

    if (offline)
        return <Offline connecting={connection === "connecting"} onReconnect={reconnect} />;

    return (
        <>
            <a
                href="#main"
                className={`${focusRing} sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-accent focus:px-3 focus:py-2 focus:text-accent-on`}
            >
                Skip to content
            </a>
            <Orb />
            <Suspense>
                {overlaysUsed && (
                    <>
                        <Palette
                            isOpen={palette}
                            onOpenChange={setPalette}
                            snap={data}
                            dark={dark}
                            onGo={go}
                            onTheme={toggle}
                        />
                        <ShortcutHelp isOpen={help} onOpenChange={setHelp} />
                    </>
                )}
            </Suspense>
            <div className="min-h-screen md:grid md:grid-cols-[200px_minmax(0,1fr)] lg:grid-cols-[232px_minmax(0,1fr)]">
                <nav
                    aria-label="Admin screens"
                    className="glass fixed inset-x-2 bottom-2 z-40 flex gap-0.5 overflow-x-auto p-1 md:sticky md:inset-auto md:top-0 md:m-3 md:h-[calc(100vh-1.5rem)] md:flex-col md:overflow-y-auto"
                >
                    <div className="mb-2 hidden items-center gap-2.5 border-b border-border px-2.5 pt-2 pb-3 md:flex">
                        <img src={logo} alt="" width={28} height={28} className="rounded-md" />
                        <span className="flex flex-col leading-none">
                            <span className="text-sm font-bold tracking-wordmark">RTOK</span>
                            <span className="mt-1 text-2xs text-fg-subtle">
                                <span className="text-delta-fg">Δ</span>tok
                            </span>
                        </span>
                    </div>
                    {PAGES.map((p: Page) => (
                        <Link key={p.id} to={`/${p.id}`} className={navLink}>
                            <Icon name={p.id} />
                            {p.id}
                        </Link>
                    ))}
                </nav>
                <div className="flex min-w-0 flex-col">
                    <header className="flex items-end gap-3 px-3 pt-4 pb-3">
                        <div className="min-w-0 flex-1">
                            <h1
                                ref={heading}
                                tabIndex={-1}
                                className="truncate text-xl font-semibold outline-none"
                            >
                                {title}
                            </h1>
                        </div>
                        <LiveStatus />
                        <button
                            type="button"
                            onClick={() => setPalette(true)}
                            aria-label="Open the command palette"
                            aria-keyshortcuts={paletteKeys() === "⌘ K" ? "Meta+K" : "Control+K"}
                            className={`${focusRing} flex h-8 items-center gap-2 rounded-md border border-border px-2 text-xs text-fg-muted transition-colors duration-fast hover:border-border-strong hover:text-fg`}
                        >
                            <span className="hidden sm:inline">jump to</span>
                            <Kbd>{paletteKeys()}</Kbd>
                        </button>
                        <button
                            type="button"
                            onClick={toggle}
                            aria-pressed={!dark}
                            aria-label={dark ? "Switch to light theme" : "Switch to dark theme"}
                            title={dark ? "Light theme" : "Dark theme"}
                            className={`${focusRing} grid size-8 place-items-center rounded-md border border-border text-fg-muted transition-colors duration-fast hover:border-border-strong hover:text-fg`}
                        >
                            <Icon name="theme" />
                        </button>
                    </header>
                    <main
                        id="main"
                        tabIndex={-1}
                        className="flex min-w-0 flex-1 flex-col gap-3 px-3 pt-1 pb-24 outline-none md:pb-6"
                    >
                        {data?.error && <ErrorState message={data.error} />}
                        <Outlet />
                    </main>
                </div>
            </div>
        </>
    );
}

export function NotFound() {
    return <Empty title="Page not found" hint="Pick a screen from the navigation." />;
}
