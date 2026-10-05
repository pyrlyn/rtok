// Copyright (c) 2026 Ivan Tugay
// SPDX-License-Identifier: GPL-3.0-or-later
// Licensed under GPL-3.0 or later; see https://www.gnu.org/licenses/gpl-3.0.html

// Behaviour (focus trap, Escape, outside click, virtual focus while typing, filtering) is
// React Aria's; the look is ours on the brand roles (T414.9).

import { Fragment, useMemo, type ReactNode } from "react";
import {
    Autocomplete,
    Button,
    Dialog,
    Header,
    Heading,
    Input,
    Menu,
    MenuItem,
    MenuSection,
    Modal,
    ModalOverlay,
    SearchField,
    useFilter,
} from "react-aria-components";
import type { Snapshot } from "../api/snapshot.gen";
import { PAGES, type Page } from "../pages";
import { focusRing } from "../ui/cx";
import { Icon } from "../ui/Icon";
import { Kbd, paletteKeys } from "./Kbd";
import { GO } from "./shortcuts";

export interface Target {
    page: Page["id"];
    /** A row the page selects on arrival (`?id=`). */
    id?: string;
}

const SESSIONS = 30;

interface Entry {
    id: string;
    /** What the filter matches, wider than the label. */
    text: string;
    icon: string;
    label: string;
    hint?: ReactNode;
    go: Target;
}

const overlay = "fixed inset-0 z-50 flex items-start justify-center bg-bg/60 px-3 pt-[12vh]";
const panel =
    "glass flex max-h-[70vh] w-full max-w-lg flex-col overflow-hidden rounded-lg border border-border-strong shadow-lg outline-none";
const item =
    "flex cursor-default items-center gap-2.5 rounded-md px-2.5 py-1.5 text-xs text-fg-muted outline-none data-[focused]:bg-accent/15 data-[focused]:text-accent-fg";
const header = "px-2.5 pt-2 pb-1 text-2xs font-semibold text-fg-subtle";

interface OverlayProps {
    isOpen: boolean;
    onOpenChange(open: boolean): void;
}

