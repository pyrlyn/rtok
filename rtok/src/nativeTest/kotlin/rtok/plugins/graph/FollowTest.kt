package rtok.plugins.graph

import rtok.config.Graph
import rtok.store.*
import rtok.support.tmpDir
import rtok.sys.*
import kotlin.test.*

class FollowTest {
    private class Fx(
        tag: String,
    ) {
        val base = canonicalize(tmpDir(tag))!!
        val store = Store.openInMemory()
        var cfg = Graph()

        /** A directory [name] whose Cargo.toml depends by path on each of [deps]. */
        fun proj(
            name: String,
            vararg deps: String,
        ) {
            createDirAll("$base/$name")
            writeString("$base/$name/Cargo.toml", "[dependencies]\n" + deps.joinToString("") { "$it = { path = \"../$it\" }\n" })
        }

        fun add(name: String) = store.registerProject("$base/$name", Origin.Manual).id

        fun follow(name: String) = Follow.follow(store, cfg, "$base/$name")

        fun links(): List<String> {
            val names = store.projects().associate { it.id to it.displayName() }
            return store.projectLinks().map { "${names[it.from]}->${names[it.to]}:${it.kind.str}" }
        }
    }

    private fun fx(
        tag: String,
        body: (Fx) -> Unit,
    ) {
        val f = Fx(tag)
        try {
            body(f)
        } finally {
            removeDirAll(f.base)
        }
    }

    @Test fun aRegistersAndLinksBAndCAndNotDAndASecondRunChangesNothing() =
        fx("follow-abc") { fx ->
            fx.proj("A", "B", "C")
            fx.proj("B")
            fx.proj("C")
            fx.proj("D")
            fx.add("A")
            fx.add("D")
            val f = fx.follow("A")
            assertEquals(2, f.registered.size)
            assertTrue(f.registered.all { it.origin == Origin.Reference })
            assertEquals(listOf("A->B:auto", "A->C:auto"), fx.links())
            val again = fx.follow("A")
            assertEquals(Triple(0, 0, 0), Triple(again.registered.size, again.linked, again.dropped))
        }

    @Test fun theDepthLimitStopsAndSaysSo() =
        fx("follow-depth") { fx ->
            fx.proj("A", "B")
            fx.proj("B", "C")
            fx.proj("C", "D")
            fx.proj("D")
            fx.add("A")
            fx.cfg = Graph(referenceDepth = 1u)
            val f = fx.follow("A")
            assertEquals(listOf("A->B:auto"), fx.links())
            assertEquals(listOf<Stop>(Stop.Depth("B", 1u)), f.stops)
            assertEquals("reference_depth 1 reached at B: its references are not followed", f.stops[0].toString())
            fx.cfg = Graph(referenceDepth = 3u)
            assertTrue(fx.follow("A").stops.isEmpty())
            assertEquals(listOf("A->B:auto", "B->C:auto", "C->D:auto"), fx.links())
            fx.cfg = Graph(referenceDepth = 0u)
            assertEquals(1, fx.follow("A").stops.size)
        }

    @Test fun theProjectCapIsReportedAndExistingProjectsAreStillLinked() =
        fx("follow-cap") { fx ->
            fx.proj("A", "B", "C", "D")
            for (n in listOf("B", "C", "D")) fx.proj(n)
            fx.add("A")
            fx.add("D")
            fx.cfg = Graph(maxAutoProjects = 1u)
            val f = fx.follow("A")
            assertEquals(1, f.registered.size)
            assertEquals(1, f.stops.size)
            assertEquals(1u, (f.stops[0] as Stop.Cap).limit)
            assertEquals(2, fx.links().size, "B or C, and the already known D")
        }

    @Test fun aRemovedDependencyDropsTheAutoLinkAndKeepsTheProject() =
        fx("follow-drop") { fx ->
            fx.proj("A", "B")
            fx.proj("B")
            fx.add("A")
            fx.follow("A")
            fx.proj("A")
            assertEquals(1, fx.follow("A").dropped)
            assertTrue(fx.links().isEmpty())
            assertNotNull(fx.store.projectByRoot("${fx.base}/B"))
        }

    @Test fun aLinkTheUserRemovedIsNotRecreatedOrFollowed() =
        fx("follow-unlinked") { fx ->
            fx.proj("A", "B")
            fx.proj("B")
            fx.add("A")
            fx.follow("A")
            assertTrue(fx.store.unlinkProjects(fx.add("A"), fx.add("B")))
            fx.proj("B", "C")
            fx.proj("C")
            val f = fx.follow("A")
            assertEquals(0, f.linked)
            assertTrue(f.registered.isEmpty(), "B is not walked, so C is not found")
            assertTrue(fx.links().isEmpty())
        }

    @Test fun aManualLinkIsNeverDroppedOrChanged() =
        fx("follow-manual") { fx ->
            fx.proj("A", "B")
            fx.proj("B")
            fx.proj("X")
            val a = fx.add("A")
            val x = fx.add("X")
            val b = fx.add("B")
            fx.store.linkProjects(a, x, LinkKind.Manual, null)
            fx.store.linkProjects(a, b, LinkKind.Manual, null)
            fx.follow("A")
            fx.proj("A")
            fx.follow("A")
            assertEquals(listOf("A->X:manual", "A->B:manual"), fx.links())
        }

    @Test fun aCycleEndsAndAnUnreadableManifestDropsNothing() =
        fx("follow-cycle") { fx ->
            fx.proj("A", "B")
            fx.proj("B", "A")
            fx.add("A")
            fx.follow("A")
            assertEquals(listOf("A->B:auto", "B->A:auto"), fx.links())
            writeString("${fx.base}/A/Cargo.toml", "[dependencies\n")
            val f = fx.follow("A")
            assertEquals(0, f.dropped)
            assertEquals(1, f.warnings.size, "${f.warnings}")
            assertEquals(2, fx.links().size)
        }

    @Test fun nothingHappensWhenOffOrWhenTheRootIsNotRegistered() =
        fx("follow-off") { fx ->
            fx.proj("A", "B")
            fx.proj("B")
            assertEquals(0, fx.follow("A").registered.size)
            fx.add("A")
            fx.cfg = Graph(autoLinkReferences = false)
            assertEquals(0, fx.follow("A").registered.size)
            assertTrue(fx.links().isEmpty())
        }
}
