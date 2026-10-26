package rtok.agents

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsoncTest {
    @Test fun commentsAndTrailingCommasAreAccepted() {
        val raw = "{\n  // line\n  \"a\": \"x // not a comment\", /* block */\n  \"b\": [1, 2,],\n}"
        assertEquals(Json.parseToJsonElement("""{"a":"x // not a comment","b":[1,2]}"""), Jsonc.parse(raw))
    }

    @Test fun invalidInputIsNull() {
        assertNull(Jsonc.parseOrNull("{'a': 1}"))
    }
}
