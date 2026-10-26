// Port of src/agents/mcp.rs tests (has_entry/has_toml_entry, every host surface, commandcode, row sources).
package rtok.agents

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import rtok.config.Config
import rtok.config.Doctor
import rtok.config.HOSTS
import rtok.mcp.config.McpConfig
import rtok.mcp.config.McpFs
import rtok.sys.createDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemFs : McpFs {
    val files = mutableMapOf<String, String>()

    override fun read(path: String) = files[path]

    override fun write(
        path: String,
        text: String,
    ) {
        files[path] = text
    }
}

class McpEntriesTest {
    private fun tmp(
        name: String,
        contents: String,
    ): String {
        val dir = "${tempDir()}/rtok-mcp-surfaces-$name-${kotlin.random.Random.nextLong()}"
        createDirAll(dir)
        val path = "$dir/config.${if (name.startsWith("toml")) "toml" else "json"}"
        writeString(path, contents)
        return path
    }

    @Test fun presentEntryIsTrue() {
        val p = tmp("present", """{"mcpServers": {"rtok": {"command": "rtok", "args": ["mcp"]}}}""")
        assertTrue(McpEntries.hasEntry(p, "mcpServers", "rtok"))
    }

    @Test fun missingKeyNameOrFileIsFalse() {
        val p = tmp("missing", """{"mcpServers": {"other": {"command": "x"}}}""")
        assertFalse(McpEntries.hasEntry(p, "mcpServers", "rtok"))
        assertFalse(McpEntries.hasEntry(p, "otherKey", "rtok"))
        assertFalse(McpEntries.hasEntry("/does/not/exist/rtok-t275.json", "mcpServers", "rtok"))
    }

    @Test fun nameMentionedElsewhereIsNotAnEntry() {
        val p = tmp("elsewhere", "{\n // rtok\n \"notes\": \"rtok lives here too\",\n \"mcpServers\": {\"other\": {\"command\": \"rtok\"}}\n}")
        assertFalse(McpEntries.hasEntry(p, "mcpServers", "rtok"))
    }

    @Test fun invalidFileIsFalse() = assertFalse(McpEntries.hasEntry(tmp("invalid", "{ this is not json"), "mcpServers", "rtok"))

    @Test fun dottedKeyWalksNestedObjects() {
        val p = tmp("dotted", """{"mcp": {"servers": {"rtok": {"command": "rtok", "args": ["mcp"]}}}}""")
        assertTrue(McpEntries.hasEntry(p, "mcp.servers", "rtok"))
        assertFalse(McpEntries.hasEntry(p, "mcp.other", "rtok"))
        assertFalse(McpEntries.hasEntry(tmp("dotted-missing", """{"mcp": {"servers": {}}}"""), "mcp.servers", "rtok"))
    }

    @Test fun tomlPresentEntryIsTrue() {
        val p = tmp("toml-present", "[mcp_servers.rtok]\ncommand = \"rtok\"\nargs = [\"mcp\"]\n")
        assertTrue(McpEntries.hasTomlEntry(p, "mcp_servers", "rtok"))
    }

    @Test fun tomlMissingTableNameOrFileIsFalse() {
        val p = tmp("toml-missing", "[mcp_servers.other]\ncommand = \"x\"\n")
        assertFalse(McpEntries.hasTomlEntry(p, "mcp_servers", "rtok"))
        assertFalse(McpEntries.hasTomlEntry(p, "other_table", "rtok"))
        assertFalse(McpEntries.hasTomlEntry("/does/not/exist/rtok-t275.toml", "mcp_servers", "rtok"))
    }

    @Test fun tomlNameMentionedElsewhereIsNotAnEntry() {
        val p = tmp("toml-elsewhere", "notes = \"rtok lives here too\"\n\n[mcp_servers.other]\ncommand = \"rtok\"\n")
        assertFalse(McpEntries.hasTomlEntry(p, "mcp_servers", "rtok"))
    }

