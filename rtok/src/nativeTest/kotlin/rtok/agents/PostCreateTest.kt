@file:Suppress("MagicNumber")

package rtok.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rtok.config.Config
import rtok.config.Setup
import rtok.mcp.config.Jsonc
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.parent
import rtok.sys.readToString
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Port of tests/agents_post_create.rs (T289.3): `rtok agents install|uninstall <host> --project`.
@Suppress("TooManyFunctions")
class PostCreateTest {
    private class Project(
        name: String,
    ) {
        val home = join(tempDir(), "rtok-pc-$name-${kotlin.random.Random.nextLong().toULong()}")
        val root = join(home, "proj").also { createDirAll(join(it, ".git")) }

        fun run(
            host: String,
            remove: Boolean,
            dryRun: Boolean = false,
        ) = AgentsCommand.installProject(Config(setup = Setup(dryRun = dryRun)), host, remove, root).out

        fun install(host: String) = run(host, false)

        fun uninstall(host: String) = run(host, true)

        fun write(
            rel: String,
            body: String,
        ) = join(root, rel).let {
            createDirAll(parent(it)!!)
            writeString(it, body)
        }

        fun read(rel: String) = readToString(join(root, rel))

        fun has(rel: String): Boolean = exists(join(root, rel))
    }

    private fun json(raw: String) = Jsonc.parse(raw).jsonObject

    private fun JsonElement.at(vararg keys: String): JsonElement = keys.fold(this) { v, k -> v.jsonObject.getValue(k) }

    @Test fun cursorAddsToTheCommandListsOnlyAndRemovalRestoresTheFile() {
        val p = Project("cursor")
        p.write(".cursor/worktrees.json", CURSOR_FOREIGN)
        val text = p.install("cursor")
        assertTrue(".cursor/worktrees.json: added" in text, text)
        assertTrue("setup-worktree-windows runs a script" in text, text)
        val got = p.read(".cursor/worktrees.json")
        assertTrue(got.startsWith("{\n  // keep this comment\n  \"setup-worktree-unix\": [\"npm ci\", \"echo hi\", \""), got)
        val doc = json(got)
        val unix = doc.getValue("setup-worktree-unix").jsonArray
        assertEquals(3, unix.size)
        assertTrue("worktree adopt" in unix[2].jsonPrimitive.content)
        assertEquals(JsonPrimitive("setup.ps1"), doc["setup-worktree-windows"])
        assertFalse("setup-worktree" in doc)
        assertTrue("no changes" in p.install("cursor"))
        assertEquals(got, p.read(".cursor/worktrees.json"))
        assertTrue("removed" in p.uninstall("cursor"))
        assertEquals(CURSOR_FOREIGN, p.read(".cursor/worktrees.json"))
        assertTrue("no changes" in p.uninstall("cursor"))
    }

    @Test fun cursorCreatesTheGenericKeyAndRemovalDeletesTheFileItMade() {
        val p = Project("cursor-new")
        p.install("cursor")
        assertEquals(1, json(p.read(".cursor/worktrees.json")).getValue("setup-worktree").jsonArray.size)
        assertFalse(p.has(".cursor/.backup_configs"), "project files get no backup folder")
        p.uninstall("cursor")
        assertFalse(p.has(".cursor/worktrees.json"))
    }

    @Test fun cursorWithOnlyAScriptChangesNothingAndSaysHow() {
        val p = Project("cursor-script")
        val body = "{\"setup-worktree\": \"setup.sh\"}\n"
        p.write(".cursor/worktrees.json", body)
        val text = p.install("cursor")
        assertTrue("no changes" in text && "add `" in text, text)
        assertEquals(body, p.read(".cursor/worktrees.json"))
    }

    @Test fun windsurfAndDevinShareOneEntryInTheFileTheHostReads() {
        val p = Project("devin")
        val legacy = "{\n  \"hooks\": {\n    \"pre_run_command\": [{\"command\": \"echo pre\"}]\n  }\n}\n"
        p.write(".windsurf/hooks.json", legacy)
        val text = p.install("windsurf,devin")
        assertTrue(".windsurf/hooks.json: added" in text, text)
        assertFalse(p.has(".devin/hooks.json"), "a new .devin file would switch the legacy hooks off")
        val doc = json(p.read(".windsurf/hooks.json"))
        assertEquals(JsonPrimitive("echo pre"), doc.at("hooks", "pre_run_command").jsonArray[0].at("command"))
        val post = doc.at("hooks", "post_setup_worktree").jsonArray
        assertEquals(1, post.size)
        assertTrue("worktree adopt" in post[0].at("command").jsonPrimitive.content)
        p.uninstall("windsurf,devin")
        assertEquals(legacy, p.read(".windsurf/hooks.json"))
    }

    @Test fun devinJoinsAForeignPostSetupListAndAFreshFileGoesAwayOnRemoval() {
        val p = Project("devin-list")
        val body =
            "{\n  \"hooks\": {\n    \"post_setup_worktree\": [\n      {\"command\": \"bash setup.sh\", \"show_output\": true}\n    ]\n  }\n}\n"
        p.write(".devin/hooks.json", body)
        p.install("devin")
        val list = json(p.read(".devin/hooks.json")).at("hooks", "post_setup_worktree").jsonArray
        assertEquals(2, list.size)
        assertEquals(JsonPrimitive("bash setup.sh"), list[0].at("command"))
        p.uninstall("devin")
        assertEquals(body, p.read(".devin/hooks.json"))

        val fresh = Project("devin-fresh")
        fresh.install("devin")
        assertTrue(json(fresh.read(".devin/hooks.json")).at("hooks", "post_setup_worktree") is JsonArray)
        fresh.uninstall("devin")
        assertFalse(fresh.has(".devin/hooks.json"))
    }

