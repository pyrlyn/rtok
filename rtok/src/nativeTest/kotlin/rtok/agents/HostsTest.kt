package rtok.agents

import rtok.config.HOSTS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostsTest {
    @Test fun everyConfigHostResolvesInAgentsListOrder() {
        assertEquals(HOSTS.toList(), Hosts.ALL.map { it.id })
        for (id in HOSTS) assertEquals(id, Hosts.host(id)?.id)
        assertNull(Hosts.host("no-such-host"))
    }

    @Test fun everyHostHasAVariantAndAnswersEveryModule() {
        for (h in Hosts.ALL) {
            assertTrue(h.variants.isNotEmpty(), h.id)
            for (v in h.variants) for (m in Hosts.MODULES + "other") h.support(v.kind, m)
        }
    }

    @Test fun hooklessFollowsTheHooksSupportOfEveryVariant() {
        assertTrue(HostHookSupport.hookless("zed"))
        assertFalse(HostHookSupport.hookless("claude"))
        assertFalse(HostHookSupport.hookless("no-such-host"))
        assertTrue(HostHookSupport.hookless("warp"))
    }

    @Test fun supportArmsMatchFirstWins() {
        val claude = Hosts.host("claude")!!
        assertEquals(Support.Yes, claude.support(Kind.Desktop, "mcp"))
        assertEquals(Support.Flag("--proxy"), claude.support(Kind.Cli, "proxy"))
        assertEquals(Support.No("Claude Desktop has no hook events"), claude.support(Kind.Desktop, "hooks"))
        assertEquals(Support.Offer("--yes"), Hosts.host("kimi")!!.support(Kind.Cli, "plugin"))
        assertEquals(Support.Yes, Hosts.host("warp")!!.support(Kind.Desktop, "mcp"))
        assertTrue(Hosts.host("cursor")!!.shared)
        assertFalse(claude.shared)
    }

    @Test fun wantsEveryKindWithoutAFlag() {
        assertTrue(Hosts.wants(Kind.Desktop, cli = false, desktop = false, all = false))
        assertFalse(Hosts.wants(Kind.Desktop, cli = true, desktop = false, all = false))
        assertTrue(Hosts.wants(Kind.Desktop, cli = true, desktop = false, all = true))
        assertTrue(Hosts.wants(Kind.Cli, cli = true, desktop = false, all = false))
    }
}
