// Port of src/agents/task_list.rs tests.
package rtok.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import rtok.support.tmpDir
import rtok.sys.ProgressRunner
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.parent
import rtok.sys.readToString
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CAP = 1L shl 20
private const val ID = "rtok-t1"

class TaskListTest {
    private fun temp(tag: String): String = "${tmpDir("tasklist-$tag")}/${TaskList.FILE}"

    private fun seed(
        path: String,
        text: String,
    ) {
        createDirAll(parent(path)!!)
        writeString(path, text)
    }

    @Test fun aNewFileGetsOnlyOurKey() {
        val path = temp("new")
        TaskList.set(path, ID)
        val want = buildJsonObject { putJsonObject("env") { put(TaskList.KEY, ID) } }
        assertEquals(want, Json.parseToJsonElement(readToString(path)))
    }

    @Test fun everyOtherByteOfAnExistingFileStays() {
        val path = temp("keep")
        seed(path, "{\n  // mine\n  \"permissions\": {\"allow\": [\"Bash(ls)\",],},\n  \"env\": {\n    \"A\": \"1\",\n  },\n}\n")
        TaskList.set(path, ID)
        val after = readToString(path)
        assertTrue("// mine" in after, after)
        assertTrue("\"permissions\": {\"allow\": [\"Bash(ls)\",],}," in after, after)
        assertTrue("\"A\": \"1\"" in after, after)
        assertTrue("\"CLAUDE_CODE_TASK_LIST_ID\": \"rtok-t1\"" in after, after)
        TaskList.set(path, ID)
        assertEquals(after, readToString(path), "idempotent")
    }

    @Test fun nothingIsWrittenWhereGitDoesNotIgnoreTheFile() {
        val repo = tmpDir("tasklist-tracked")
        val file = "$repo/${TaskList.FILE}"

        fun git(vararg args: String) {
            val (c, _) = ProgressRunner("", "git", listOf("-C", repo) + args).quiet().runCapped(CAP)
            check(c.exitCode == 0) { c.stderr.decodeToString() }
        }
        git("init", "-q")
        // A global excludes file ignoring `.claude/` would make the first write happen; the repo's config outranks it.
        writeString("$repo/no-global-excludes", "")
        git("config", "core.excludesFile", "$repo/no-global-excludes")
        TaskList.writeFor(repo, repo, "t1")
        assertFalse(exists(file), file)
        writeString("$repo/.gitignore", ".claude/\n")
        TaskList.writeFor(repo, repo, "t1")
        val env = Json.parseToJsonElement(readToString(file)).jsonObject["env"]!!.jsonObject
        assertTrue(env[TaskList.KEY]!!.jsonPrimitive.content.endsWith("-t1"), "$env")
    }

    @Test fun aMalformedFileIsLeftUntouched() {
        val path = temp("bad")
        seed(path, "{ \"env\": ")
        assertFailsWith<Exception> { TaskList.set(path, ID) }
        assertEquals("{ \"env\": ", readToString(path))
    }

    @Test fun aValueTheUserSetOrAForeignShapeIsKept() {
        for (before in listOf("{\"env\": {\"CLAUDE_CODE_TASK_LIST_ID\": \"mine\"}}", "{\"env\": \"x\"}", "[1]")) {
            val path = temp("kept")
            seed(path, before)
            assertFailsWith<Exception>(before) { TaskList.set(path, ID) }
            assertEquals(before, readToString(path))
        }
    }
}
