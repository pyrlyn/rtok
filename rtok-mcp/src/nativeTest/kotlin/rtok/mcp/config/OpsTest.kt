// Port of crates/rtok-mcp/src/ops.rs + config.rs tests, plus format-keeping checks for the JSONC and TOML editors.
package rtok.mcp.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import rtok.agent.sdk.Apply
import rtok.agent.sdk.NO_CHANGES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemFs : McpFs {
    val files = mutableMapOf<String, String>()
    var backups = 0

    override fun read(path: String) = files[path]

    override fun write(
        path: String,
        text: String,
    ) {
        files[path] = text
    }

    override fun backup(path: String) {
        backups++
    }
}

private fun isRtokBin(s: String) = s.substringAfterLast('/').substringBeforeLast(".exe") == "rtok"

private fun spec(
    format: Format,
    keyPath: String,
    shape: EntryShape = EntryShape.TypedCommand,
) = McpSpec("test", Client.Cli, "/config", format, McpSpec.keyPath(keyPath), entryShape = shape)

private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

class OpsTest {
    private val ctx = Apply()

    private data class Case(
        val format: Format,
        val keyPath: String,
        val existing: String,
    )

    private val cases =
        listOf(
            Case(Format.Json, "mcpServers", ""),
            Case(Format.Json, "mcp.servers", ""),
            Case(Format.Jsonc, "mcpServers", "{\n  // keep me\n  \"other\": 1,\n}\n"),
            Case(Format.Toml, "mcp_servers", ""),
            Case(Format.Toml, "mcp.servers", ""),
        )

    @Test fun installThenRemoveRoundTripsAcrossEveryFormat() {
        for (c in cases) {
            val s = spec(c.format, c.keyPath)
            val fs = MemFs()
            if (c.existing.isNotEmpty()) fs.write(s.configPath, c.existing)
            assertIs<Report.Wrote>(apply(s, Mode.Install, ctx, fs, ::isRtokBin), "${c.format}")
            assertEquals(Entry.Present, status(fs, s).entry, "${c.format}")
            assertEquals(1, fs.backups, "${c.format}: install must back up first")
            assertEquals(Report.NoChanges, apply(s, Mode.Install, ctx, fs, ::isRtokBin))
            assertEquals(1, fs.backups, "${c.format}: a no-op write must not back up again")
            if (c.existing.isNotEmpty()) {
                val after = fs.read(s.configPath)!!
                assertTrue("// keep me" in after, after)
                assertTrue("\"other\": 1" in after, after)
            }
            assertIs<Report.Wrote>(apply(s, Mode.Remove, ctx, fs, ::isRtokBin), "${c.format}")
            assertNull(McpConfig.readEntry(fs, s), "${c.format}")
            assertEquals(Report.NoChanges, apply(s, Mode.Remove, ctx, fs, ::isRtokBin))
        }
    }

    @Test fun tomlEntryIsAStandardTableUnderImplicitParents() {
        for ((keyPath, header) in listOf("mcp_servers" to "[mcp_servers.rtok]", "mcp.servers" to "[mcp.servers.rtok]")) {
            val s = spec(Format.Toml, keyPath)
            val fs = MemFs()
            apply(s, Mode.Install, ctx, fs, ::isRtokBin)
            val body = fs.read(s.configPath)!!
            assertTrue(body.startsWith(header), body)
            assertTrue("rtok = {" !in body, body)
        }
    }

    @Test fun removeLeavesAnEntryTheUserEdited() {
        val s = spec(Format.Json, "mcpServers")
        val fs = MemFs()
        apply(s, Mode.Install, ctx, fs, ::isRtokBin)
        fs.write(s.configPath, """{"mcpServers":{"rtok":{"type":"stdio","command":"rtok","args":["mcp","--extra"]}}}""")
        assertIs<Report.Left>(apply(s, Mode.Remove, ctx, fs, ::isRtokBin))
        assertNotNull(McpConfig.readEntry(fs, s))
    }