    @Test fun cursorRemovalKeepsAValidFileWhenACommentFollowsOurEntry() {
        val p = Project("cursor-comment")
        p.write(".cursor/worktrees.json", "{\"setup-worktree\": [\"$OURS\" /*keep*/, \"npm ci\"]}\n")
        assertTrue("removed" in p.uninstall("cursor"))
        val got = p.read(".cursor/worktrees.json")
        assertEquals("{\"setup-worktree\": [ /*keep*/ \"npm ci\"]}\n", got)
        assertEquals(JsonArray(listOf(JsonPrimitive("npm ci"))), json(got)["setup-worktree"])
    }

    @Test fun devinRemovalKeepsAValidFileWhenACommentFollowsOurEntry() {
        val p = Project("devin-comment")
        p.write(".devin/hooks.json", "{\"hooks\": {\"post_setup_worktree\": [{\"command\": \"$OURS\"} // keep\n, {\"command\": \"x\"}]}}\n")
        assertTrue("removed" in p.uninstall("devin"))
        val got = p.read(".devin/hooks.json")
        assertTrue("// keep" in got, got)
        assertEquals(
            JsonArray(listOf(JsonObject(mapOf("command" to JsonPrimitive("x"))))),
            json(got).at("hooks", "post_setup_worktree"),
        )
    }

    @Test fun kiloPutsAMarkedBlockAfterTheShebangAndRemovalRestoresTheScript() {
        val p = Project("kilo")
        val script = "#!/bin/bash\nset -e\nnpm ci\n"
        p.write(".kilo/setup-script", script)
        val text = p.install("kilo")
        assertTrue(".kilo/setup-script: added" in text, text)
        val got = p.read(".kilo/setup-script")
        assertTrue(got.startsWith("#!/bin/bash\n# >>> rtok worktree adopt"), got)
        assertTrue(got.endsWith("# <<< rtok worktree adopt <<<\nset -e\nnpm ci\n"), got)
        assertTrue("no changes" in p.install("kilo"))
        assertEquals(got, p.read(".kilo/setup-script"))
        p.uninstall("kilo")
        assertEquals(script, p.read(".kilo/setup-script"))
    }

    @Test fun kiloEditsTheShScriptItWouldOtherwiseShadowAndDeletesAScriptItCreated() {
        val p = Project("kilo-sh")
        p.write(".kilo/setup-script.sh", "npm ci\n")
        p.install("kilo")
        assertFalse(p.has(".kilo/setup-script"), "setup-script would shadow setup-script.sh")
        assertTrue(p.read(".kilo/setup-script.sh").endsWith("# <<< rtok worktree adopt <<<\nnpm ci\n"))
        p.uninstall("kilo")
        assertEquals("npm ci\n", p.read(".kilo/setup-script.sh"))

        val fresh = Project("kilo-new")
        fresh.install("kilo")
        assertTrue(fresh.read(".kilo/setup-script").startsWith("#!/bin/sh\n# >>>"))
        fresh.uninstall("kilo")
        assertFalse(fresh.has(".kilo/setup-script"))
    }

    @Test fun aDryRunWritesNothingAndAnotherHostIsRefused() {
        val p = Project("dry")
        val text = p.run("cursor,kilo,devin", remove = false, dryRun = true)
        assertTrue("would add" in text, text)
        assertTrue(!p.has(".cursor") && !p.has(".kilo") && !p.has(".devin"))
        val err = assertFailsWith<AgentsFailure> { p.install("claude") }
        assertTrue("no post-create script" in err.message.orEmpty(), err.message)
    }

    /** Fail open: a refused `adopt` (no single live agent) must not fail the host's worktree setup. */
    @Test fun theWrittenCommandExitsZeroWhenAdoptFails() {
        val p = Project("fail-open")
        p.install("devin")
        val cmd =
            json(p.read(".devin/hooks.json"))
                .at("hooks", "post_setup_worktree")
                .jsonArray[0]
                .at("command")
                .jsonPrimitive.content
        val fake = join(p.home, "fakebin").also(::createDirAll)
        val marker = join(p.home, "adopt-ran")
        val rtok = join(fake, "rtok")
        writeString(rtok, "#!/bin/sh\necho \"\$@\" > '$marker'\nexit 3\n")
        platform.posix.chmod(rtok, 0x1ED.toUShort())
        assertFalse('\'' in cmd, cmd)
        val status = platform.posix.system("PATH='$fake:/usr/bin:/bin' HOME='${p.home}' sh -c '$cmd'")
        assertEquals(0, status, cmd)
        assertEquals("worktree adopt", readToString(marker).trim())
    }

    private companion object {
        const val CURSOR_FOREIGN =
            "{\n  // keep this comment\n  \"setup-worktree-unix\": [\"npm ci\", \"echo hi\"],\n  \"other\": {\"a\": 1,},\n" +
                "  \"setup-worktree-windows\": \"setup.ps1\"\n}\n"
        const val OURS = "( command -v rtok worktree adopt ) || true"
    }
}
