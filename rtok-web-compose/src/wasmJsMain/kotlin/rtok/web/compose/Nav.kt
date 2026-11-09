// The sidebar's grouping of PAGES with each screen's title, subtitle and icon, as on the Figma "Pyrlyn" page.
package rtok.web.compose

/** One sidebar entry: page id, title, the header's subtitle and an icon path (24×24 view box). */
class NavEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: String,
)

/** A sidebar group; its name is the header kicker of its pages. */
class NavGroup(
    val name: String,
    val entries: List<NavEntry>,
)

val SIDEBAR: List<NavGroup> =
    listOf(
        NavGroup(
            "Observe",
            listOf(
                NavEntry("overview", "Overview", "Token savings across every agent, plugin and session", "M4 4h7v7H4zM13 4h7v4h-7zM13 10h7v10h-7zM4 13h7v7H4z"),
                NavEntry("calls", "Calls", "Every hook, MCP and proxy call rtok handled", "M3 12h4l3-8 4 16 3-8h4"),
                NavEntry("sessions", "Sessions", "Agent sessions rtok has seen, live and ended", "M3 5h18v14H3zM3 9h18"),
                NavEntry("logs", "Logs", "rtok's own log, newest first", "M9 6h12M9 12h12M9 18h12M4 6h.01M4 12h.01M4 18h.01"),
                NavEntry("stats", "Stats", "Token savings per command from the ledger", "M5 20v-9M12 20V5M19 20v-6M3 20h18"),
                NavEntry("usage", "Usage", "Agent usage read from the hosts' transcripts", "M12 14l4-4M4.6 18a9 9 0 1 1 14.8 0"),
            ),
        ),
        NavGroup(
            "Code",
            listOf(
                NavEntry(
                    "graph",
                    "Graph",
                    "Indexed projects, their links and live calls",
                    "M3 6a2 2 0 1 0 4 0a2 2 0 1 0-4 0M17 6a2 2 0 1 0 4 0a2 2 0 1 0-4 0M10 18a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7 6h10M6 8l5 8M18 8l-5 8",
                ),
                NavEntry(
                    "worktrees",
                    "Worktrees",
                    "Git worktrees rtok manages for agents",
                    "M6 3v12M15 6a3 3 0 1 0 6 0a3 3 0 1 0-6 0M3 18a3 3 0 1 0 6 0a3 3 0 1 0-6 0M18 9a9 9 0 0 1-9 9",
                ),
                NavEntry("tasks", "Tasks", "Tasks of this checkout, by state", "M10 6h11M10 12h11M10 18h11M3 6l1.5 1.5L7 5M3 12l1.5 1.5L7 11M3 18l1.5 1.5L7 17"),
            ),
        ),
        NavGroup(
            "Setup",
            listOf(
                NavEntry("plugins", "Plugins", "Plugins installed in rtok and what they save", "M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4"),
                NavEntry("skills", "Skills", "Skills rtok serves to agents", "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 16l.7 1.8 1.8.7-1.8.7L19 21l-.7-1.8-1.8-.7 1.8-.7z"),
                NavEntry("hosts", "Hosts", "Agent hosts on this machine and their junk", "M3 4h18v7H3zM3 13h18v7H3zM7 7.5h.01M7 16.5h.01"),
                NavEntry("services", "Services", "Background services rtok runs", "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5"),
                NavEntry("config", "Config", "The effective config and where each value comes from", "M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1M15 4v4M9 10v4M17 16v4"),
                NavEntry("doctor", "Doctor", "Health checks and the fixes rtok can apply", "M4 7h16v13H4zM9 7V4h6v3M12 10.5v6M9 13.5h6"),
            ),
        ),
    )

/** The group of the page [id] and its entry, or null for an unknown page. */
fun navFor(id: String?): Pair<NavGroup, NavEntry>? = SIDEBAR.firstNotNullOfOrNull { g -> g.entries.firstOrNull { it.id == id }?.let { g to it } }

const val ICON_BRAND = "M5 7l5 5-5 5M12 18h7"
const val ICON_MOON = "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"
const val ICON_SUN = "M8 12a4 4 0 1 0 8 0a4 4 0 1 0-8 0M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"
