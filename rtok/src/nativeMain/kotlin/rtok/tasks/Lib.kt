// Conversions between rtok's task types, which keep the CLI, MCP and web JSON shapes, and kotlin-taskboard's.
package rtok.tasks

import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import pyrlyn.taskboard.BoardError
import pyrlyn.taskboard.BoardException
import rtok.store.TaskId
import kotlin.time.Instant
import pyrlyn.taskboard.Status as LibStatus
import pyrlyn.taskboard.Task as LibTask
import pyrlyn.taskboard.TaskId as LibId

/** A remote adapter's issue rides in the library task's unknown keys: the library only knows a URL. */
private const val EXTERNAL = "external"

internal fun TaskId.lib(): LibId = LibId.parse(toString())

internal fun LibId.rtok(): TaskId = TaskId.parse(toString())

internal fun Status.lib(): LibStatus = LibStatus.parse(str)

internal fun LibStatus.rtok(): Status = Status.parse(word)

internal fun LibTask.rtok(): Task =
    Task(
        id = id.rtok(),
        title = title,
        description = description,
        status = status.rtok(),
        parent = parent?.rtok(),
        createdAt = created.epochSeconds,
        updatedAt = updated.epochSeconds,
        external = extra[EXTERNAL]?.let { runCatching { TASK_JSON.decodeFromJsonElement<ExternalRef>(it) }.getOrNull() },
        priority = priority,
        assignee = assignee,
        blockedBy = blockedBy.map { it.rtok() },
        conflictsWith = conflictsWith.map { it.rtok() },
        parallel = parallel,
        report = report,
    )

internal fun Task.lib(): LibTask =
    LibTask(
        id = id.lib(),
        title = title,
        description = description,
        status = status.lib(),
        priority = priority,
        parent = parent?.lib(),
        assignee = assignee,
        blockedBy = blockedBy.map { it.lib() },
        conflictsWith = conflictsWith.map { it.lib() },
        parallel = parallel,
        report = report,
        created = Instant.fromEpochSeconds(createdAt),
        updated = Instant.fromEpochSeconds(updatedAt),
        extra = external?.let { mapOf(EXTERNAL to TASK_JSON.encodeToJsonElement(it)) }.orEmpty(),
    )

/** Runs a board call; its failures become the errors the CLI, MCP tools and web page already report. */
@Suppress("SwallowedException") // the message is the whole error
internal fun <T> onBoard(body: () -> T): T =
    try {
        body()
    } catch (e: BoardException) {
        when (val error = e.error) {
            is BoardError.Claimed -> throw ClaimConflict.Already(error.id.rtok(), error.holder)
            else -> bail(e.message.orEmpty())
        }
    }
