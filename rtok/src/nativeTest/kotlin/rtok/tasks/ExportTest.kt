@file:Suppress("MagicNumber")

package rtok.tasks

import rtok.config.Tasks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun localTask(
    id: String,
    status: Status,
    title: String = "Title",
    body: String = "Body",
) = Task.open(tid(id), title, body, 0).copy(status = status)

internal fun ExportReport.summary() = actions.map { "${it.kind} ${it.id}" }

// Port of export.rs's tests.
class ExportTest {
    @Test fun aMissingIssueIsCreatedAndAFinishedOneIsSkipped() {
        val plan = exportDiff(listOf(localTask("R2.1", Status.Open), localTask("R1", Status.Open), localTask("R3", Status.Done)), emptyList(), true)
        assertEquals(listOf("R1", "R2.1"), plan.actions.map { it.id.toString() }, "parents first")
        assertTrue(plan.actions.all { it.kind == ExportKind.Create })
        assertEquals(0 to 1, plan.unchanged to plan.skipped)
    }

    @Test fun driftNamesTheFieldsAndPicksTheKind() {
        val open = localTask("R1", Status.Open).copy(description = "New\r\n")
        val stale = localTask("R1", Status.Open)
        val a = exportDiff(listOf(open), listOf(stale), true).actions
        assertEquals(ExportKind.Update to listOf("body"), a[0].kind to a[0].fields)

        val done = localTask("R1", Status.Done).copy(title = "Renamed")
        val c = exportDiff(listOf(done), listOf(stale), true).actions
        assertEquals(ExportKind.Close, c[0].kind)
        assertEquals(listOf("title", "status"), c[0].fields)

        assertEquals(ExportKind.Reopen, exportDiff(listOf(open), listOf(done), true).actions[0].kind)
    }

    @Test fun aMatchingIssueIsUnchangedAndClaimsAreIgnoredWhenNotKept() {
        val local = localTask("R1", Status.Open).copy(assignee = "agent", description = " Body\n")
        val remote = localTask("R1", Status.Open)
        val plan = exportDiff(listOf(local), listOf(remote), false)
        assertTrue(plan.actions.isEmpty())
        assertEquals(1, plan.unchanged)
        assertEquals(listOf("assignee"), exportDiff(listOf(local), listOf(remote), true).actions[0].fields)
    }

    @Test fun linearNeedsATeamBeforeAnythingElse() {
        val e = assertFailsWith<TaskException> { openExporter(Tasks(), "R", "linear") }
        assertTrue("[tasks.export.linear] team" in e.message!!, e.message)
        val u = assertFailsWith<TaskException> { openExporter(Tasks(), "R", "jira") }
        assertTrue("use github or linear" in u.message!!, u.message)
    }

    @Test fun markersCountAsClaims() {
        val local = localTask("R1", Status.Open).copy(parallel = true, conflictsWith = listOf(tid("R2")))
        assertEquals(listOf("conflicts", "parallel"), exportDiff(listOf(local), listOf(localTask("R1", Status.Open)), true).actions[0].fields)
    }

    @Test fun theReportReadsLikeAPlan() {
        val r = ExportReport("github", "me/app", true, listOf(ExportAction(tid("R1"), ExportKind.Update, listOf("title"), "https://x/1")), 2, 1)
        assertEquals(
            "github (me/app): 0 create, 1 update, 0 close, 0 reopen, 2 unchanged, 1 finished not exported (dry run: nothing sent)\nupdate R1  (title)  https://x/1\n",
            renderExport(r),
        )
    }
}