    @Test fun tomlInvalidFileIsFalse() = assertFalse(McpEntries.hasTomlEntry(tmp("toml-invalid", "this is not = [valid toml"), "mcp_servers", "rtok"))
}

private const val MIN_SURFACES = 20

class McpSurfacesTest {
    /** Every path points under a root that does not exist, so no test reads the real home. */
    private fun vfsCfg(): Config {
        val root = "/nonexistent/rtok-t278"
        val c = Config()
        val s = c.setup
        return c.copy(
            doctor = Doctor(claudeJson = "$root/claude.json"),
            setup =
                s.copy(
                    gemini = s.gemini.copy(dir = "$root/gemini"),
                    commandcode = s.commandcode.copy(dir = "$root/commandcode"),
                    grok = s.grok.copy(configPath = "$root/grok/config.toml"),
                    vscode = s.vscode.copy(codeUserDir = "$root/code", insidersUserDir = "$root/insiders"),
                ),
        )
    }

    private fun obj(vararg kv: Pair<String, JsonElement>) = JsonObject(kv.toMap())

    private fun arr(vararg s: String) = buildJsonArray { s.forEach { add(JsonPrimitive(it)) } }

    /** T278: every host × variant surface, each combination of entry and plugin; the line and JSON row follow the file. */
    @Test fun everyHostSurfaceReportsWhatItsFileHolds() {
        val cfg = vfsCfg()
        var seen = 0
        val all = HOSTS.flatMap { id -> Hosts.host(id)!!.variants.flatMap { v -> McpSurfaces.surfaces(id, cfg, v.kind).map { id to it } } }
        for ((id, s0) in all) {
            seen++
            val local = s0.spec.keyPath == listOf("mcp")
            val ours =
                if (local) {
                    obj("type" to JsonPrimitive("local"), "command" to arr("/opt/bin/rtok", "mcp", "--host", id), "enabled" to JsonPrimitive(true))
                } else {
                    obj("type" to JsonPrimitive("stdio"), "command" to JsonPrimitive("/opt/bin/rtok"), "args" to arr("mcp", "--host", id))
                }
            val legacy = obj("command" to JsonPrimitive("rtok"), "args" to arr("mcp"))
            val edited = obj("command" to JsonPrimitive("rtok"), "args" to arr("mcp", "--extra"))
            val name = s0.spec.configPath.substringAfterLast('/')
            val file = McpSurfaces.tilde(s0.spec.configPath)
            val cases =
                listOf(
                    Triple(null, false, EntryState.Missing to "missing ($name has no \"rtok\")"),
                    Triple(ours, false, EntryState.Present to "entry $file"),
                    Triple(legacy, false, EntryState.Present to "entry $file"),
                    Triple(edited, false, EntryState.Stale to "stale (runs `rtok mcp --extra`, want `rtok mcp --host $id`)"),
                    Triple(null, true, EntryState.Missing to "plugin rtok (serves \"rtok\")"),
                    Triple(ours, true, EntryState.Present to "entry $file, plugin rtok (serves \"rtok\")"),
                )
            for ((entry, plugin, want) in cases) {
                val fs = MemFs()
                if (entry != null) McpConfig.writeEntry(fs, s0.spec, entry)
                val s = s0.copy(plugin = if (plugin) "rtok" else null)
                val row = McpSurfaces.status(fs, s)
                val at = "$id ${s.label} ${row.file}"
                assertEquals(want, row.entry to row.text(), at)
                assertEquals(want.first == EntryState.Present || plugin, row.serves(), at)
                val json = row.toJson()
                assertEquals(JsonPrimitive(s.label), json["surface"], at)
                assertEquals(JsonPrimitive(want.first.json), json["entry"], at)
                assertEquals(JsonPrimitive(s.plugin), json["plugin"], at)
            }
        }
        assertTrue(seen >= MIN_SURFACES, "$seen surfaces")
    }

