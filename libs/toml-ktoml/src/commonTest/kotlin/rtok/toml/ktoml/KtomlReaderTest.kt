package rtok.toml.ktoml

import kotlinx.serialization.json.*
import rtok.toml.TomlException
import kotlin.test.*

class KtomlReaderTest {
    private fun p(s: String) = KtomlReader.parse(s)

    @Test fun scalars() {
        val r = p("a = 1\nb = 1.5\nc = true\nd = \"x\"\ne = 'y'\n")
        assertEquals("1", r["a"]!!.jsonPrimitive.content)
        assertEquals(1.5, r["b"]!!.jsonPrimitive.double)
        assertEquals(true, r["c"]!!.jsonPrimitive.boolean)
        assertEquals("x", r["d"]!!.jsonPrimitive.content)
        assertEquals("y", r["e"]!!.jsonPrimitive.content)
    }

    @Test fun nestedTables() {
        val r = p("[a.b]\nx = 1\n[a]\ny = 2\n")
        assertEquals(
            "1",
            r["a"]!!
                .jsonObject["b"]!!
                .jsonObject["x"]!!
                .jsonPrimitive.content,
        )
        assertEquals("2", r["a"]!!.jsonObject["y"]!!.jsonPrimitive.content)
    }

    @Test fun dottedKeys() {
        val r = p("a.b.c = 1\n")
        assertEquals(
            "1",
            r["a"]!!
                .jsonObject["b"]!!
                .jsonObject["c"]!!
                .jsonPrimitive.content,
        )
    }

    @Test fun inlineTable() {
        val r = p("t = { x = 1, y = \"s\" }\n")
        assertEquals("s", r["t"]!!.jsonObject["y"]!!.jsonPrimitive.content)
    }

    @Test fun arrays() {
        val r = p("a = [1, 2]\nb = [[1], [\"x\"]]\nc = [\n  \"m\",\n  \"n\",\n]\n")
        assertEquals(2, r["a"]!!.jsonArray.size)
        assertEquals(2, r["b"]!!.jsonArray.size)
        assertEquals(2, r["c"]!!.jsonArray.size)
    }

    @Test fun arrayOfTables() {
        val r = p("[[g]]\nn = 1\n[[g]]\nn = 2\n")
        assertEquals(2, r["g"]!!.jsonArray.size)
    }

    @Test fun datetimeAsString() {
        val r = p("d = 1979-05-27T07:32:00Z\n")
        assertTrue(r["d"]!!.jsonPrimitive.content.startsWith("1979-05-27"))
    }

    @Test fun multiline() {
        assertEquals("x\ny\n", KtomlReader.parse("a = \"\"\"\nx\ny\n\"\"\"\nb = 1\n")["a"]!!.jsonPrimitive.content)
    }

    @Test fun lines() {
        val d = KtomlReader.read("# c\na = 1\n\n[t]\nk = 2\n")
        assertEquals(2, d.lineOf("a"))
        assertEquals(4, d.lineOf("t"))
        assertEquals(5, d.lineOf("t.k"))
    }

    @Test fun errorHasLine() {
        val e = assertFailsWith<TomlException> { KtomlReader.read("a = 1\nb = = 2\n") }
        println("ERR: " + e.message)
        assertTrue(e.message!!.contains("2"))
    }

    @Test fun duplicateKeyRejected() {
        assertFailsWith<TomlException> { KtomlReader.read("a = 1\na = 2\n") }
    }
}

class KtomlWriterTest {
    @Test fun roundTrip() {
        val src = "# c\ntop = \"s\"\nn = 3\nf = 1.5\nb = true\nlist = [1, 2]\n[t]\nk = \"v\"\n[t.u]\nz = 1\n[[g]]\nn = 1\n[[g]]\nn = 2\n"
        val tree = KtomlReader.parse(src)
        val out = KtomlReader.write(tree)
        println("OUT:\n$out")
        assertEquals(tree, KtomlReader.parse(out))
        assertFalse(out.contains("# c"))
    }

    /** A file `config set` wrote and a user then appended to must still parse (no glued last line). */
    @Test fun writtenDocumentEndsWithANewline() {
        val out =
            KtomlReader.write(
                buildJsonObject {
                    putJsonObject("a") { put("x", 1) }
                    putJsonObject("b") {}
                },
            )
        assertTrue(out.endsWith("\n"), out)
        assertEquals(
            "1",
            KtomlReader
                .parse(out + "c = 1\n")["b"]!!
                .jsonObject["c"]!!
                .jsonPrimitive.content,
        )
    }
}
