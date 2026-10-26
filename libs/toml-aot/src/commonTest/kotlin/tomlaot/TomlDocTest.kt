package tomlaot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TomlDocTest {
    private val sample =
        """
        |# user config
        |model = "k2"
        |
        |[ui]
        |theme = "dark" # keep me
        |
        |# a foreign hook
        |[[hooks]]
        |event = "Stop"
        |command = "notify"
        |args = [
        |  "[not-a-header]",
        |]
        |
        |[[hooks]]
        |event = "PreToolUse"
        |matcher = 'Shell'
        |command = "guard \"x\""
        |timeout = 5
        |
        """.trimMargin()

    @Test fun untouchedDocumentRoundTripsByteForByte() {
        assertEquals(sample, TomlDoc.parse(sample).toString())
        assertEquals("a = 1", TomlDoc.parse("a = 1").toString())
        assertEquals("", TomlDoc.parse("").toString())
    }

    @Test fun readsArrayOfTablesAndScalars() {
        val doc = TomlDoc.parse(sample)
        val hooks = doc.arrayOfTables("hooks")
        assertEquals(2, hooks.size, "a bracket line inside a multi-line array is not a header")
        assertEquals(listOf("event", "command", "args"), hooks[0].keys())
        assertEquals("Shell", hooks[1].str("matcher"))
        assertEquals("guard \"x\"", hooks[1].str("command"))
        assertEquals(5L, hooks[1].int("timeout"))
        assertTrue(hooks[0]["args"] is TomlValue.Raw)
        assertEquals("dark", doc.table("ui")?.str("theme"))
        assertNull(doc.table("hooks"))
    }

    @Test fun pushAppendsAfterTheLastElementKeepingEverythingElse() {
        val doc = TomlDoc.parse(sample)
        doc.push("hooks", listOf("event" to TomlValue.Str("SessionStart"), "timeout" to TomlValue.Int(5)))
        assertEquals(sample + "\n[[hooks]]\nevent = \"SessionStart\"\ntimeout = 5\n", doc.toString())
        assertEquals(3, TomlDoc.parse(doc.toString()).arrayOfTables("hooks").size)
    }

    @Test fun pushIntoAnEmptyDocument() {
        val doc = TomlDoc.parse("")
        doc.push("hooks.hooks", listOf("name" to TomlValue.Str("rtok")))
        assertEquals("[[hooks.hooks]]\nname = \"rtok\"\n", doc.toString())
    }

    @Test fun retainDropsATableWithItsLeadingComment() {
        val doc = TomlDoc.parse(sample)
        assertEquals(1, doc.retain("hooks") { it.str("event") != "Stop" })
        val out = doc.toString()
        assertTrue("# a foreign hook" !in out && "notify" !in out && "guard" in out && "# keep me" in out, out)
        assertEquals(1, doc.retain("hooks") { false })
        assertEquals(Shape.Absent, doc.shape("hooks"))
    }

    @Test fun setReplacesInPlaceKeepingTheCommentOrAppends() {
        val doc = TomlDoc.parse(sample)
        val ui = doc.table("ui")!!
        ui["theme"] = TomlValue.Str("light")
        ui["size"] = TomlValue.Int(2)
        doc.arrayOfTables("hooks")[0]["args"] = TomlValue.Raw("[]")
        val out = doc.toString()
        assertTrue("theme = \"light\" # keep me\nsize = 2\n" in out, out)
        assertTrue("args = []\n\n[[hooks]]" in out, out)
    }

    @Test fun ensureTableGoesBeforeItsFirstChildTable() {
        val doc = TomlDoc.parse("x = 1\n\n[[hooks.hooks]]\nname = \"a\"\n")
        doc.ensureTable("hooks")["enabled"] = TomlValue.Bool(true)
        assertEquals("x = 1\n\n[hooks]\nenabled = true\n\n[[hooks.hooks]]\nname = \"a\"\n", doc.toString())
        assertEquals(true, (TomlDoc.parse(doc.toString()).table("hooks")!!["enabled"] as TomlValue.Bool).value)
    }

    @Test fun shapesAndConflicts() {
        val doc = TomlDoc.parse("hooks = []\n[a]\nb = 1\n[a.c]\n[[d]]\n")
        assertEquals(Shape.Value, doc.shape("hooks"))
        assertEquals(Shape.Table, doc.shape("a"))
        assertEquals(Shape.Value, doc.shape("a.b"))
        assertEquals(Shape.Table, doc.shape("a.c"))
        assertEquals(Shape.ArrayOfTables, doc.shape("d"))
        assertFailsWith<TomlAotException> { doc.push("hooks", emptyList()) }
        assertFailsWith<TomlAotException> { doc.push("a", emptyList()) }
        assertFailsWith<TomlAotException> { doc.ensureTable("d") }
    }

    @Test fun stringsQuoteAndUnquote() {
        val s = "a \"b\" \\ c\n\t\u0001"
        assertEquals(TomlValue.Str(s), TomlValue.parse(TomlValue.quote(s)))
        assertEquals(TomlValue.Str("C:\\x"), TomlValue.parse("'C:\\x'"))
        assertEquals(TomlValue.Int(1000), TomlValue.parse("1_000"))
        assertEquals(TomlValue.Raw("1.5"), TomlValue.parse("1.5"))
        assertEquals(TomlValue.Str("é"), TomlValue.parse("\"\\u00e9\""))
    }

    @Test fun quotedKeysAndHeaders() {
        val doc = TomlDoc.parse("[[ \"hooks\" ]]\n\"odd key\" = 'v'\n")
        assertEquals("v", doc.arrayOfTables("hooks").single().str("odd key"))
        doc.arrayOfTables("hooks").single()["new key"] = TomlValue.Bool(false)
        assertTrue("\"new key\" = false" in doc.toString())
    }

    @Test fun removeTableTakesItsSubtreeAndDecorAndLeavesTheRest() {
        val doc = TomlDoc.parse("a = 1\n\n# ours\n[x.rtok]\ncommand = \"rtok\"\n\n[x.rtok.tools.read]\nmode = 1\n\n[x.other]\ncommand = \"o\"\n")
        assertTrue(doc.removeTable("x.rtok"))
        assertEquals("a = 1\n\n[x.other]\ncommand = \"o\"\n", doc.toString())
        assertEquals(false, doc.removeTable("x.rtok"))
        assertEquals(false, doc.removeTable("x"))
    }

    @Test fun rootKeysCarryAReplaceableCommentAndCanBeRemoved() {
        val doc = TomlDoc.parse("# head\nprovider = \"openai\" # old\nmodel = \"o3\"\n\n[x]\na = 1\n")
        val root = doc.root
        assertEquals("# old", root.comment("provider"))
        root.set("provider", TomlValue.Str("rtok"), "# was \"openai\"")
        assertEquals("# was \"openai\"", root.comment("provider"))
        root.set("provider", TomlValue.Str("openai"), null)
        assertNull(root.comment("provider"))
        assertTrue(root.remove("model"))
        assertEquals(false, root.remove("model"))
        assertEquals("# head\nprovider = \"openai\"\n\n[x]\na = 1\n", doc.toString())
        val empty = TomlDoc.parse("")
        empty.root.set("k", TomlValue.Str("v"), "# c")
        assertEquals("k = \"v\" # c", empty.toString())
    }
}