    @Test fun removeLeavesAnEntryThatIsNotRtoks() {
        val s = spec(Format.Json, "mcpServers")
        val fs = MemFs()
        fs.write(s.configPath, """{"mcpServers":{"rtok":{"command":"other-tool"}}}""")
        assertIs<Report.Left>(apply(s, Mode.Remove, ctx, fs, ::isRtokBin))
        assertNotNull(McpConfig.readEntry(fs, s))
    }

    @Test fun launchSetsTheEntryAndATypeOnlyDifferenceIsNoChange() {
        val s = spec(Format.Json, "mcpServers").copy(host = "claude", launch = Launch("/opt/bin/rtok", hostArg = true))
        val fs = MemFs()
        assertEquals(Report.Wrote("mcpServers.rtok: /opt/bin/rtok mcp --host claude"), apply(s, Mode.Install, ctx, fs, ::isRtokBin))
        assertEquals(
            obj("""{"type": "stdio", "command": "/opt/bin/rtok", "args": ["mcp", "--host", "claude"]}"""),
            McpConfig.readEntry(fs, s),
        )
        val noType = """{"mcpServers":{"rtok":{"command":"/opt/bin/rtok","args":["mcp","--host","claude"]}}}"""
        fs.write(s.configPath, noType)
        for (mode in listOf(Mode.Install, Mode.Update)) assertEquals(NO_CHANGES, apply(s, mode, ctx, fs, ::isRtokBin).line, "$mode")
        assertEquals(noType, fs.read(s.configPath), "file untouched")
    }

    @Test fun everyEntryShapeWritesItsDocumentedKeys() {
        val table =
            listOf(
                EntryShape.TypedCommand to """{"type": "stdio", "command": "rtok", "args": ["mcp"]}""",
                EntryShape.Command to """{"command": "rtok", "args": ["mcp"]}""",
                EntryShape.LocalAllTools to """{"type": "local", "command": "rtok", "args": ["mcp"], "tools": ["*"]}""",
            )
        for ((shape, want) in table) {
            val s = spec(Format.Json, "mcpServers", shape)
            assertEquals(obj(want), s.entry(), "$shape")
            val fs = MemFs()
            for ((mode, wrote) in listOf(Mode.Install to true, Mode.Update to false)) {
                assertEquals(wrote, apply(s, mode, ctx, fs, ::isRtokBin) is Report.Wrote, "$shape $mode")
            }
            assertEquals(obj(want), McpConfig.readEntry(fs, s))
        }
    }

    @Test fun removeFollowsOwnershipAndTheRunFlags() {
        val ours = """{"type":"stdio","command":"/old/bin/rtok","args":["mcp","--host","claude"]}"""
        val edited = """{"command":"rtok","args":["mcp","--extra"]}"""
        val foreign = """{"command":"other-tool"}"""

        data class Row(
            val have: String,
            val yes: Boolean,
            val dryRun: Boolean,
            val want: String,
            val kept: Boolean,
        )
        val table =
            listOf(
                Row(ours, false, false, "- mcpServers.rtok", false),
                Row("""{"command":"rtok","args":["mcp"]}""", false, false, "- mcpServers.rtok", false),
                Row(edited, false, false, "leave mcpServers.rtok in ", true),
                Row(edited, false, true, "? mcpServers.rtok in ", true),
                Row(edited, true, false, "- mcpServers.rtok", false),
                Row(foreign, true, false, "leave mcpServers.rtok in ", true),
            )
        for (r in table) {
            val s = spec(Format.Json, "mcpServers")
            val fs = MemFs()
            fs.write(s.configPath, """{"keep":1,"mcpServers":{"rtok":${r.have}}}""")
            val got = apply(s, Mode.Remove, Apply(yes = r.yes, dryRun = r.dryRun), fs, ::isRtokBin).line
            assertTrue(got.startsWith(r.want), "$r: $got")
            assertEquals(r.kept, McpConfig.readEntry(fs, s) != null, "$r")
        }
    }

