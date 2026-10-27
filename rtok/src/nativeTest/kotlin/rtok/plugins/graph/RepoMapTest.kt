// Port of the repo-map tests in src/plugins/graph/mod.rs.
@file:Suppress("MagicNumber")

package rtok.plugins.graph

import rtok.plugin.Runtime
import rtok.plugin.sdk.Class
import rtok.plugin.sdk.Ctx
import rtok.plugin.sdk.Injection
import rtok.plugin.sdk.SessionStart
import rtok.plugin.sdk.SymbolRow
import rtok.support.tmpDir
import rtok.support.withRuntime
import kotlin.test.*

class RepoMapTest {
    private fun seedMap(
        rt: Runtime,
        dir: String,
    ) {
        rt.replaceSymbols(
            Index.canon(dir),
            "a.rs",
            "s",
            0L to 0L,
            listOf(
                SymbolRow("hot", "function", 1, true, 1, ""),
                SymbolRow("mid", "function", 2, true, 2, ""),
                SymbolRow("cold", "function", 3, true, 3, ""),
                SymbolRow("hot", "function", 10, false, 10, ""),
                SymbolRow("hot", "function", 11, false, 11, ""),
                SymbolRow("mid", "function", 12, false, 12, ""),
            ),
        )
    }

    private fun start(rt: Runtime): Injection? = GraphPlugin.sessionStart(SessionStart("startup"), Ctx(rt))

    private fun map(
        rt: Runtime,
        tokens: UInt,
        rank: String? = null,
    ) {
        val g = rt.config.plugins.graph
        rt.config = rt.config.copy(plugins = rt.config.plugins.copy(graph = g.copy(mapTokens = tokens, mapRank = rank ?: g.mapRank)))
    }

    @Test fun repoMapOffByDefaultAndEmptyIndex() {
        withRuntime("map-off") { rt ->
            val dir = tmpDir("map-off")
            rt.cwd = dir
            seedMap(rt, dir)
            assertNull(start(rt), "map_tokens=0 must not inject")
        }
        withRuntime("map-empty") { empty ->
            map(empty, 200u)
            empty.cwd = tmpDir("map-empty")
            assertNull(start(empty), "empty index must not inject")
        }
    }

    /** T370: `map_rank = "pagerank"` lists files from the stored graph, falls back to the reference counts when none is stored, and is byte-stable. */
    @Test fun repoMapPagerankListsFilesAndFallsBackWithoutAGraph() =
        withRuntime("map-rank") { rt ->
            val dir = tmpDir("map-rank")
            rt.cwd = dir
            map(rt, 2000u, "pagerank")
            seedMap(rt, dir)
            val fallback = assertNotNull(start(rt), "refs fallback")
            assertTrue(fallback.text.contains("hot a.rs:1 2"), fallback.text)
            Rank.refresh(Ctx(rt), Index.canon(dir))
            val ranked = assertNotNull(start(rt), "pagerank map")
            assertEquals("repo map\na.rs: hot, mid", ranked.text)
            assertEquals(ranked, start(rt))
            assertEquals(1u, ranked.priority.toUInt())
        }

    @Test fun repoMapRankedByteStableAndTrimmed() =
        withRuntime("map-on") { rt ->
            val dir = tmpDir("map-on")
            rt.cwd = dir
            map(rt, 2000u, "refs")
            seedMap(rt, dir)
            val a = assertNotNull(start(rt), "map on")
            val b = assertNotNull(start(rt), "map on again")
            assertEquals(a, b)
            assertEquals(1u, a.priority.toUInt())
            val lines = a.text.lines()
            assertEquals("repo map", lines[0])
            assertTrue(lines[1].startsWith("hot a.rs:1 2"), "$lines")
            assertTrue(lines[2].startsWith("mid a.rs:2 1"), "$lines")
            assertTrue(lines[3].startsWith("cold a.rs:3 0"), "$lines")
            val full = rt.estimate(a.text, Class.Prose)
            map(rt, if (full > 1u) full - 1u else 1u)
            val trimmed = assertNotNull(start(rt), "trimmed map")
            assertTrue(trimmed.text.lines().size < a.text.lines().size, "cap must drop a line\nfull=${a.text}\ntrim=${trimmed.text}")
        }
}
