@file:Suppress("MagicNumber")

// Port of tests/task_cli.rs (T441.5): `rtok task …` end to end on the disk adapter, in a throwaway checkout with its
// own home, CLI and MCP (`rtok mcp --call`) halves.

package rtok.e2e

import db.Db
import db.room.RoomDbDriver
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import rtok.store.Store
import rtok.store.hostId
import rtok.store.registerAgent
import rtok.sys.*

private class TaskBox(
    tag: String,
) : AutoCloseable {
    val sb = Sandbox("task-cli-$tag")
    val repo = "${sb.root}/repo"

    init {
        // A checkout as rtok reads it, lexically: no git process needed.
        createDirAll("$repo/.git")
        writeString("$repo/.git/config", "[remote \"origin\"]\n\turl = https://github.com/me/tasks-demo.git\n")
    }

    fun task(
        vararg args: String,
        agent: String? = null,
    ): Proc = sh(listOf(RTOK, "task") + args, repo, mapOf("RTOK_HOME" to sb.home, "HOME" to sb.user, "RTOK_CONFIG" to null, "NO_COLOR" to "1", "RTOK_AGENT_ID" to agent), null)

    /** The MCP tool [name] through `rtok mcp --call`: (exited 0, the tool's text). */
    fun mcp(
        name: String,
        args: String,
    ): Pair<Boolean, String> {
        val p = sh(listOf(RTOK, "mcp", "--call", name, "--json", args), repo, mapOf("RTOK_HOME" to sb.home, "HOME" to sb.user, "RTOK_CONFIG" to null, "NO_COLOR" to "1", "RTOK_AGENT_ID" to null), null)
        return (p.code == 0) to p.out
    }

    /** The sandbox store, wherever the config puts it under the sandbox's homes. */
    fun db(): String = listOf("${sb.home}/rtok.db", "${sb.user}/.rtok/rtok.db").firstOrNull(::exists) ?: error("no store yet")

    fun ok(vararg args: String): String {
        val p = task(*args)
        check(p.code == 0) { "rtok task ${args.joinToString(" ")} failed: ${p.err}" }
        return p.out
    }

    fun file(rel: String) = "$repo/$rel"

    /** Two agents registered in the sandbox store (after `init` created it). */
    fun agents(vararg sessions: String): List<String> {
        Db.installIfAbsent(RoomDbDriver)
        return Store.open(db()).use { s -> sessions.map { s.registerAgent(s.hostId("claude")!!, it, null, null, null) } }
    }

    override fun close() = sb.close()
}

