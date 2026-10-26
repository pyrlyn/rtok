// Port of agents/continue/mod.rs tests: a fresh valid config, the item appended after the last one with every other
// byte kept, a list at the key's own indent, `mcpServers: []` / a bare header, extra keys not an edit, an edited item
// rewritten on install and left on remove, a foreign `rtok` left, dry-run, CRLF, refused documents, no final newline.
package rtok.agents

import rtok.agent.sdk.NO_CHANGES
import rtok.config.Config
import rtok.sys.createDirAll
import rtok.sys.exists
import rtok.sys.join
import rtok.sys.readToString
import rtok.sys.removeDirAll
import rtok.sys.tempDir
import rtok.sys.writeString
import yamlaot.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ContinueTest {
    private val foreign =
        "# my continue\nname: Mine\nversion: 1.0.0\nschema: v1\nmodels:\n  - name: m\n    provider: ollama\n    model: llama\n" +
            "mcpServers:\n  # tools\n  - name: other\n    command: npx\n    args:\n      - x\n\nrules:\n  - be brief\n"

    private fun scratch(
        name: String,
        dry: Boolean = false,
        body: String? = null,
    ): Pair<Config, String> {
        val dir = join(tempDir(), "rtok-continue-$name-${kotlin.random.Random.nextLong().toULong()}").also(::createDirAll)
        val s = Config().setup
        val setup = s.copy(dryRun = dry, backup = false, `continue` = s.`continue`.copy(configPath = join(dir, "config.yaml")))
        body?.let { writeString(setup.`continue`.configPath, it) }
        return Config().copy(setup = setup) to dir
    }

    private fun Config.text() = readToString(Continue.configPath(this))

    @Test fun aMissingFileGetsAValidConfigAndRemovalDeletesTheBlockAgain() {
        val (c, dir) = scratch("fresh")
        assertTrue(Continue.register(c).startsWith("mcpServers.rtok: "))
        val body = c.text()
        assertTrue(body.startsWith("name: Local Config\nversion: 1.0.0\nschema: v1\nmcpServers:\n  - name: rtok\n") && "args: [mcp, --host, continue]" in body, body)
        Yaml.parse(body)
        assertEquals(listOf("mcp"), Continue.installed(c, Kind.Cli))
        assertEquals(NO_CHANGES, Continue.register(c))
        assertEquals("- mcpServers.rtok", Continue.unregister(c))
        assertEquals("name: Local Config\nversion: 1.0.0\nschema: v1\n", c.text())
        assertEquals(NO_CHANGES, Continue.unregister(c))
        assertEquals(emptyList(), Continue.installed(c, Kind.Cli))
        assertTrue(Agents.agent("continue") === Continue)
        removeDirAll(dir)
    }

    @Test fun installAppendsAfterTheLastItemAndKeepsEveryOtherByte() {
        val (c, dir) = scratch("foreign", body = foreign)
        Continue.register(c)
        assertEquals(foreign.replace("      - x\n\nrules", "      - x\n${Continue.render(2, rtokMcpCommand(), "\n")}\nrules"), c.text())
        assertEquals("- mcpServers.rtok", Continue.unregister(c))
        assertEquals(foreign, c.text())
        removeDirAll(dir)
    }

    @Test fun aListAtTheKeysOwnIndentIsExtendedInPlace() {
        val src = "name: n\nmcpServers:\n- name: other\n  command: x\nrules: []\n"
        val (c, dir) = scratch("flush", body = src)
        Continue.register(c)
        val body = c.text()
        assertTrue(body.startsWith("name: n\nmcpServers:\n- name: other\n  command: x\n- name: rtok\n  type: stdio\n") && body.endsWith("rules: []\n"), body)
        Continue.unregister(c)
        assertEquals(src, c.text())
        removeDirAll(dir)
    }

    @Test fun anEmptyListValueOrHeaderTakesTheItem() {
        for (src in listOf("mcpServers: []\nname: n\n", "mcpServers:\nname: n\n")) {
            val (c, dir) = scratch("empty", body = src)
            Continue.register(c)
            val body = c.text()
            assertTrue(body.startsWith("mcpServers:\n  - name: rtok\n") && body.endsWith("name: n\n"), body)
            Yaml.parse(body)
            Continue.unregister(c)
            assertEquals("name: n\n", c.text())
            removeDirAll(dir)
        }
    }

    @Test fun extraKeysAreNotAnEditButAChangedItemIs() {
        val (c, dir) = scratch("edits")
        Continue.register(c)
        writeString(Continue.configPath(c), c.text() + "    env:\n      A: b\n    cwd: /tmp\n")
        assertEquals(NO_CHANGES, Continue.register(c))
        assertEquals("- mcpServers.rtok", Continue.unregister(c))
        Continue.register(c)
        val edited = c.text().replace("type: stdio", "type: sse")
        writeString(Continue.configPath(c), edited)
        assertTrue(Continue.unregister(c).startsWith("leave mcpServers.rtok"))
        assertEquals(edited, c.text())
        assertTrue(Continue.register(c).startsWith("mcpServers.rtok: "))
        assertTrue("type: stdio" in c.text())
        removeDirAll(dir)
    }

    @Test fun anotherCommandUnderTheRtokNameIsNeverRemoved() {
        val body = "mcpServers:\n  - name: rtok\n    type: stdio\n    command: other\n    args: [x]\n"
        val (c, dir) = scratch("notours", body = body)
        assertTrue(Continue.unregister(c).startsWith("leave mcpServers.rtok"))
        assertEquals(body, c.text())
        removeDirAll(dir)
    }

    @Test fun dryRunCrlfAndNoFinalNewline() {
        val (dry, d1) = scratch("dry", dry = true)
        assertTrue(Continue.register(dry).startsWith("mcpServers.rtok: "))
        assertTrue(!exists(Continue.configPath(dry)))
        val src = "name: n\r\nmcpServers:\r\n  - name: o\r\n    command: x\r\n"
        val (crlf, d2) = scratch("crlf", body = src)
        Continue.register(crlf)
        val body = crlf.text()
        assertEquals(body.count { it == '\n' }, body.split("\r\n").size - 1, body)
        Continue.unregister(crlf)
        assertEquals(src, crlf.text())
        val (nonl, d3) = scratch("nonl", body = "name: n")
        Continue.register(nonl)
        assertTrue(nonl.text().startsWith("name: n\nmcpServers:\n  - name: rtok\n"), nonl.text())
        Yaml.parse(nonl.text())
        listOf(d1, d2, d3).forEach(::removeDirAll)
    }

    @Test fun anUnreadableDocumentOrAWrongMcpServersValueIsAnErrorAndUntouched() {
        for (body in listOf("mcpServers: [\n", "mcpServers:\n  a: b\n", "mcpServers: [{name: o}]\n")) {
            val (c, dir) = scratch("bad", body = body)
            assertFails(body) { Continue.register(c) }
            assertEquals(body, c.text())
            removeDirAll(dir)
        }
    }
}
