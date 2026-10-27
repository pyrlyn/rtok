// Port of web/src/pages/fixState.test.ts (T331.12).
package rtok.web.ui

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class FixTest {
    private fun ref(s: String) = FixRef(s, "hooks.Stop[0]")

    private fun item(
        source: String,
        selected: Boolean,
    ) = """{"source":"$source","path":"hooks.Stop[0]","kind":"broken-hook","agent":"claude","label":"/gone.sh","detail":"missing","kept_in":null,"shared":${!selected},"selected":$selected,"can_keep":false}"""

    private fun plan(
        vararg items: String,
        token: String = "t1",
    ) = Json.parseToJsonElement("""{"items":[${items.joinToString(",")}],"diff":"","refused":[],"token":"$token"}""") as JsonObject

    private fun fixed(
        text: String,
        code: Int,
        replan: JsonObject?,
    ) = Json.parseToJsonElement("""{"text":${Json.encodeToString(String.serializer(), text)},"code":$code,"replan":${replan ?: "null"}}""") as JsonObject

    private fun run(
        s: FixState,
        vararg es: FixEvent,
    ) = es.fold(s, ::fixReduce)

    private val ready get() = run(FixState(), FixEvent.Planned(0, plan(item("/u", true))))

    @Test fun aToggleIsADeltaAndASecondToggleUndoesIt() {
        val on = run(FixState(), FixEvent.Toggle(ref("/p")))
        assertEquals(listOf(ref("/p")), on.selection.toggled)
        assertEquals(FixPhase.LOADING, on.phase)
        val off = fixReduce(on, FixEvent.Toggle(ref("/p")))
        assertEquals(emptyList(), off.selection.toggled)
        assertEquals(2, off.seq)
    }

    @Test fun aPlanForAnOlderSelectionIsDropped() {
        val s = run(FixState(), FixEvent.Toggle(ref("/p")))
        assertSame(s, fixReduce(s, FixEvent.Planned(0, plan(item("/u", true)))))
        val fresh = fixReduce(s, FixEvent.Planned(s.seq, plan(item("/u", true))))
        assertEquals(FixPhase.READY, fresh.phase)
        assertEquals(1, selectedCount(fresh.plan))
    }

    @Test fun aKeepSwapStartsTheSelectionOver() {
        val s = run(FixState(), FixEvent.Toggle(ref("/p")), FixEvent.Keep(ref("/u")))
        assertEquals(FixSelection(listOf(ref("/u")), emptyList()), s.selection)
    }

    @Test fun confirmingNeedsASelectionAndAChangeWithdrawsIt() {
        val none = run(FixState(), FixEvent.Planned(0, plan(item("/p", false))))
        assertEquals(FixPhase.READY, fixReduce(none, FixEvent.Ask).phase)
        val asked = fixReduce(ready, FixEvent.Ask)
        assertEquals(FixPhase.CONFIRMING, asked.phase)
        assertEquals(FixPhase.READY, fixReduce(asked, FixEvent.Cancel).phase)
        assertEquals(FixPhase.LOADING, fixReduce(asked, FixEvent.Toggle(ref("/u"))).phase)
    }

    @Test fun onlyAConfirmedRequestIsAppliedAndAFinishedOneIgnoresEdits() {
        assertEquals(FixPhase.READY, fixReduce(ready, FixEvent.Applying).phase)
        val applying = run(ready, FixEvent.Ask, FixEvent.Applying)
        assertEquals(FixPhase.APPLYING, applying.phase)
        assertSame(applying, fixReduce(applying, FixEvent.Toggle(ref("/u"))))
        val done = fixReduce(applying, FixEvent.Applied(fixed("ok", 0, null)))
        assertEquals(FixPhase.DONE, done.phase)
        assertSame(done, fixReduce(done, FixEvent.Keep(ref("/u"))))
        val again = fixReduce(done, FixEvent.Again)
        assertEquals(FixPhase.LOADING, again.phase)
        assertNull(again.plan)
    }

    @Test fun aStaleConfirmationShowsTheFreshPlanAndAsksAgain() {
        val applying = run(ready, FixEvent.Ask, FixEvent.Applying)
        val fresh = plan(item("/u", true), item("/u2", true), token = "t2")
        val s = fixReduce(applying, FixEvent.Applied(fixed("changed since the preview\n", 1, fresh)))
        assertEquals(FixPhase.READY, s.phase)
        assertNull(s.result)
        assertEquals("changed since the preview", s.error)
        assertEquals(fresh, s.plan)
        assertEquals(FixPhase.CONFIRMING, fixReduce(s, FixEvent.Ask).phase)
    }

    @Test fun aFailedRequestShowsTheErrorAndKeepsTheSelection() {
        val s = run(FixState(), FixEvent.Toggle(ref("/p")), FixEvent.Failed("connection closed"))
        assertEquals(FixPhase.ERROR, s.phase)
        assertEquals("connection closed", s.error)
        assertEquals(listOf(ref("/p")), s.selection.toggled)
    }

    @Test fun diffToneSkipsTheHeaders() {
        assertEquals(listOf("muted", "muted", "ok", "fail", "muted"), listOf("+++ b", "--- a", "+x", "-y", " z").map(::diffTone))
    }
}
