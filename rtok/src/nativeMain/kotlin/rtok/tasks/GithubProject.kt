// Port of src/tasks/github_project.rs: Projects v2 `Status` for the `github` adapter. With `[tasks.github] project`
// set, each task's issue joins that project and its `Status` single-select follows the task status. The issue stays
// the source of truth: every failure here is a warning, never an error.
package rtok.tasks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.store.TaskId

/** One query for both owner kinds: a project belongs to a user or an organization. */
private const val RESOLVE =
    "query(\$owner:String!,\$number:Int!){repositoryOwner(login:\$owner){" +
        "... on User{projectV2(number:\$number){...P}} ... on Organization{projectV2(number:\$number){...P}}}}" +
        "fragment P on ProjectV2{id field(name:\"Status\"){... on ProjectV2SingleSelectField{id options{id name}}}}"

/** Returns the existing item when the issue is already in the project, so one call adds or finds. */
private const val ADD =
    "mutation(\$project:ID!,\$content:ID!){addProjectV2ItemById(input:{projectId:\$project,contentId:\$content}){item{id}}}"

private const val SET =
    "mutation(\$project:ID!,\$item:ID!,\$field:ID!,\$option:String!){updateProjectV2ItemFieldValue(input:{projectId:\$project," +
        "itemId:\$item,fieldId:\$field,value:{singleSelectOptionId:\$option}}){projectV2Item{id}}}"

/** The option names a status may have, the template default first. `Closed` shares `Done` (no "won't do" column). */
internal fun optionNames(status: Status): List<String> =
    when (status) {
        Status.Open -> listOf("Todo", "To do")
        Status.InProgress -> listOf("In Progress")
        Status.Done, Status.Closed -> listOf("Done")
    }

class GithubProject(
    repo: String,
    private val number: Long,
) {
    private class Resolved(
        val project: String,
        val field: String,
        val options: List<Pair<String, String>>,
    )

    private val owner = repo.substringBefore('/')

    /** Only a success is kept: a network blip must not switch the project off for a long-lived MCP session. */
    private var resolved: Resolved? = null

    /** Put the issue behind [nodeId] in the project with the Status [status] maps to. */
    internal fun sync(
        http: RemoteHttp,
        id: TaskId,
        nodeId: String?,
        status: Status,
    ) {
        try {
            trySync(http, nodeId, status)
        } catch (e: TaskException) {
            taskWarningSink("github tasks: project #$number: Status of $id not updated, the issue is: ${e.message}")
        }
    }

    private fun trySync(
        http: RemoteHttp,
        nodeId: String?,
        status: Status,
    ) {
        val node = nodeId ?: bail("the issue came back without a node_id")
        val r = resolve(http)
        val names = optionNames(status)
        val option =
            r.options.firstOrNull { (_, name) -> names.any { it.equals(name, ignoreCase = true) } }
                ?: bail("the Status field has no \"${names[0]}\" option")
        val added =
            graphql(
                http,
                ADD,
                buildJsonObject {
                    put("project", r.project)
                    put("content", node)
                },
            )
        val item = strAt(added, "addProjectV2ItemById", "item", "id")
        graphql(
            http,
            SET,
            buildJsonObject {
                put("project", r.project)
                put("item", item)
                put("field", r.field)
                put("option", option.first)
            },
        )
    }

    private fun resolve(http: RemoteHttp): Resolved {
        resolved?.let { return it }
        val data =
            graphql(
                http,
                RESOLVE,
                buildJsonObject {
                    put("owner", owner)
                    put("number", number)
                },
            )
        val project =
            ((data["repositoryOwner"] as? JsonObject)?.get("projectV2") as? JsonObject)
                ?: bail("$owner has no project #$number")
        val field = (project["field"] as? JsonObject)?.takeIf { it["id"] != null } ?: bail("the project has no single-select field named Status")
        val options =
            (field["options"] as? JsonArray)?.map {
                val o = it as? JsonObject ?: bail("the Status field has no options")
                strAt(o, "id") to strAt(o, "name")
            } ?: bail("the Status field has no options")
        return Resolved(strAt(project, "id"), strAt(field, "id"), options).also { resolved = it }
    }
}

private fun strAt(
    v: JsonElement,
    vararg path: String,
): String {
    var cur: JsonElement? = v
    for (p in path) cur = (cur as? JsonObject)?.get(p)
    return (cur as? JsonPrimitive)?.takeIf { it.isString && cur !is JsonNull }?.content ?: bail("GraphQL answer lacks /${path.joinToString("/")}")
}