export function Palette({
    isOpen,
    onOpenChange,
    snap,
    dark,
    onGo,
    onTheme,
}: OverlayProps & {
    snap: Snapshot | undefined;
    dark: boolean;
    onGo(target: Target): void;
    onTheme(): void;
}) {
    const { contains } = useFilter({ sensitivity: "base" });
    const groups = useMemo((): [string, Entry[]][] => {
        const all = snap?.sessions ?? [];
        // The newest sessions only: the list is for jumping, the Sessions page is for browsing.
        const sessions = [...all]
            .sort((a, b) => b.last_activity - a.last_activity)
            .slice(0, SESSIONS);
        const hosts = [...new Set(all.flatMap((s) => (s.host ? [s.host] : [])))].sort();
        return [
            [
                "Pages",
                PAGES.map((p) => ({
                    id: `page:${p.id}`,
                    text: p.id,
                    icon: p.id,
                    label: p.id,
                    hint: <Kbd>g {GO[p.id]}</Kbd>,
                    go: { page: p.id },
                })),
            ],
            [
                "Plugins",
                (snap?.plugins ?? []).map((p) => ({
                    id: `plugin:${p.id}`,
                    text: `${p.title} ${p.id}`,
                    icon: "plugins",
                    label: p.title,
                    hint: p.id,
                    go: { page: "plugins", id: p.id },
                })),
            ],
            [
                "Sessions",
                sessions.map((s) => ({
                    id: `session:${s.id}`,
                    text: [s.id, s.host, s.project].filter(Boolean).join(" "),
                    icon: "sessions",
                    label: [s.host, s.project].filter(Boolean).join(" · ") || "session",
                    hint: s.id.slice(0, 8),
                    go: { page: "sessions", id: s.id },
                })),
            ],
            // The Hosts page is text with no rows, so a host only opens it.
            [
                "Hosts",
                hosts.map((h) => ({
                    id: `host:${h}`,
                    text: h,
                    icon: "hosts",
                    label: h,
                    go: { page: "hosts" },
                })),
            ],
        ];
    }, [snap?.plugins, snap?.sessions]);
    const go = (target: Target) => () => {
        onOpenChange(false);
        onGo(target);
    };

    return (
        <ModalOverlay isOpen={isOpen} onOpenChange={onOpenChange} isDismissable className={overlay}>
            <Modal className="w-full max-w-lg">
                <Dialog aria-label="Command palette" className={panel}>
                    <Autocomplete filter={contains}>
                        <SearchField
                            aria-label="Search pages, plugins, sessions and hosts"
                            autoFocus
                        >
                            <Input
                                placeholder="Jump to…"
                                className={`${focusRing} h-11 w-full border-b border-border bg-transparent px-3.5 text-sm text-fg placeholder:text-fg-subtle`}
                            />
                        </SearchField>
                        <Menu
                            aria-label="Results"
                            className="overflow-y-auto p-1.5 outline-none"
                            renderEmptyState={() => (
                                <p className="px-2.5 py-3 text-xs text-fg-subtle">
                                    Nothing matches.
                                </p>
                            )}
                        >
                            {groups
                                .filter(([, entries]) => entries.length > 0)
                                .map(([title, entries]) => (
                                    <MenuSection key={title}>
                                        <Header className={header}>{title}</Header>
                                        {entries.map((e) => (
                                            <MenuItem
                                                key={e.id}
                                                id={e.id}
                                                textValue={e.text}
                                                onAction={go(e.go)}
                                                className={item}
                                            >
                                                <Icon name={e.icon} />
                                                <span className="flex-1 truncate">{e.label}</span>
                                                <span className="text-fg-subtle">{e.hint}</span>
                                            </MenuItem>
                                        ))}
                                    </MenuSection>
                                ))}
                            <MenuSection>
                                <Header className={header}>Actions</Header>
                                <MenuItem
                                    id="theme"
                                    textValue="switch theme light dark"
                                    onAction={() => {
                                        onOpenChange(false);
                                        onTheme();
                                    }}
                                    className={item}
                                >
                                    <Icon name="theme" />
                                    <span className="flex-1">
                                        {dark ? "Switch to light theme" : "Switch to dark theme"}
                                    </span>
                                </MenuItem>
                            </MenuSection>
                        </Menu>
                    </Autocomplete>
                </Dialog>
            </Modal>
        </ModalOverlay>
    );
}

export function ShortcutHelp({ isOpen, onOpenChange }: OverlayProps) {
    return (
        <ModalOverlay isOpen={isOpen} onOpenChange={onOpenChange} isDismissable className={overlay}>
            <Modal className="w-full max-w-sm">
                <Dialog className={`${panel} gap-3 p-4`}>
                    <Heading slot="title" className="text-sm font-semibold text-fg">
                        Keyboard shortcuts
                    </Heading>
                    <dl className="grid grid-cols-[auto_1fr] items-center gap-x-4 gap-y-1.5 overflow-y-auto text-xs text-fg-muted">
                        <dt>
                            <Kbd>{paletteKeys()}</Kbd>
                        </dt>
                        <dd>Command palette</dd>
                        <dt>
                            <Kbd>?</Kbd>
                        </dt>
                        <dd>This help</dd>
                        {PAGES.map((p) => (
                            <Fragment key={p.id}>
                                <dt>
                                    <Kbd>g {GO[p.id]}</Kbd>
                                </dt>
                                <dd>{p.id}</dd>
                            </Fragment>
                        ))}
                    </dl>
                    <Button
                        slot="close"
                        className={`${focusRing} h-8 self-end rounded-md border border-border px-3 text-xs text-fg-muted hover:border-border-strong hover:text-fg`}
                    >
                        Close
                    </Button>
                </Dialog>
            </Modal>
        </ModalOverlay>
    );
}
