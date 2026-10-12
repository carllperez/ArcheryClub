package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MemberTasksTest {
    @Test fun tasksStayPendingAfterAcknowledgementAndExcludeOtherMembersAndCompletedRecords() {
        val workspace=Json.parseToJsonElement("""{
          "user_id":"me",
          "renewals":[{"id":"renew","user_id":"me","status":"returned_for_correction"},
                      {"id":"other","user_id":"other","status":"pending"},
                      {"id":"done","user_id":"me","status":"approved"}],
          "notifications":[{"user_id":"me","record_id":"renew","acknowledged_at":"2026-10-10T00:00:00Z"}],
          "borrowings":[{"id":"loan","user_id":"me","status":"released","ends_at":"2026-10-09T00:00:00Z"}],
          "fee_assessments":[{"id":"fee","user_id":"me","due_on":"2026-10-09","amount":"100.25","payment_id":null},
                              {"id":"paid","user_id":"me","payment_id":"verified-payment"}]
        }""").jsonObject
        val tasks=memberTasks(workspace,Instant.parse("2026-10-10T00:00:00Z"),LocalDate.parse("2026-10-10"))
        assertEquals(setOf("renew","loan","fee"),tasks.map{it.row.text("id")}.toSet())
        assertEquals(1,tasks.first{it.table=="renewals"}.module)
        assertTrue(tasks.first{it.table=="borrowings"}.status.startsWith("Overdue"))
        assertTrue(tasks.first{it.table=="fee_assessments"}.status.contains("100.25"))
    }
}
