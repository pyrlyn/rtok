package rtok.store

import rtok.sys.createDirAll
import rtok.sys.removeDirAll
import kotlin.test.*

/** Port of project_links.rs tests. */
class ProjectLinksTest {
    /** A, B, C and D as registered projects. */
    private class World(
        tag: String,
    ) {
        val store = Store.openInMemory()
        val dir = tmpDir("links-$tag")
        val id: Map<Char, Int> = "ABCD".associateWith { c -> store.registerProject("$dir/$c".also { createDirAll(it) }, Origin.Manual).id }

        fun link(
            a: Char,
            b: Char,
            kind: LinkKind,
        ) = store.linkProjects(id[a]!!, id[b]!!, kind, null)

        fun scope(a: Char): String {
            val names = id.entries.associate { (c, i) -> i to c }
            return store.projectScope(id[a]!!).map { names[it.id]!! }.joinToString("")
        }
    }

    private val worlds = ArrayList<World>()

    private fun world(tag: String) = World(tag).also { worlds += it }

    @AfterTest fun cleanup() = worlds.forEach { removeDirAll(it.dir) }

    @Test fun scopeFollowsLinksTransitivelyOnceEachAndSurvivesCycles() {
        val w = world("scope")
        assertEquals("A", w.scope('A'))
        w.link('A', 'B', LinkKind.Auto)
        w.link('B', 'C', LinkKind.Auto)
        assertEquals("ABC", w.scope('A'))
        assertEquals("BC", w.scope('B'), "links are directional")
        w.link('A', 'D', LinkKind.Manual)
        w.link('D', 'A', LinkKind.Manual)
        w.link('C', 'A', LinkKind.Auto)
        assertEquals("ABDC", w.scope('A'))
        assertEquals("DABC", w.scope('D'))
    }

    @Test fun aMissingProjectDropsOutOfTheScopeAndItsLinksComeBackWithIt() {
        val w = world("missing")
        w.link('A', 'B', LinkKind.Auto)
        w.link('B', 'C', LinkKind.Auto)
        removeDirAll("${w.dir}/B")
        assertEquals("A", w.scope('A'), "C is behind the missing B")
        assertEquals(2, w.store.projectLinks().size)
        createDirAll("${w.dir}/B")
        assertEquals("ABC", w.scope('A'))
    }

    @Test fun linkRulesSelfDuplicateManualOverAutoAndRememberedUnlink() {
        val w = world("rules")
        val (a, b, c) = listOf('A', 'B', 'C').map { w.id[it]!! }
        assertFailsWith<StoreException> { w.store.linkProjects(a, a, LinkKind.Manual, null) }
        assertTrue(w.link('A', 'B', LinkKind.Auto))
        assertFalse(w.link('A', 'B', LinkKind.Auto), "a duplicate is a no-op")
        assertTrue(w.link('A', 'B', LinkKind.Manual), "a manual link takes an auto one over")
        assertEquals(LinkKind.Manual, w.store.projectLinks()[0].kind)
        assertFalse(w.link('A', 'B', LinkKind.Manual))
        assertTrue(w.store.unlinkProjects(a, b))
        assertFalse(w.store.unlinkProjects(a, b))
        assertTrue(w.store.projectLinks().isEmpty(), "a manual unlink deletes")
        assertTrue(w.link('A', 'C', LinkKind.Auto))
        assertTrue(w.store.unlinkProjects(a, c))
        assertEquals("A", w.scope('A'))
        assertFalse(w.link('A', 'C', LinkKind.Auto), "an auto link the user removed stays removed")
        assertTrue(w.link('A', 'C', LinkKind.Manual), "a manual link brings it back")
        assertEquals("AC", w.scope('A'))
    }

    @Test fun dropAutoLinksRemovesOnlyActiveAutoLinksOutsideKeep() {
        val w = world("drop")
        val (a, b, c, d) = listOf('A', 'B', 'C', 'D').map { w.id[it]!! }
        w.link('A', 'B', LinkKind.Auto)
        w.link('A', 'C', LinkKind.Auto)
        w.link('A', 'D', LinkKind.Manual)
        w.link('B', 'C', LinkKind.Auto)
        assertEquals(1, w.store.dropAutoLinks(a, listOf(b)))
        assertEquals(listOf(a to b, a to d, b to c), w.store.projectLinks().map { it.from to it.to })
        w.store.unlinkProjects(a, b)
        assertEquals(0, w.store.dropAutoLinks(a, emptyList()))
        assertFalse(w.link('A', 'B', LinkKind.Auto))
    }

    @Test fun removingAProjectLeavesNoLinksInEitherDirection() {
        val w = world("remove")
        val (b, c) = listOf('B', 'C').map { w.id[it]!! }
        w.link('A', 'B', LinkKind.Manual)
        w.link('B', 'C', LinkKind.Auto)
        w.link('C', 'B', LinkKind.Auto)
        w.store.unlinkProjects(c, b)
        assertTrue(w.store.removeProject(b))
        assertEquals(0L, w.store.conn { it.longOf("SELECT count(*) FROM project_links") }, "active and remembered links both went with B")
    }
}
