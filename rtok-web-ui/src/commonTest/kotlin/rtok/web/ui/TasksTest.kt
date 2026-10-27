// Port of web/src/pages/Tasks.test.tsx's logic cases: markers stay quiet for a plain task, the filter and the order.
package rtok.web.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasksTest {
    private val wire =
        """{"adapter":"disk","hidden":3,"tasks":[
        {"id":"A1","title":"Ship the board","status":"in-progress","parent":null,"priority":2,"assignee":"agent-1",
         "blocked_by":[],"conflicts_with":[],"parallel":false,"external":null,"updated_at":1},
        {"id":"A1.1","title":"Columns","status":"open","parent":"A1","priority":0,"assignee":null,
         "blocked_by":["A2"],"conflicts_with":["A3"],"parallel":true,
         "external":{"adapter":"github","number":7,"url":"https://github.com/me/x/issues/7"},"updated_at":2},
        {"id":"A2","title":"Old","status":"open","parent":null,"priority":3,"assignee":null,
         "blocked_by":[],"conflicts_with":[],"parallel":false,
         "external":{"adapter":"github","number":8,"url":"javascript:alert(1)"},"updated_at":3}]}"""

    private val board = taskBoard(Json.parseToJsonElement(wire).jsonObject)

    @Test fun theBoardParses() {
        assertEquals("disk", board.adapter)
        assertEquals(3, board.hidden)
        assertEquals(listOf("A1", "A1.1", "A2"), board.tasks.map { it.id })
        assertEquals(listOf("A2"), board.tasks[1].blockedBy)
        assertEquals(7, board.tasks[1].external?.number)
    }

    @Test fun onlyAnHttpLinkBecomesAnIssue() = assertNull(board.tasks[2].external)

    @Test fun aPlainTaskHasNoMarkers() = assertEquals(emptyList(), taskMarkers(board.tasks[0]))

    @Test fun markersSayPriorityNeedsConflictsAndParallel() {
        val m = taskMarkers(board.tasks[1])
        assertEquals(listOf("P0", "needs A2", "conflicts A3", "parallel"), m.map { it.label })
        assertEquals("fail", m[0].tone)
        assertEquals("muted", taskMarkers(board.tasks[2])[0].tone)
    }

    @Test fun cardsInFiltersByColumnAndQueryMostUrgentFirst() {
        assertEquals(listOf("A1.1", "A2"), cardsIn(board.tasks, "open", "").map { it.id })
        assertEquals(listOf("A1.1"), cardsIn(board.tasks, "open", " a1 ").map { it.id }, "the parent matches")
        assertEquals(listOf("A1"), cardsIn(board.tasks, "in-progress", "AGENT").map { it.id })
        assertTrue(cardsIn(board.tasks, "done", "").isEmpty())
    }

    @Test fun columnTitles() {
        assertEquals("in progress", taskStatusTitle("in-progress"))
        assertEquals("weird", taskStatusTitle("weird"))
    }
}
