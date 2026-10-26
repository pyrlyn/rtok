package rtok.plugins.graph

import kotlin.test.*
import kotlinx.serialization.json.*
import rtok.plugin.sdk.Ctx
import rtok.support.tmpDir
import rtok.support.withRuntime
import rtok.sys.*

class DispatchTest {
    private fun at(tag: String, body: (Ctx, List<Member>) -> Unit) = withRuntime(tag) { rt ->
        val dir = canonicalize(tmpDir(tag))!!
        try {
            writeString("$dir/a.rs", "fn alpha() { beta(); }\nfn beta() {}\nstruct Gamma;\n")
            body(Ctx(rt), listOf(Member("", dir)))
        } finally { removeDirAll(dir) }
    }
    private fun args(vararg kv: Pair<String, Any>) = buildJsonObject {
        for ((k, v) in kv) when (v) { is String -> put(k, v); is Int -> put(k, v); is Boolean -> put(k, v); is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it as String) })) }
    }

    @Test fun eachToolAnswersThroughTheScope() = at("dispatch-tools") { cx, scope ->
        assertTrue(Dispatch.call(cx, "symbol", args("name" to "alpha"), scope).contains("fn alpha()"))
        assertTrue(Dispatch.call(cx, "symbol", args("names" to listOf("alpha", "beta")), scope).contains("fn beta()"))
        assertTrue(Dispatch.call(cx, "callers", args("name" to "beta"), scope).contains("alpha"))
        assertTrue(Dispatch.call(cx, "impact", args("name" to "beta"), scope).contains("alpha"))
        assertTrue(Dispatch.call(cx, "explore", args("query" to "alpha"), scope).contains("= alpha"))
    }

    @Test fun outlineNarrowsToOneKind() = at("dispatch-outline") { cx, scope ->
        val structs = Dispatch.call(cx, "outline", args("path" to "a.rs", "kind" to "struct"), scope)
        assertTrue(structs.contains("Gamma") && !structs.contains("alpha"), structs)
        assertEquals("no enum in a.rs", Dispatch.call(cx, "outline", args("path" to "a.rs", "kind" to "enum"), scope))
    }

    @Test fun badCallsThrow() = at("dispatch-bad") { cx, scope ->
        assertEquals("missing `name` or `id`", assertFails { Dispatch.call(cx, "symbol", args(), scope) }.message)
        assertEquals("unknown tool: nope", assertFails { Dispatch.call(cx, "nope", args(), scope) }.message)
    }

    @Test fun callersWithoutANameListsDeadCode() = at("dispatch-dead") { cx, scope ->
        assertEquals(Scope.deadFiltered(cx, scope, Filter.None), Dispatch.call(cx, "callers", args(), scope))
    }
}
