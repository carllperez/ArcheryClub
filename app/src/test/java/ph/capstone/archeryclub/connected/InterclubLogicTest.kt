package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class InterclubLogicTest {
    private val now = Instant.parse("2026-11-20T04:00:00Z")
    private fun ev(status: String, closes: String = "2026-11-28T15:00:00Z") = buildJsonObject {
        put("event_id", "E"); put("status", status); put("registration_closes", closes); put("shared_title", "Friendly Shoot") }
    private fun ws(access: Boolean = true, delegates: List<Pair<String, String>> = emptyList(), assigned: List<String> = emptyList(), results: List<Pair<String, Boolean>> = emptyList()) = buildJsonObject {
        put("event_access", JsonArray(if (access) listOf(buildJsonObject { put("event_id", "E"); put("role", "partner"); put("partner_id", "P") }) else emptyList()))
        put("delegates", JsonArray(delegates.map { (id, s) -> buildJsonObject { put("id", id); put("event_id", "E"); put("status", s) } }))
        put("interclub_assignments", JsonArray(assigned.map { buildJsonObject { put("event_id", "E"); put("delegate_id", it) } }))
        put("interclub_results", JsonArray(results.map { (id, v) -> buildJsonObject { put("id", "r$id"); put("event_id", "E"); put("delegate_id", id); if (v) put("verified_by", "U") } }))
    }

    @Test fun stepFollowsTheRecords() {
        assertEquals(IC_INVITE, interclubStep(ev("preparation"), ws(access = false), now))
        assertEquals(IC_DELEGATES, interclubStep(ev("preparation"), ws(), now))
        // Sign-ups closed and nothing pending: on to the schedule.
        assertEquals(IC_SCHEDULE, interclubStep(ev("preparation", "2026-11-19T00:00:00Z"), ws(delegates = listOf("a" to "eligible")), now))
        // Closed, but a delegate still waits for review: still the Delegates step.
        assertEquals(IC_DELEGATES, interclubStep(ev("preparation", "2026-11-19T00:00:00Z"), ws(delegates = listOf("a" to "pending")), now))
        assertEquals(IC_CHECKIN, interclubStep(ev("active"), ws(), now))
        assertEquals(IC_RESULTS, interclubStep(ev("active"), ws(results = listOf("a" to false)), now))
        assertEquals(IC_RESULTS, interclubStep(ev("completed"), ws(results = listOf("a" to false)), now))
        assertEquals(IC_ARCHIVE, interclubStep(ev("completed"), ws(results = listOf("a" to true)), now))
        assertEquals(IC_DONE, interclubStep(ev("archived"), ws(), now))
    }

    @Test fun nextStepNamesTheWork() {
        assertEquals("Review 2 delegates", interclubNext(ev("preparation"), ws(delegates = listOf("a" to "pending", "b" to "pending")), now)!!.title)
        assertEquals("Assign 1 archer to targets", interclubNext(ev("preparation", "2026-11-19T00:00:00Z"), ws(delegates = listOf("a" to "eligible", "b" to "eligible"), assigned = listOf("a")), now)!!.title)
        assertEquals("Check in 1 archer", interclubNext(ev("active"), ws(delegates = listOf("a" to "eligible", "b" to "checked_in")), now)!!.title)
        assertEquals("Verify 1 result", interclubNext(ev("completed"), ws(results = listOf("a" to false, "b" to true)), now)!!.title)
        assertNull(interclubNext(ev("archived"), ws(), now))
        // A substituted delegate no longer counts.
        assertEquals(1, icDelegates(ws(delegates = listOf("a" to "substituted", "b" to "eligible")), "E").size)
    }

    @Test fun resultsRankByScore() {
        val r = listOf("590", "612/720", "DNF", "598").mapIndexed { i, v -> buildJsonObject { put("id", "$i"); put("result", v) } }
        assertEquals(listOf("612/720", "598", "590", "DNF"), icRanked(r).map { it.text("result") })
        assertEquals("NR", clubInitials("North Range Archers")); assertEquals("UA", clubInitials("UP Archery"))
    }

    @Test fun everyoneHereLeadsToResultsNotAnEmptyCheckIn() {
        val next = interclubNext(ev("active"), ws(delegates = listOf("a" to "checked_in")), now)!!
        assertEquals("Everyone is checked in", next.title); assertEquals("ic_results:E", next.route)
    }

    @Test fun theRecorderIsToldSomeoneElseVerifies() {
        fun w(recorder: String) = buildJsonObject {
            put("user_id", "me")
            put("delegates", JsonArray(listOf(buildJsonObject { put("id", "a"); put("event_id", "E"); put("status", "checked_in") })))
            put("interclub_results", JsonArray(listOf(buildJsonObject { put("id", "r"); put("event_id", "E"); put("delegate_id", "a"); put("recorded_by", recorder) })))
        }
        assertEquals("Another officer verifies your result", resultsNext(w("me"), "E").first)
        assertEquals("Verify 1 result", resultsNext(w("someone"), "E").first)
        assertEquals(true, recordedByMe((w("me")["interclub_results"] as JsonArray)[0] as JsonObject, w("me")))
    }

    @Test fun theResultsBannerSaysNothingIsRecordedYet() {
        assertEquals("No results recorded yet" to Tone.NEUTRAL, resultsBanner(emptyList()))
        assertEquals("1 not verified yet" to Tone.WARNING, resultsBanner(listOf(buildJsonObject { put("id", "r") })))
        assertEquals(Tone.POSITIVE, resultsBanner(listOf(buildJsonObject { put("id", "r"); put("verified_by", "U") })).second)
    }

    @Test fun aPartnerSeesOneStagePerEventStatus() {
        fun e(s: String) = buildJsonObject { put("status", s) }
        assertEquals(PartnerStage.OPEN, partnerStage(e("preparation")))
        assertEquals(PartnerStage.DAY, partnerStage(e("active")))
        assertEquals(PartnerStage.CHECKING, partnerStage(e("completed")))
        assertEquals(PartnerStage.FINAL, partnerStage(e("archived")))
    }

    @Test fun signUpDeadlinesCountWholeManilaDays() {
        val now = Instant.parse("2026-11-21T01:00:00Z") // 9:00 AM Nov 21, Manila
        assertEquals("4 days left", daysLeftLabel(Instant.parse("2026-11-25T08:00:00Z"), now))
        assertEquals("Last day", daysLeftLabel(Instant.parse("2026-11-21T10:00:00Z"), now))
        assertNull(daysLeftLabel(Instant.parse("2026-11-20T10:00:00Z"), now))
    }

    @Test fun aPartnerTallyCountsCompetedPublishedAndBest() {
        val w = buildJsonObject {
            put("delegates", JsonArray(listOf(
                buildJsonObject { put("id", "a"); put("event_id", "E"); put("status", "checked_in") },
                buildJsonObject { put("id", "m"); put("event_id", "E"); put("status", "checked_in") },
                buildJsonObject { put("id", "x"); put("event_id", "E"); put("status", "eligible") })))
            put("interclub_results", JsonArray(listOf(
                buildJsonObject { put("id", "r1"); put("event_id", "E"); put("delegate_id", "a"); put("result", "612 / 720"); put("verified_by", "U") },
                buildJsonObject { put("id", "r2"); put("event_id", "E"); put("delegate_id", "m"); put("result", "655 / 720") })))
        }
        assertEquals(PartnerTally(competed = 2, published = 1, pending = 1, best = 612), partnerTally(w, "E"))
    }
}
