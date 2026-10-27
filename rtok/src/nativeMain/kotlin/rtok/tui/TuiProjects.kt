// Port of src/tui/projects.rs (T476, T477): the Graph tab's registry keys. Select a project, link or unlink a pair,
// re-index or remove one. The write is the web page's (GraphAnswer.projectWrite over `graph projects`), so the two
// surfaces and the CLI cannot disagree; this is the keys, the target picker and the plan the user confirms. Nothing
// that changes the registry is written before `y`; a re-index changes only the index, so it starts at once.
package rtok.tui

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import rtok.plugins.graph.Job
import rtok.plugins.graph.Jobs
import rtok.plugins.graph.ProjectRow
import rtok.web.ProjectRequest
import tui.Key

private val rowsJson = Json { ignoreUnknownKeys = true }

/** projects.rs `entries`: the snapshot's registry rows, empty when it holds none (or they do not parse). */
fun projectEntries(snapshot: JsonObject): List<ProjectRow> =
    (snapshot["projects"] as? JsonArray)
        ?.let { runCatching { rowsJson.decodeFromJsonElement(ListSerializer(ProjectRow.serializer()), it) }.getOrNull() }
        .orEmpty()

/** What the page did with a key. */
sealed interface ProjectKey {
    data object Ignored : ProjectKey

    data object Handled : ProjectKey

    /** The user confirmed: write this, then report the plan's outcome. */
    data class Write(
        val req: ProjectRequest,
        val plan: String,
    ) : ProjectKey

    /** Open the one-project view on this row, or close it when it is already open. */
    data class Drill(
        val id: Int,
        val name: String,
    ) : ProjectKey

    /** A remove was asked for: fetch its dry run, then show it for `y`. */
    data class Plan(
        val id: Int,
    ) : ProjectKey
}

private enum class Verb(
    val word: String,
) {
    LINK("link"),
    UNLINK("unlink"),
}

private sealed interface Stage {
    data object Idle : Stage

    /** [from] is an id, not a row index: a refresh between keys may reorder the rows. */
    data class Pick(
        val verb: Verb,
        val from: Int,
        val target: Int,
        val both: Boolean,
    ) : Stage

    data class Confirm(
        val req: ProjectRequest,
        val plan: String,
    ) : Stage
}

/** Where a link from [from] can go (not itself, not missing, not linked yet) or, for an unlink, where it goes now. */
private fun candidates(
    verb: Verb,
    all: List<ProjectRow>,
    from: ProjectRow,
): List<Pair<Int, String>> =
    when (verb) {
        Verb.LINK -> all.filter { p -> p.id != from.id && !p.missing && from.links.none { it.to == p.id } }.map { it.id to it.name }
        Verb.UNLINK -> from.links.map { it.to to it.name }
    }

private fun planFor(
    verb: Verb,
    all: List<ProjectRow>,
    from: ProjectRow,
    to: Pair<Int, String>,
    both: Boolean,
): String {
    val arrows = if (both) "${from.name} <-> ${to.second}" else "${from.name} -> ${to.second}"
    val neverIndexed = all.any { it.id == to.first && it.state == "not indexed" }
    // The write indexes a never-indexed target; the plan says so rather than let a link look instant.
    val note = if (verb == Verb.LINK && neverIndexed) "; ${to.second} was never indexed, so it is indexed now" else ""
    return "${verb.word} $arrows$note"
}

class ProjectsState {
    private var cursorAt = 0
    private var stage: Stage = Stage.Idle
    private var status = ""

    /** Projects whose re-index this page started and has not yet reported on. */
    private val watching = mutableListOf<Int>()

    /** The row cursor, clamped to the rows the registry holds. */
    fun cursor(all: List<ProjectRow>): Int = cursorAt.coerceAtMost((all.size - 1).coerceAtLeast(0))

    fun setStatus(s: String) {
        status = s
    }

    /** A re-index of [id] was started: the status line follows it until [settle] sees it end. */
    fun watch(
        id: Int,
        all: List<ProjectRow>,
    ) {
        all.find { it.id == id }?.let {
            status = "indexing ${it.name}…"
            watching += id
        }
    }

    /** The dry run of a remove came back: show it and wait for `y`. */
    fun confirmRemove(
        id: Int,
        dryRun: String,
    ) {
        val plan = dryRun.trim().lines().joinToString("; ")
        stage = Stage.Confirm(ProjectRequest.Remove(id.toString(), true), "$plan; y removes it, any other key declines")
    }

    /** Reports the end of every watched re-index, read from the job tracker rather than the snapshot. */
    fun settle(all: List<ProjectRow>) {
        watching.retainAll { id ->
            val e = all.find { it.id == id } ?: return@retainAll false
            when (val job = Jobs.get(e.root)) {
                Job.Running -> {
                    true
                }

                is Job.Failed -> {
                    status = "index of ${e.name} failed: ${job.error}"
                    false
                }

                null -> {
                    status = "indexed ${e.name}"
                    false
                }
            }
        }
    }