    /** T275.2: Command Code is a host with MCP of its own, so it has a surface. */
    @Test fun commandcodeHasAnMcpSurface() {
        val rows = McpSurfaces.surfaces("commandcode", vfsCfg(), Kind.Cli)
        assertEquals(1, rows.size)
        assertTrue(rows[0].spec.configPath.endsWith("commandcode/mcp.json"))
        assertEquals(listOf("mcpServers"), rows[0].spec.keyPath)
    }

    /** Every host that can write MCP has a surface, except the three with their own parser. */
    @Test fun everyMcpHostHasARowSource() {
        val cfg = vfsCfg()
        val own =
            Hosts.ALL
                .filter { h -> h.variants.any { v -> h.support(v.kind, "mcp") !is Support.No && McpSurfaces.surfaces(h.id, cfg, v.kind).isEmpty() } }
                .map { it.id }
                .sorted()
        assertEquals(listOf("continue", "goose", "reasonix"), own)
    }

    @Test fun grokImportServesWhenNoPlugin() {
        val s = McpSurfaces.surfaces("grok", vfsCfg(), Kind.Cli, grokImport = true).single()
        assertEquals("[compat.claude] import", s.plugin)
        val row = McpSurfaces.status(MemFs(), s)
        assertNull(McpSurfaces.doctorLine("grok", null, row))
    }

    @Test fun doctorLineNamesTheInstallThatWritesIt() {
        val missing = McpRow("cli", "~/.cursor/mcp.json", EntryState.Missing)
        assertEquals("missing: ~/.cursor/mcp.json has no \"rtok\" — rtok side: rtok agents install cursor", McpSurfaces.doctorLine("cursor", null, missing))
        val stale = McpRow("desktop", "~/c.json", EntryState.Stale, diff = "runs `x`, want `rtok mcp --host claude`")
        assertEquals(
            "stale: ~/c.json runs `x`, want `rtok mcp --host claude` — rtok side: rtok agents install claude --desktop",
            McpSurfaces.doctorLine("claude", Kind.Desktop, stale),
        )
        assertNull(McpSurfaces.doctorLine("claude", null, missing.copy(entry = EntryState.Present)))
        // The rtok plugin itself does not excuse a missing entry (D33).
        assertTrue(McpSurfaces.doctorLine("claude", null, missing.copy(plugin = "rtok"))!!.startsWith("missing:"))
    }

    @Test fun unresolvedCommandFlagsMissingAndBareBins() {
        assertEquals("runs `/no/such/rtok`, which does not exist", McpArgv.unresolvedCommand(listOf("/no/such/rtok")) { "rtok" })
        assertEquals(
            "runs bare `rtok`, which a GUI host cannot find without the shell's PATH",
            McpArgv.unresolvedCommand(listOf("rtok")) { "/opt/bin/rtok" },
        )
        assertNull(McpArgv.unresolvedCommand(listOf("rtok")) { "rtok" })
        assertNull(McpArgv.unresolvedCommand(emptyList()) { "/opt/bin/rtok" })
    }

    @Test fun argvOfReadsCommandArgsOrOneArray() {
        assertEquals(
            listOf("rtok", "mcp"),
            McpArgv.argvOf(
                buildJsonObject {
                    put("command", "rtok")
                    put("args", arr("mcp"))
                },
            ),
        )
        assertEquals(listOf("rtok", "mcp"), McpArgv.argvOf(buildJsonObject { put("command", arr("rtok", "mcp")) }))
        assertEquals("~/a/b", McpSurfaces.tilde("/h/a/b", "/h"))
        assertEquals("/x/a", McpSurfaces.tilde("/x/a", "/h"))
    }

    @Test fun resolveMcpCommandPrefersAnAbsolutePathBin() {
        assertEquals("/opt/bin/rtok", resolveMcpCommand("/opt/bin/rtok", "/nonexistent"))
        assertEquals("rtok", resolveMcpCommand("rtok", "/nonexistent"))
        assertTrue(isRtokBin("/x/RTOK.exe"))
        assertFalse(isRtokBin("rtok-extra"))
    }
}
