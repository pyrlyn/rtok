// Port of src/tui/drill.rs (T561): the Graph tab's one-project view, the terminal counterpart of the web page's
// drill-down. It draws nothing of its own: the nodes and the count the cap left out are Drill.run's answer at the
// page's own cap. The loop has no worker yet, so the load runs on the key, as Rust's foreground path does.
package rtok.tui

import rtok.web.DrawnNode

/** What the one-project view draws: the nodes within the cap and how many it left out. */
data class Drawn(
    val nodes: List<DrawnNode> = emptyList(),
    val more: Int = 0,
)

class TuiDrill {
    /** The registry id and name of the project the tab is drilled into. */
    private var open: Pair<Int, String>? = null

    var drawn = Drawn()
        private set

    /** The line under the registry table: what the view holds, or why it holds nothing. */
    var note = ""
        private set

    val project: Int? get() = open?.first

    /** Opens the view on [id], or closes it when [id] is already the one drilled into. */
    fun toggle(
        id: Int,
        name: String,
        load: (Int) -> Drawn,
    ) {
        val leaving = project == id
        open = null
        drawn = Drawn()
        note = ""
        if (leaving) return
        open = id to name
        runCatching { load(id) }.fold(
            {
                drawn = it
                note = "$name: ${it.nodes.size} nodes drawn, +${it.more} more"
            },
            { note = "$name: ${it.message ?: it}" },
        )
    }
}
