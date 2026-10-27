@file:Suppress("MagicNumber", "ReturnCount")

// Port of tests/common/plan_rules.rs: the rules for one `plan/<id>/` folder, run over what the adapter writes.

package rtok.tasks

import rtok.sys.readToString

private val KEYS = listOf("status", "priority", "complexity", "readiness", "agent")
private val ID_RE = Regex("^[A-Z][0-9]+(?:\\.[0-9]+)*[a-z]?$")

private fun frontMatter(text: String): Pair<List<Pair<String, String>>, String>? {
    if (!text.startsWith("---\n")) return null
    val rest = text.removePrefix("---\n")
    val end = rest.indexOf("\n---\n").takeIf { it >= 0 } ?: return null
    val pairs = rest.substring(0, end).split("\n").map { l -> l.substringBefore(':').trim() to (if (':' in l) l.substringAfter(':').trim() else "") }
    return pairs to rest.substring(end + 5)
}

fun activeTaskErrors(
    id: String,
    text: String,
): List<String> {
    val (pairs, body) = frontMatter(text) ?: return listOf("$id: active task without front matter")
    val keys = pairs.map { it.first }
    if (keys != KEYS) return listOf("$id: front matter keys $keys, want $KEYS")
    val v = { k: String -> pairs.first { it.first == k }.second }
    val errs = mutableListOf<String>()
    val status = v("status")
    if (status !in setOf("todo", "in progress")) errs += "$id: status $status, want todo or in progress"
    if (v("priority") !in setOf("P0", "P1", "P2", "P3")) errs += "$id: priority ${v("priority")}, want P0..P3"
    if (v("complexity") !in setOf("1", "2", "3", "4", "5")) errs += "$id: complexity ${v("complexity")}, want 1..5"
    val r = v("readiness")
    if (!(r.endsWith("%") && (r.removeSuffix("%").toIntOrNull() ?: -1) in 0..100)) errs += "$id: readiness $r, want 0%..100%"
    val agent = v("agent")
    if ((status == "todo") != agent.isEmpty()) errs += "$id: agent $agent with status $status"
    val checks = body.split("\n").count { it.startsWith("Check:") }
    if (checks != 1) errs += "$id: $checks Check: lines, want 1"
    if ("haiku" in agent.lowercase() && "src/" in body) errs += "$id: low-cost model on a task touching src/"
    return errs
}

fun folderErrors(path: String): List<String> {
    val id = path.substringAfterLast('/')
    if (!ID_RE.matches(id)) return listOf("$id: folder name is not a task id")
    val task = runCatching { readToString("$path/task.md") }.getOrNull() ?: return listOf("$id: no task.md")
    val errs = mutableListOf<String>()
    val heading = "# $id. "
    if (task.split("\n").none { it.startsWith(heading) }) errs += "$id: task.md has no `${heading}Title` heading"
    val done = runCatching { readToString("$path/done.md") }.getOrNull()
    when {
        done == null -> errs += activeTaskErrors(id, task)
        done.isBlank() -> errs += "$id: empty done.md"
    }
    return errs
}