    /** The line under the table: the plan awaiting `y`, the target being picked, or the last outcome. */
    fun note(all: List<ProjectRow>): String =
        when (val s = stage) {
            Stage.Idle -> {
                status
            }

            is Stage.Confirm -> {
                s.plan
            }

            is Stage.Pick -> {
                val f = all.find { it.id == s.from }
                if (f == null) {
                    ""
                } else {
                    val to = candidates(s.verb, all, f).getOrNull(s.target)?.second ?: "-"
                    "${s.verb.word} ${f.name} -> $to${if (s.both) " (both ways)" else ""}"
                }
            }
        }

    /** True while a target is picked or a plan awaits `y`: every key is the picker's then. */
    val busy: Boolean get() = stage != Stage.Idle

    /** One key on the Graph tab. While picking or confirming, a stray `q` or digit must not leave a plan half made. */
    fun key(
        k: Key,
        all: List<ProjectRow>,
    ): ProjectKey {
        val s = stage
        stage = Stage.Idle
        return when (s) {
            is Stage.Confirm -> {
                if (k == Key.Char("y")) {
                    ProjectKey.Write(s.req, s.plan)
                } else {
                    status = "declined; nothing written"
                    ProjectKey.Handled
                }
            }

            is Stage.Pick -> {
                pick(k, s, all)
            }

            Stage.Idle -> {
                idle(k, all)
            }
        }
    }

    private fun pick(
        k: Key,
        s: Stage.Pick,
        all: List<ProjectRow>,
    ): ProjectKey {
        val f = all.find { it.id == s.from }
        if (f == null) status = "that project is gone" else stage = picked(k, s, all, f)
        return ProjectKey.Handled
    }

    /** The picker's next stage: Esc leaves it, Enter turns the target into a plan for `y`. */
    private fun picked(
        k: Key,
        s: Stage.Pick,
        all: List<ProjectRow>,
        f: ProjectRow,
    ): Stage {
        val cands = candidates(s.verb, all, f)
        return when {
            k == Key.Esc -> {
                Stage.Idle
            }

            k == Key.Up -> {
                s.copy(target = (s.target - 1).coerceAtLeast(0))
            }

            k == Key.Down -> {
                s.copy(target = (s.target + 1).coerceAtMost((cands.size - 1).coerceAtLeast(0)))
            }

            k == Key.Char("b") -> {
                s.copy(both = !s.both)
            }

            k == Key.Enter && s.target < cands.size -> {
                val to = cands[s.target]
                val (from, toId) = s.from.toString() to to.first.toString()
                val req = if (s.verb == Verb.LINK) ProjectRequest.Link(from, toId, s.both) else ProjectRequest.Unlink(from, toId, s.both)
                Stage.Confirm(req, planFor(s.verb, all, f, to, s.both))
            }

            else -> {
                s
            }
        }
    }

    private fun idle(
        k: Key,
        all: List<ProjectRow>,
    ): ProjectKey {
        val row = all.getOrNull(cursor(all)) ?: return ProjectKey.Ignored
        return when (k) {
            Key.Up, Key.Down -> {
                cursorAt = (cursor(all) + if (k == Key.Up) -1 else 1).coerceIn(0, all.size - 1)
                status = ""
                ProjectKey.Handled
            }

            Key.Char("s") -> {
                stage = Stage.Confirm(ProjectRequest.Select(row.id.toString()), "select ${row.name}")
                ProjectKey.Handled
            }

            Key.Char("d") -> {
                status = ""
                ProjectKey.Drill(row.id, row.name)
            }

            // A re-index only rewrites the index from files that stay where they are, so it needs no plan.
            Key.Char("i") -> {
                ProjectKey.Write(ProjectRequest.Reindex(row.id.toString()), "re-index ${row.name}")
            }

            Key.Char("x") -> {
                ProjectKey.Plan(row.id)
            }

            Key.Char("l"), Key.Char("u") -> {
                startPick(if (k == Key.Char("l")) Verb.LINK else Verb.UNLINK, row, all)
                ProjectKey.Handled
            }

            else -> {
                ProjectKey.Ignored
            }
        }
    }

    private fun startPick(
        verb: Verb,
        row: ProjectRow,
        all: List<ProjectRow>,
    ) {
        if (candidates(verb, all, row).isEmpty()) {
            status = if (verb == Verb.LINK) "nothing to link ${row.name} to" else "${row.name} has no links"
        } else {
            stage = Stage.Pick(verb, row.id, 0, false)
        }
    }
}