class TaskE2eSpec :
    FunSpec({
        test("a plan from init to done") {
            TaskBox("plan").use { b ->
                b.ok("init", "--prefix", "td") shouldContain "adapter disk, next id TD1"
                readToString(b.file(".rtok.toml")) shouldContain "prefix = \"TD\""
                b.ok("create", "Parent", "-d", "Why it exists.") shouldBe "TD1\n"
                b.ok("create", "Child", "--parent", "td1") shouldBe "TD1.1\n"
                b.ok("create", "Other") shouldBe "TD2\n"
                isFile(b.file("tasks/todo/TD1-parent.md")) shouldBe true
                b.ok("list") shouldBe "TD1      todo         Parent\n  TD1.1  todo         Child\nTD2      todo         Other\n"
                b.ok("next") shouldBe "TD1.1  Child\n"
                b.ok("show", "TD1") shouldContain "subtasks: TD1.1\n\nWhy it exists.\n"
                val refused = b.task("status", "TD1", "done")
                refused.code shouldBe 1
                refused.err shouldContain "TD1.1"
                b.ok("status", "TD1.1", "done") shouldBe "TD1.1: done\n"
                b.ok("status", "TD1", "in_progress") shouldBe "TD1: in-progress\n"
                b.ok("status", "TD1", "done") shouldBe "TD1: done\n"
                b.ok("status", "TD1") shouldBe "TD1: done\n"
                isFile(b.file("tasks/done/TD1-parent.md")) shouldBe true
                b.ok("list") shouldBe "TD2  todo         Other\n"
                Json.parseToJsonElement(b.ok("list", "--all", "--json")).jsonArray.size shouldBe 3
                val done = Json.parseToJsonElement(b.ok("list", "--status", "done", "--json")).jsonArray
                done[0].jsonObject["id"] shouldBe JsonPrimitive("TD1")
                done[1].jsonObject["parent"] shouldBe JsonPrimitive("TD1")
                // The counter lives in the store, not in the files: a task removed by hand keeps its id.
                removeFile(b.file("tasks/todo/TD2-other.md"))
                b.ok("create", "Third") shouldBe "TD3\n"
                val shown = Json.parseToJsonElement(b.ok("show", "TD3", "--json")).jsonObject
                shown["status"] shouldBe JsonPrimitive("todo")
                shown["subtasks"] shouldBe JsonArray(emptyList())
            }
        }

        test("board shows the four columns and migrate turns legacy files into a board") {
            TaskBox("board").use { b ->
                b.ok("init", "--prefix", "B")
                for (title in listOf("First", "Second", "Third")) b.ok("create", title)
                b.ok("status", "B2", "in-progress")
                b.ok("status", "B3", "done", "--report", "Shipped.")
                b.ok("board") shouldBe "todo (1)\n  B1  First\n\nin-progress (1)\n  B2  Second\n\ndone (1)\n  B3  Third\n\nclosed (0)\n"
                val json = Json.parseToJsonElement(b.ok("board", "--json")).jsonObject
                json["kind"] shouldBe JsonPrimitive("board")
                json["board"]!!.jsonObject["layout"] shouldBe JsonPrimitive("board")
                json["remote"] shouldBe JsonNull
                val columns = json["columns"]!!.jsonArray.map { it.jsonObject }
                columns.map { it["status"] } shouldBe listOf("todo", "in-progress", "done", "closed").map(::JsonPrimitive)
                columns[2]["tasks"]!!.jsonArray[0].jsonObject["report"] shouldBe JsonPrimitive("Shipped.")
                readToString(b.file("tasks/.gitignore")) shouldBe ".lock\n.sync/\n"

                val legacy = TaskBox("migrate")
                legacy.use { l ->
                    writeString(l.file(".rtok.toml"), "[tasks]\nprefix = \"L\"\n\n[tasks.disk]\nlayout = \"files\"\n")
                    createDirAll(l.file("tasks/done"))
                    writeString(l.file("tasks/L1 - old.md"), "---\nid: L1\ntitle: Old\nstatus: open\ncreated_at: 1000\nupdated_at: 2000\n---\n\nBody\n")
                    writeString(l.file("tasks/done/L2 - fin.md"), "---\nid: L2\ntitle: Fin\nstatus: closed\ncreated_at: 1000\nupdated_at: 2000\n---\n")
                    val before = l.task("list")
                    before.code shouldBe 0
                    before.err shouldContain "rtok task migrate"
                    val dry = l.ok("migrate", "--dry-run")
                    dry shouldContain "L1: tasks/L1 - old.md -> tasks/todo/L1-old.md\n"
                    dry shouldContain "L2: tasks/done/L2 - fin.md -> tasks/closed/L2-fin.md\n"
                    isFile(l.file("tasks/L1 - old.md")) shouldBe true
                    val dryJson = Json.parseToJsonElement(l.ok("migrate", "--dry-run", "--json")).jsonObject
                    dryJson["dry_run"] shouldBe JsonPrimitive(true)
                    dryJson["moved"]!!.jsonArray.size shouldBe 2
                    l.ok("migrate") shouldContain "moved 2 tasks"
                    isFile(l.file("tasks/todo/L1-old.md")) shouldBe true
                    isFile(l.file("tasks/closed/L2-fin.md")) shouldBe true
                    readToString(l.file(".rtok.toml")) shouldNotContain "tasks.disk"
                    l.ok("migrate") shouldBe "nothing to migrate\n"
                    val after = l.task("list")
                    after.err shouldBe ""
                    after.out shouldContain "L1"
                }
            }
        }

        test("refusals name the problem") {
            TaskBox("refuse").use { b ->
                for ((args, want) in listOf(
                    listOf("show", "X9") to "no task X9",
                    listOf("create", "Sub", "--parent", "T7") to "no parent task T7",
                    listOf("status", "T1", "blocked") to "unknown task status",
                    listOf("init", "--adapter", "jira") to "--adapter must be one of",
                )) {
                    val p = b.task(*args.toTypedArray())
                    (p.code != 0) shouldBe true
                    p.err shouldContain want
                }
                b.ok("next") shouldBe "no ready task\n"
                b.ok("next", "--json") shouldBe "null\n"
            }
        }

        test("export mirrors the disk adapter only and names the backends") {
            TaskBox("export").use { b ->
                // A github-adapter checkout is refused before any token lookup or request.
                writeString(b.file(".rtok.toml"), "[tasks]\nadapter = \"github\"\nprefix = \"E\"\n")
                val refused = b.task("export", "github", "--dry-run")
                refused.code shouldBe 1
                refused.err shouldContain "mirrors the disk adapter"
                val unknown = b.task("export", "jira")
                unknown.code shouldBe 2
                unknown.err shouldContain "invalid value"
                // A disk checkout without a Linear team stops before the key lookup.
                writeString(b.file(".rtok.toml"), "[tasks]\nadapter = \"disk\"\nprefix = \"E\"\n")
                b.task("export", "linear", "--dry-run").err shouldContain "[tasks.export.linear] team"
            }
        }

        test("sync raises a lost counter and reports the drift") {
            TaskBox("sync").use { b ->
                b.ok("init", "--prefix", "S")
                b.ok("create", "One")
                b.ok("create", "Two")
                b.ok("create", "Leaf", "--parent", "S1")
                val home = b.db().substringBeforeLast('/')
                removeDirAll(home)
                createDirAll(home)
                b.ok("sync") shouldBe "board: next id S3\nraised top level: 0 -> 2\nraised subtasks of S1: 0 -> 1\nabove the counter: S1, S2, S1.1\n"
                val json = Json.parseToJsonElement(b.ok("sync", "--json")).jsonObject
                json["counter"] shouldBe JsonPrimitive(2)
                json["raised"] shouldBe JsonArray(emptyList())
                b.ok("sync") shouldBe "board: next id S3, counters in step\n"
                b.ok("create", "Three") shouldBe "S3\n"
            }
        }

        test("claim, ready and a blocker") {
            TaskBox("claim").use { b ->
                b.ok("init", "--prefix", "C")
                val (agent, other) = b.agents("cli-claim", "cli-other")
                b.ok("create", "First") shouldBe "C1\n"
                b.ok("create", "Second") shouldBe "C2\n"
                b.task("claim", "C1").err shouldContain "no agent to bind"
                b.task("claim", agent = agent).out shouldBe "C1  First\n"
                b.task("claim", "C1", agent = agent).out shouldBe "C1  First  (already yours)\n"
                b.task("dep", "C2", "C1", agent = agent).out shouldBe "C2: blocked by C1\n"
                b.ok("ready") shouldNotContain "C2"
                b.task("claim", "C1", agent = other).err shouldContain "claimed by"
                b.task("release", "C1", agent = agent).out shouldBe "C1: todo\n"
                b.ok("ready") shouldContain "C1"
            }
        }

        test("conflict and parallel markers are symmetric and flag ready") {
            TaskBox("markers").use { b ->
                b.ok("init", "--prefix", "K")
                val (agent) = b.agents("cli-markers")
                for (title in listOf("First", "Second", "Third")) b.ok("create", title)
                b.ok("conflict", "K1", "K2") shouldBe "K1: conflicts with K2\n"
                b.ok("parallel", "K3") shouldBe "K3: parallel\n"
                val list = b.ok("list")
                list shouldContain "First  [conflicts: K2]"
                list shouldContain "Second  [conflicts: K1]"
                list shouldContain "Third  [parallel]"
                b.ok("show", "K2") shouldContain "conflicts with: K1\n"
                b.ok("show", "K3") shouldContain "parallel: yes\n"
                readToString(b.file("tasks/todo/" + listDir(b.file("tasks/todo")).first { it.startsWith("K2-") })) shouldContain "conflicts_with:"
                b.ok("ready") shouldBe "K1  First\nK2  Second\nK3  Third  [parallel]\n"
                b.task("claim", "K1", agent = agent).code shouldBe 0
                b.ok("ready") shouldBe "K2  Second  (conflicts with K1 in progress)\nK3  Third  [parallel]\n"
                b.task("claim", "K2", agent = agent).code shouldBe 0
                // CLI and MCP answer alike; a repeat leaves the task as it is.
                val (ok, out) = b.mcp("task_conflict", """{"id":"K1","other":"K3"}""")
                ok shouldBe true
                val viaMcp = Json.parseToJsonElement(out)
                viaMcp shouldBe Json.parseToJsonElement(b.ok("conflict", "K1", "K3", "--json"))
                viaMcp.jsonObject["conflicts_with"] shouldBe Json.parseToJsonElement("""["K2","K3"]""")
                val (pOk, pOut) = b.mcp("task_parallel", """{"id":"K3","on":false}""")
                (pOk && !pOut.contains("parallel")) shouldBe true
                b.ok("conflict", "K1", "K2", "--remove") shouldBe "K1: no longer conflicts with K2\n"
                b.ok("show", "K2") shouldNotContain "conflicts with"
                b.ok("parallel", "K3", "--off") shouldBe "K3: not parallel\n"
                for (args in listOf(listOf("conflict", "K1", "K1"), listOf("conflict", "K1", "K9"))) {
                    val p = b.task(*args.toTypedArray())
                    (p.code != 0 && p.err.isNotEmpty()) shouldBe true
                }
            }
        }

        test("mcp tools answer like the cli") {
            TaskBox("mcp").use { b ->
                b.ok("init", "--prefix", "M")

                fun json(s: String) = Json.parseToJsonElement(s)

                fun mcp(
                    name: String,
                    args: String,
                ): kotlinx.serialization.json.JsonElement {
                    val (ok, out) = b.mcp(name, args)
                    check(ok) { "$name $args: $out" }
                    return json(out)
                }
                mcp("task_create", """{"title":"Parent","description":"Why."}""").jsonObject["id"] shouldBe JsonPrimitive("M1")
                mcp("task_create", """{"title":"Child","parent":"M1"}""").jsonObject["id"] shouldBe JsonPrimitive("M1.1")
                json(b.ok("create", "Other", "--json")).jsonObject["id"] shouldBe JsonPrimitive("M2")
                mcp("task_list", "{}") shouldBe json(b.ok("list", "--json"))
                mcp("task_get", """{"id":"M1"}""") shouldBe json(b.ok("show", "M1", "--json"))
                mcp("task_next", "{}") shouldBe json(b.ok("next", "--json"))
                val (refusedOk, refused) = b.mcp("task_status", """{"id":"M1","status":"done"}""")
                refusedOk shouldBe false
                refused shouldContain "M1.1"
                mcp("task_status", """{"id":"M1.1","status":"done"}""").jsonObject["status"] shouldBe JsonPrimitive("done")
                mcp("task_status", """{"id":"M1"}""") shouldBe json(b.ok("status", "M1", "--json"))
                mcp("task_list", """{"status":["done"]}""") shouldBe json(b.ok("list", "--status", "done", "--json"))
                val (noIdOk, noId) = b.mcp("task_get", "{}")
                noIdOk shouldBe false
                noId shouldContain "missing `id`"
                val (unknownOk, unknown) = b.mcp("task_get", """{"id":"M9"}""")
                unknownOk shouldBe false
                unknown shouldContain "no task M9"
            }
        }
    })