    /** T328: a root or servers table of the wrong type is an error that leaves the file untouched. */
    @Test fun strictJsonRefusesANonObjectRootOrTableAndKeepsTheFile() {
        val entry = obj("""{"command": "rtok", "args": ["mcp"]}""")
        for (raw in listOf("[1, 2]", "null", "\"text\"", """{"mcpServers": []}""", """{"mcpServers": null}""", """{"mcpServers": "x"}""")) {
            val fs = MemFs()
            fs.write("/config", raw)
            val s = spec(Format.Json, "mcpServers", EntryShape.Command)
            assertFailsWith<McpConfigException>(raw) { McpConfig.writeEntry(fs, s, entry) }
            runCatching { McpConfig.removeEntry(fs, s) }
            assertEquals(raw, fs.read("/config"), raw)
        }
    }
}

class EditorsTest {
    private val ctx = Apply()

    @Test fun jsoncKeepsCommentsAndNeighboursAcrossUpdateAndRemove() {
        val s = spec(Format.Jsonc, "mcpServers")
        val before = "{\n  // top\n  \"mcpServers\": {\n    \"other\": { \"command\": \"x\" }, // keep\n  },\n  \"z\": 2\n}\n"
        val fs = MemFs()
        fs.write(s.configPath, before)
        apply(s, Mode.Install, ctx, fs, ::isRtokBin)
        val installed = fs.read(s.configPath)!!
        assertTrue("// top" in installed && "// keep" in installed && "\"z\": 2" in installed, installed)
        fs.write(s.configPath, installed.replace("\"mcp\"", "\"mcp\", \"--host\", \"old\""))
        assertIs<Report.Wrote>(apply(s, Mode.Update, ctx, fs, ::isRtokBin))
        assertEquals(Entry.Present, status(fs, s).entry)
        apply(s, Mode.Remove, ctx, fs, ::isRtokBin)
        val removed = fs.read(s.configPath)!!
        assertNull(McpConfig.readEntry(fs, s))
        assertTrue("\"other\"" in removed && "// keep" in removed, removed)
        Jsonc.parse(removed)
    }

    @Test fun jsoncNonObjectTableIsRefused() {
        val fs = MemFs()
        fs.write("/config", "{ \"mcpServers\": [] }")
        assertFailsWith<McpConfigException> { apply(spec(Format.Jsonc, "mcpServers"), Mode.Install, ctx, fs, ::isRtokBin) }
        assertEquals("{ \"mcpServers\": [] }", fs.read("/config"))
    }

    @Test fun tomlKeepsOtherTablesAndCommentsAndReplacesInPlace() {
        val s = spec(Format.Toml, "mcp_servers", EntryShape.Command)
        val before = "# mine\nmodel = \"x\"\n\n[mcp_servers.rtok]\ncommand = \"/old/rtok\"\nargs = [\"mcp\"]\n\n[profiles.a]\nk = 1 # note\n"
        val fs = MemFs()
        fs.write(s.configPath, before)
        assertIs<Report.Wrote>(apply(s, Mode.Install, ctx, fs, ::isRtokBin))
        val after = fs.read(s.configPath)!!
        assertEquals(before.replace("/old/rtok", "rtok"), after)
        apply(s, Mode.Remove, ctx, fs, ::isRtokBin)
        assertEquals("# mine\nmodel = \"x\"\n\n[profiles.a]\nk = 1 # note\n", fs.read(s.configPath))
    }

    @Test fun tomlInlineEntryIsReplacedByATable() {
        val s = spec(Format.Toml, "mcp_servers", EntryShape.Command)
        val fs = MemFs()
        fs.write(s.configPath, "[mcp_servers]\nrtok = { command = \"rtok\", args = [\"mcp\", \"--x\"] }\n")
        apply(s, Mode.Install, ctx, fs, ::isRtokBin)
        assertEquals(Entry.Present, status(fs, s).entry)
        assertTrue("[mcp_servers.rtok]" in fs.read(s.configPath)!!)
    }
}
