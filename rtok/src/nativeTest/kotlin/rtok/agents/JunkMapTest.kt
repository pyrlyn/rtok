// Port of src/agents/junk_map.rs tests.
package rtok.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JunkMapTest {
    private val h = "/tmp/rtok-junk-map/h"

    private fun roots(vararg env: Pair<String, String>) = JunkRoots(h, { k -> env.firstOrNull { it.first == k }?.second })

    @Test fun defaultsSitUnderHome() {
        val r = roots()
        assertEquals("$h/.claude/debug", r.resolve("{claude}/debug"))
        assertEquals("$h/.codex", r.resolve("{codex}"))
        assertEquals("$h/.cache/opencode", r.resolve("{xdg_cache}/opencode"))
        assertEquals("$h/.local/share/opencode/log", r.resolve("{xdg_data}/opencode/log"))
    }

    @Test fun environmentOverridesMoveEveryPathUnderThem() {
        val r = roots("CLAUDE_CONFIG_DIR" to "/c", "CODEX_HOME" to "/x", "XDG_CACHE_HOME" to "/xc", "COPILOT_CACHE_HOME" to "/cc")
        assertEquals("/c/paste-cache", r.resolve("{claude}/paste-cache"))
        assertEquals("/x/log", r.resolve("{codex}/log"))
        assertEquals("/xc/zed", r.resolve("{xdg_cache}/zed"))
        assertEquals("/cc", r.resolve("{copilot_cache}"))
    }

    @Test fun aRelativeOrEmptyOverrideIsIgnored() {
        val r = roots("CLAUDE_CONFIG_DIR" to "rel", "CODEX_HOME" to "")
        assertEquals("$h/.claude", r.resolve("{claude}"))
        assertEquals("$h/.codex", r.resolve("{codex}"))
    }

    @Test fun onlyDocumentedRowsComeFromTheSection22TableAndCursorHasNone() {
        assertTrue(JunkMap.specs("cursor").none { it.documented })
        assertTrue(JunkMap.specs("claude").all { it.documented })
        assertTrue(JunkMap.specs("claude").any { it.role == JunkRole.Logs })
    }

    @Test fun everyHostHasARowOrIsListedAsHavingNone() {
        for (host in Hosts.ALL.map { it.id }) {
            assertEquals(host in JunkMap.NO_ROWS, JunkMap.specs(host).isEmpty(), "$host: add a row to `specs`, or list it in NO_ROWS")
        }
    }

    @Test fun onlyVscodeCodeCacheIsPerCommitAndDocumented() {
        val per = Hosts.ALL.flatMap { h -> JunkMap.specs(h.id).map { h.id to it } }.filter { it.second.perCommit }
        assertEquals(2, per.size)
        assertTrue(per.all { (h, s) -> h == "vscode" && s.documented && s.role == JunkRole.Cache && s.path.endsWith("/Code/CachedData") })
        assertTrue(JunkMap.specs("cursor").all { !it.documented && !it.perCommit })
    }

    @Test fun everyTemplateStartsWithAKnownToken() {
        val r = roots()
        for (host in Hosts.ALL.map { it.id }) {
            for (s in JunkMap.specs(host)) assertTrue(r.resolve(s.path).startsWith('/'), "$host: `${s.path}` has no known token")
        }
    }
}
