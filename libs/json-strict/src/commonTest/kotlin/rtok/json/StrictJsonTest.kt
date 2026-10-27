package rtok.json

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrictJsonTest {
    @Test
    fun acceptsWhatSerdeAccepts() {
        for (ok in listOf("{}", "[]", "\"x\"", "true", "false", "null", "0", "-0", "1.5", "-12e3", "1E+2", " {\"a\":[1,null,\"b\"]} ")) {
            assertTrue(StrictJson.parseOrNull(ok) != null, ok)
        }
        assertEquals(JsonNull, StrictJson.parse("null"))
        assertEquals(JsonPrimitive("garbage"), StrictJson.parse("\"garbage\""))
    }

    @Test
    fun rejectsGarbage() {
        val bad =
            listOf(
                "garbage",
                "{\"a\":garbage}",
                "[1,nope]",
                "NaN",
                "Infinity",
                "-Infinity",
                "01",
                "+1",
                ".5",
                "1.",
                "1e",
                "TRUE",
                "Null",
                "{a:1}",
                "[1,]",
                "{\"a\":1,}",
                "// c\n{}",
                "{} x",
                "",
                "  ",
                "\"open",
                "{'a':1}",
            )
        for (b in bad) assertNull(StrictJson.parseOrNull(b), b)
        assertFailsWith<SerializationException> { StrictJson.parse("{\"k\":word}") }
    }
}
