package yamlaot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YamlTest {
    private fun str(s: String) = YamlValue.Str(s)

    private fun map(vararg kv: Pair<String, YamlValue>) = YamlValue.Map(linkedMapOf(*kv))

    private fun seq(vararg v: YamlValue) = YamlValue.Seq(v.toList())

    @Test fun readsAContinueConfig() {
        val doc =
            """
            |# my config
            |name: Local Config   # trailing
            |version: 1.0.0
            |schema: v1
            |models:
            |  - name: gpt
            |    provider: openai
            |    apiBase: http://localhost:8080/v1
            |    roles: [chat, "edit"]
            |mcpServers:
            |- name: other
            |  type: stdio
            |  command: "C:\\tools\\x.exe"
            |  args:
            |    - --flag
            |    - 'it''s'
            |  env: {A: 1, B: "two"}
            |- name: rtok
            |  command: rtok
            |  args: ["mcp", "--host", "continue"]
            |
            """.trimMargin()
        val v = Yaml.parse(doc) as YamlValue.Map
        assertEquals(str("Local Config"), v["name"])
        assertEquals(str("1.0.0"), v["version"])
        val models = v["models"] as YamlValue.Seq
        assertEquals(seq(str("chat"), str("edit")), (models.items[0] as YamlValue.Map)["roles"])
        assertEquals(str("http://localhost:8080/v1"), (models.items[0] as YamlValue.Map)["apiBase"])
        val servers = (v["mcpServers"] as YamlValue.Seq).items
        val other = servers[0] as YamlValue.Map
        assertEquals(str("C:\\tools\\x.exe"), other["command"])
        assertEquals(seq(str("--flag"), str("it's")), other["args"])
        assertEquals(map("A" to YamlValue.Int(1), "B" to str("two")), other["env"])
        assertEquals(seq(str("mcp"), str("--host"), str("continue")), (servers[1] as YamlValue.Map)["args"])
    }

    @Test fun readsAGooseConfigWithScalarsOfEveryKind() {
        val doc =
            """
            |---
            |GOOSE_PROVIDER: openai
            |extensions:
            |  developer:
            |    enabled: true
            |    timeout: 300
            |    ratio: 0.5
            |    envs: {}
            |    note: ~
            |    bundled:
            |  rtok:
            |    name: rtok
            |    cmd: /usr/local/bin/rtok
            |    args:
            |    - mcp
            |
            """.trimMargin()
        val ext = (Yaml.parse(doc) as YamlValue.Map)["extensions"] as YamlValue.Map
        val dev = ext["developer"] as YamlValue.Map
        assertEquals(YamlValue.Bool(true), dev["enabled"])
        assertEquals(YamlValue.Int(300), dev["timeout"])
        assertEquals(YamlValue.Float("0.5"), dev["ratio"])
        assertEquals(map(), dev["envs"])
        assertEquals(YamlValue.Null, dev["note"])
        assertEquals(YamlValue.Null, dev["bundled"])
        assertEquals(seq(str("mcp")), (ext["rtok"] as YamlValue.Map)["args"])
    }

    @Test fun blockScalarsFlowOverLinesAndFoldedPlainScalars() {
        val doc = "a: |\n  one\n  # not a comment\n\n  two\nb: >-\n  x\n  y\nc: [1,\n  2]\nd: long\n  text\ne: \"q # r\" # c\n"
        val v = Yaml.parse(doc) as YamlValue.Map
        assertEquals(str("one\n# not a comment\n\ntwo\n"), v["a"])
        assertEquals(str("x y"), v["b"])
        assertEquals(seq(YamlValue.Int(1), YamlValue.Int(2)), v["c"])
        assertEquals(str("long text"), v["d"])
        assertEquals(str("q # r"), v["e"])
        assertEquals(YamlValue.Null, Yaml.parse("# only a comment\n"))
        assertEquals(seq(str("a"), seq(str("b"))), Yaml.parse("- a\n-\n  - b\n"))
    }

    @Test fun unreadableOrUnsupportedDocumentsAreRefused() {
        for (bad in listOf("a: [1, 2\n", "a: 1\na: 2\n", "a: &x 1\nb: *x\n", "a:\n  b: 1\n c: 2\n", "a: \"open\n", "a: 1\n  - b\n")) {
            assertFailsWith<YamlAotException>(bad) { Yaml.parse(bad) }
        }
    }

    @Test fun scalarsAreQuotedOnlyWhenNeeded() {
        assertEquals("rtok", YamlText.scalar("rtok"))
        assertEquals("/usr/local/bin/rtok", YamlText.scalar("/usr/local/bin/rtok"))
        assertEquals("--host", YamlText.scalar("--host"))
        assertEquals("\"C:\\\\Program Files\\\\rtok.exe\"", YamlText.scalar("C:\\Program Files\\rtok.exe"))
        assertEquals("\"true\"", YamlText.scalar("true"))
        assertEquals("\"1.0\"", YamlText.scalar("1.0"))
        assertEquals("\"\"", YamlText.scalar(""))
        for (s in listOf("C:\\Program Files\\rtok.exe", "true", "a: b", "it's")) assertEquals(str(s), Yaml.parse(YamlText.scalar(s)))
    }

    @Test fun blocksAndSplicesKeepEveryOtherByte() {
        val raw = "# head\r\nmcpServers:\r\n- name: a\r\n\r\n# tail note\r\nmodels: []\r\n"
        val lines = YamlText.lines(raw)
        assertEquals(raw, lines.joinToString(""))
        val b = YamlText.block(lines, "mcpServers")!!
        assertEquals(YamlText.Block(1, "", 3, 0), b)
        assertEquals(2, YamlText.lastContent(lines, 2, 5))
        assertEquals("\r\n", YamlText.eolOf(raw))
        val out = YamlText.splice(lines, b.end, b.end, "- name: b\r\n", "\r\n")
        assertEquals("# head\r\nmcpServers:\r\n- name: a\r\n- name: b\r\n\r\n# tail note\r\nmodels: []\r\n", out)
        assertEquals("a: 1\nb: 2\n", YamlText.splice(YamlText.lines("a: 1"), 1, 1, "b: 2\n", "\n"))
        assertEquals(null, YamlText.block(lines, "extensions"))
    }
}
