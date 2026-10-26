package rtok.agents

import rtok.store.Store
import rtok.store.agentDetail
import rtok.store.endAgent
import rtok.store.hostId
import rtok.store.mainAgent
import rtok.store.registerAgent
import rtok.store.setAgentAncestors
import rtok.store.setAgentLastSeen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkTest {
    private fun store(): Store {
        rtok.support.installDb()
        return Store.openInMemory()
    }

    private val noEnv: (String) -> String? = { null }
    private val claudeSession: (String) -> String? = { if (it == "CLAUDE_CODE_SESSION_ID") "s-1" else null }
    private val zedHookless = HookSupport { it == "zed" }

    private fun caller(
        host: String,
        store: Store,
        cwd: String?,
        since: Long = 0,
        ancestors: List<Int> = emptyList(),
    ) = Caller(host, store.hostId(host) ?: 6, cwd, "mcp-1", "30m", since, ancestors)

    private fun resolve(
        store: Store,
        who: Caller,
        env: (String) -> String? = noEnv,
    ) = AgentLink.resolve(store, who, zedHookless, env)

    private fun seen(
        store: Store,
        who: Caller,
        session: String,
        ago: Long,
    ): String {
        val id = store.registerAgent(who.hostId, session, null, who.cwd, null)
        store.setAgentLastSeen(id, rtok.log.now() - ago)
        return id
    }

    private fun startedAgo(store: Store) = caller("claude", store, "/r", since = rtok.log.now() - 100)

    private fun hooked(
        store: Store,
        hostId: Int,
        session: String,
        cwd: String,
        chain: List<Int>,
    ): String {
        val id = store.registerAgent(hostId, session, null, cwd, null)
        store.setAgentAncestors(id, chain)
        return id
    }

    @Test fun envRuleRegistersTheHookSessionRowAndHooksReuseIt() =
        store().use { store ->
            val who = caller("grok", store, "/r")
            val got = resolve(store, who) { if (it == "GROK_SESSION_ID") "s-9" else null }
            assertIs<Link.Linked>(got)
            assertEquals(Rule.Env, got.rule)
            assertEquals(got.id, store.registerAgent(who.hostId, "s-9", null, "/r", null))
        }

    @Test fun envVarOfAnotherHostIsIgnored() =
        store().use { store ->
            assertEquals(Link.None, resolve(store, caller("cursor", store, "/r")) { "s-9" })
        }

    @Test fun claudeEnvLinksTheRowItsHooksWroteOverACwdMatch() =
        store().use { store ->
            val who = caller("claude", store, "/r")
            val mine = store.registerAgent(who.hostId, "s-1", null, "/worktree", null)
            store.registerAgent(who.hostId, "s-2", null, "/r", null)
            assertEquals(Link.Linked(mine, Rule.Env), resolve(store, who, claudeSession))
        }

    @Test fun claudeEnvWithoutARowRegistersNothingAndFallsThrough() =
        store().use { store ->
            val who = caller("claude", store, "/r")
            val other = store.registerAgent(who.hostId, "s-2", null, "/r", null)
            assertEquals(Link.Linked(other, Rule.Cwd), resolve(store, who, claudeSession))
            assertNull(store.mainAgent(who.hostId, "s-1"))
        }

    @Test fun shellAgentPrefersRtokAgentIdThenTheHostSessionMainRow() =
        store().use { store ->
            val claude = store.hostId("claude")!!
            val main = store.registerAgent(claude, "s-1", null, "/r", null)
            store.registerAgent(claude, "s-1", "sub", "/r", null)
            val both: (String) -> String? = { if (it == "RTOK_AGENT_ID") "from-env-file" else claudeSession(it) }
            assertEquals("from-env-file", AgentLink.shellAgent(store, both))
            assertEquals(main, AgentLink.shellAgent(store, claudeSession))
            assertNull(AgentLink.shellAgent(null, claudeSession))
            assertNull(AgentLink.shellAgent(store) { if (it == "CLAUDE_CODE_SESSION_ID") "s-9" else null })
            assertNull(store.mainAgent(claude, "s-9"))
        }

    @Test fun oneLiveAgentInTheCwdIsTheLink() =
        store().use { store ->
            val who = caller("claude", store, "/r")
            val id = store.registerAgent(who.hostId, "s1", null, "/r", null)
            store.registerAgent(who.hostId, "s2", null, "/elsewhere", null)
            assertEquals(Link.Linked(id, Rule.Cwd), resolve(store, who))
        }

    @Test fun twoLiveAgentsInTheCwdAreAmbiguous() =
        store().use { store ->
            val who = caller("claude", store, "/r")
            val want = listOf(store.registerAgent(who.hostId, "s1", null, "/r", null), store.registerAgent(who.hostId, "s2", null, "/r", null)).sorted()
            assertEquals(Link.Ambiguous(want), resolve(store, who))
        }

    @Test fun endedSubAndForeignHostAgentsAreNotCandidates() =
        store().use { store ->
            val who = caller("claude", store, "/r")
            val ended = store.registerAgent(who.hostId, "s1", null, "/r", null)
            store.endAgent(ended, 1)
            store.registerAgent(who.hostId, "s2", "sub", "/r", null)
            store.registerAgent(store.hostId("cursor")!!, "s3", null, "/r", null)
            assertEquals(Link.None, resolve(store, who))
        }

    @Test fun aHostWithoutHooksRegistersItsOwnRow() =
        store().use { store ->
            val got = resolve(store, caller("zed", store, "/r"))
            assertIs<Link.Linked>(got)
            assertEquals(Rule.Own, got.rule)
            val d = store.agentDetail(got.id)!!
            assertEquals("other" to "mcp-1", d.host to d.hostSessionId)
            assertFalse(HookSupport.Unknown.hookless("claude"))
        }

    @Test fun anAgentNotSeenSinceTheProcessStartedDoesNotLink() =
        store().use { store ->
            val who = startedAgo(store)
            seen(store, who, "a", 500)
            assertEquals(Link.None, resolve(store, who))
        }

    @Test fun onlyTheAgentSeenSinceTheStartLinks() =
        store().use { store ->
            val who = startedAgo(store)
            seen(store, who, "a", 500)
            val b = seen(store, who, "b", 10)
            assertEquals(Link.Linked(b, Rule.Cwd), resolve(store, who))
        }

    @Test fun twoAgentsSeenSinceTheStartAreAmbiguous() =
        store().use { store ->
            val who = startedAgo(store)
            seen(store, who, "stale", 500)
            val want = listOf(seen(store, who, "a", 20), seen(store, who, "b", 10)).sorted()
            assertEquals(Link.Ambiguous(want), resolve(store, who))
        }

    @Test fun onlyCwdAndAncestorLinksAreUnstable() {
        assertTrue(Rule.Env.isStable && Rule.Own.isStable && Rule.Arg.isStable)
        assertFalse(Rule.Cwd.isStable || Rule.Ancestor.isStable)
    }

    @Test fun theNearestCommonAncestorPicksTheAgentOverAFartherOne() =
        store().use { store ->
            val base = caller("claude", store, "/r")
            val a = hooked(store, base.hostId, "a", "/r", listOf(100, 200, 300))
            val b = hooked(store, base.hostId, "b", "/r", listOf(501, 500, 300))
            assertEquals(Link.Linked(a, Rule.Ancestor), resolve(store, caller("claude", store, "/r", ancestors = listOf(200, 300))))
            assertEquals(Link.Linked(b, Rule.Ancestor), resolve(store, caller("claude", store, "/r", ancestors = listOf(500, 300))))
        }

    @Test fun twoAgentsBehindOneAncestorAreAmbiguousEvenWithOneInTheCwd() =
        store().use { store ->
            val host = caller("claude", store, "/r").hostId
            val want = listOf(hooked(store, host, "a", "/r", listOf(200)), hooked(store, host, "b", "/elsewhere", listOf(200))).sorted()
            assertEquals(Link.Ambiguous(want), resolve(store, caller("claude", store, "/r", ancestors = listOf(200))))
        }

    @Test fun theAncestorRuleBeatsTheCwdRule() =
        store().use { store ->
            val host = caller("claude", store, "/r").hostId
            val byPid = hooked(store, host, "a", "/worktree", listOf(200))
            hooked(store, host, "b", "/r", listOf(999))
            assertEquals(Link.Linked(byPid, Rule.Ancestor), resolve(store, caller("claude", store, "/r", ancestors = listOf(200))))
        }

    @Test fun aMissingChainOrAForeignEndedOrUnseenAgentSkipsTheRule() =
        store().use { store ->
            val base = startedAgo(store)
            hooked(store, store.hostId("cursor")!!, "other-host", "/r", listOf(200))
            val ended = hooked(store, base.hostId, "ended", "/r", listOf(200))
            store.endAgent(ended, rtok.log.now())
            val stale = hooked(store, base.hostId, "stale", "/r", listOf(200))
            store.setAgentLastSeen(stale, rtok.log.now() - 500)
            val who = caller("claude", store, "/r", since = base.since, ancestors = listOf(200))
            assertEquals(Link.None, resolve(store, who))
            val old = seen(store, who, "old-client", 10)
            assertEquals(Link.Linked(old, Rule.Cwd), resolve(store, caller("claude", store, "/r", since = base.since, ancestors = listOf(200, 300))))
        }

    @Test fun aProcessUnderNoLiveSessionAndInNoOnesCwdIsOutside() =
        store().use { store ->
            val host = caller("claude", store, "/cox").hostId
            hooked(store, host, "a", "/rtok", listOf(30709, 25435, 25434))
            hooked(store, host, "b", "/weft", listOf(26000, 13793, 13792))
            seen(store, caller("claude", store, "/mail"), "old-client", 0)
            assertEquals(Link.Outside, resolve(store, caller("claude", store, "/cox", ancestors = listOf(11381, 11149))))
            assertEquals(Link.None, resolve(store, caller("claude", store, "/cox")))
        }

    @Test fun withoutAChainedLiveRowNothingIsOutside() =
        store().use { store ->
            val who = caller("claude", store, "/cox", ancestors = listOf(11381, 11149))
            assertEquals(Link.None, resolve(store, who))
            seen(store, caller("claude", store, "/r"), "old-client", 0)
            assertEquals(Link.None, resolve(store, who))
        }

    @Test fun aHooklessHostRegistersItselfEvenBesideChainedRowsOfItsSharedHostRow() =
        store().use { store ->
            val base = caller("zed", store, "/r")
            hooked(store, base.hostId, "grok-1", "/g", listOf(700, 701))
            val got = resolve(store, caller("zed", store, "/r", ancestors = listOf(11381, 11149)))
            assertIs<Link.Linked>(got)
            assertEquals(Rule.Own, got.rule)
        }
}
