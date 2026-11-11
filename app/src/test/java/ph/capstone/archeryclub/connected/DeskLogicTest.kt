package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DeskLogicTest {
    private fun obj(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to (JsonPrimitive(v) as JsonElement) })
    private fun ws(vararg tables: Pair<String, List<JsonObject>>, user: String = "me") =
        JsonObject(mapOf<String, JsonElement>("user_id" to JsonPrimitive(user)) + tables.associate { (k, v) -> k to (JsonArray(v) as JsonElement) })
    private val today = LocalDate.parse("2026-11-11")

    private fun card(id: String, date: String, score: String, validation: String, user: String = "me") =
        obj("id" to id, "user_id" to user, "training_date" to date, "score" to score, "maximum_score" to "360", "validation" to validation,
            "distance" to "18", "target_face" to "80 cm", "scoring_format" to "WA 360")

    @Test fun trainingStatsUseThisAndLastMonth() {
        val cards = listOf(card("a", "2026-11-10", "288", "validated"), card("b", "2026-11-02", "252", "personal"),
            card("c", "2026-10-20", "216", "validated"), card("d", "2026-09-01", "180", "validated")).map(::scoreCard)
        val s = trainingStats(cards, today)
        assertEquals("a", s.best?.id); assertEquals(2, s.roundsThisMonth)
        assertEquals(15, s.monthDelta) // (80 + 70) / 2 = 75 vs 60
        assertEquals("18 m · 80 cm · WA 360", cards[0].conditions); assertEquals("288 / 360", cards[0].scoreLabel)
        assertNull(trainingStats(cards.take(2), today).monthDelta)
    }

    @Test fun trendPrefersValidatedRoundsOldestFirst() {
        val cards = listOf(card("a", "2026-11-10", "288", "validated"), card("b", "2026-11-02", "252", "personal"), card("c", "2026-10-20", "216", "validated")).map(::scoreCard)
        assertEquals(listOf(60, 80), trendPercents(cards))
        assertEquals(listOf(70), trendPercents(cards.filter { it.id == "b" }))
    }

    @Test fun scoreStatesAndCoachQueue() {
        val w = ws("training_records" to listOf(card("a", "2026-11-10", "288", "pending", "x"), card("b", "2026-11-02", "252", "pending", "y"),
            card("c", "2026-11-03", "200", "returned_for_correction")))
        assertEquals(listOf("b", "a"), coachQueue(w).map { it.id })
        assertEquals(ScoreState.RETURNED, scoreState("returned_for_correction")); assertEquals("With coach", scoreState("pending").label)
        assertEquals(listOf("c"), filterCards(scoreCards(w), "fix").map { it.id })
        assertEquals(listOf("c"), scoreCards(w, "me").map { it.id })
    }

    @Test fun feeFollowsItsPayment() {
        val fee = obj("id" to "f1", "user_id" to "me", "purpose" to "Membership fee", "amount" to "500.00", "due_on" to "2026-11-30", "payment_id" to "")
        assertEquals(FeeState.DUE, feeState(fee, null, today))
        assertEquals(FeeState.OVERDUE, feeState(fee, null, LocalDate.parse("2026-12-01")))
        val draft = obj("id" to "p1", "kind" to "payment", "user_id" to "me", "purpose" to "membership fee ", "amount" to "500", "status" to "draft", "created_at" to "1")
        val rejected = obj("id" to "p0", "kind" to "payment", "user_id" to "me", "purpose" to "Membership fee", "amount" to "500", "status" to "rejected", "created_at" to "2")
        assertEquals("p1", paymentFor(fee, listOf(draft, rejected))?.text("id"))
        assertEquals(FeeState.DRAFT, feeState(fee, draft, today))
        assertEquals(FeeState.CHECKING, feeState(fee, obj("status" to "pending_verification"), today))
        assertEquals(FeeState.RETURNED, feeState(fee, obj("status" to "returned_for_correction"), today))
        val w = ws("fee_assessments" to listOf(fee), "financial_records" to listOf(draft))
        val views = feeViews(w, "me", today)
        assertTrue(Math.abs(amountOwed(views) - 500.0) < 0.001)
    }

    @Test fun verifiedPaymentsAreMatchedToOpenFees() {
        val fee = obj("id" to "f1", "user_id" to "u", "purpose" to "Membership fee", "amount" to "500", "due_on" to "2026-11-30", "payment_id" to "")
        val other = obj("id" to "f2", "user_id" to "u", "purpose" to "Shirt", "amount" to "500", "due_on" to "2026-11-01", "payment_id" to "")
        val pay = obj("id" to "p", "kind" to "payment", "user_id" to "u", "purpose" to "Membership fee", "amount" to "500.00", "status" to "verified")
        assertEquals("f1", matchingFee(pay, listOf(other, fee))?.text("id"))
        val w = ws("fee_assessments" to listOf(fee), "financial_records" to listOf(pay))
        assertEquals(listOf("p" to "f1"), paymentsToLink(w).map { it.first.text("id") to it.second.text("id") })
        val linked = ws("fee_assessments" to listOf(obj("id" to "f1", "user_id" to "u", "amount" to "500", "payment_id" to "p")), "financial_records" to listOf(pay))
        assertTrue(paymentsToLink(linked).isEmpty())
    }

    @Test fun fundsAndApprovals() {
        val records = listOf(obj("kind" to "payment", "status" to "verified", "amount" to "1500"), obj("kind" to "expense", "status" to "verified", "amount" to "200.5"),
            obj("kind" to "reimbursement", "status" to "pending_verification", "amount" to "999"))
        val f = fundsSummary(records); assertTrue(Math.abs(f.balance - 1299.5) < 0.001)
        assertEquals("₱1,299.50", peso(f.balance)); assertEquals("₱14,500", peso(14500.0))
        val expense = obj("id" to "e", "kind" to "expense")
        assertEquals(listOf("finance"), missingApprovals(expense, listOf(obj("record_id" to "e", "office" to "president"))))
        assertEquals(emptyList<String>(), missingApprovals(obj("id" to "p", "kind" to "payment"), emptyList()))
    }

    @Test fun electionTimelineAndFeature() {
        assertEquals(listOf(true, true, false, false), electionTimeline("voting").map { it.done })
        assertEquals(listOf(false, false, true, false), electionTimeline("voting").map { it.current })
        assertEquals(listOf(true, true, true, true), electionTimeline("confirmed").map { it.done })
        val list = listOf(obj("id" to "old", "status" to "confirmed", "voting_opens" to "2025"), obj("id" to "d", "status" to "draft"), obj("id" to "now", "status" to "voting", "voting_opens" to "2026"))
        assertEquals("now", featuredElection(list, false)?.text("id"))
        assertEquals("old", featuredElection(list.filter { it.text("id") != "now" }, false)?.text("id"))
        assertEquals("d", featuredElection(list.filter { it.text("id") != "now" }, true)?.text("id"))
    }

    @Test fun termsAreSortedByUrgency() {
        val now = Instant.parse("2026-11-11T00:00:00Z")
        val w = ws("roles" to listOf(obj("id" to "treasurer", "name" to "Treasurer")),
            "member_directory" to listOf(obj("user_id" to "a", "full_name" to "Ana Cruz")),
            "role_assignments" to listOf(
                obj("user_id" to "a", "role_id" to "treasurer", "position" to "", "ends_at" to "2027-05-31T00:00:00Z", "retired_at" to ""),
                obj("user_id" to "b", "role_id" to "treasurer", "position" to "Coach", "ends_at" to "2026-11-01T00:00:00Z", "retired_at" to ""),
                obj("user_id" to "c", "role_id" to "treasurer", "position" to "President", "ends_at" to "2026-11-30T00:00:00Z", "retired_at" to ""),
                obj("user_id" to "d", "role_id" to "treasurer", "position" to "Old", "ends_at" to "2026-01-01T00:00:00Z", "retired_at" to ""),
                obj("user_id" to "e", "role_id" to "treasurer", "position" to "Gone", "ends_at" to "2026-11-20T00:00:00Z", "retired_at" to "2026-11-02")))
        val terms = termViews(w, now)
        assertEquals(listOf("Coach", "President", "Treasurer"), terms.map { it.role })
        assertEquals(listOf(TermState.EXPIRED, TermState.ENDING, TermState.ACTIVE), terms.map { it.state })
        assertEquals("Ana Cruz", terms.last().name)
    }

    @Test fun setupChecklistAndPartners() {
        val items = clubSetup(obj("membership_fee" to "500", "rules_validated" to "false"), listOf("School ID"), listOf("Regular member"))
        assertEquals(listOf(true, true, true, false), items.map { it.done })
        assertEquals("₱500, verified before membership starts", items[1].detail)
        val w = ws("partner_clubs" to listOf(obj("id" to "p1", "name" to "North"), obj("id" to "p2", "name" to "Bay"), obj("id" to "p3", "name" to "Other")),
            "event_access" to listOf(obj("event_id" to "e", "partner_id" to "p1", "revoked_at" to "")),
            "delegates" to listOf(obj("event_id" to "e", "partner_id" to "p2", "status" to "pending"), obj("event_id" to "e", "partner_id" to "p2", "status" to "incomplete")))
        val partners = partnerViews(w, "e")
        assertEquals(listOf("Bay", "North"), partners.map { it.partner.text("name") })
        assertEquals("Missing info", partners[0].label); assertEquals("No delegates", partners[1].label)
    }

    @Test fun goalsFormatPercentMetrics() {
        val report = JsonObject(mapOf("goals" to JsonArray(listOf(obj("title" to "Attendance", "metric" to "attendance_rate", "actual" to "74", "target" to "80", "ends_on" to "2026-12-15"),
            obj("title" to "Members", "metric" to "active_members", "actual" to "", "target" to "60")))))
        val goals = goalViews(report)
        assertEquals("74%", goals[0].format(goals[0].actual!!)); assertTrue(Math.abs(goals[0].progress - 0.925f) < 0.001f)
        assertNull(goals[1].actual); assertTrue(goals[1].progress == 0f); assertEquals("60", goals[1].format(goals[1].target))
    }

    @Test fun newQueuesReachOfficers() {
        val w = ws("elections" to listOf(obj("id" to "e", "status" to "announced")),
            "candidates" to listOf(obj("election_id" to "e", "confirmed" to "false"), obj("election_id" to "e", "confirmed" to "true")),
            "delegates" to listOf(obj("status" to "pending"), obj("status" to "eligible")),
            "interclub_concerns" to listOf(obj("status" to "open")),
            "fee_assessments" to listOf(obj("id" to "f", "user_id" to "u", "purpose" to "Membership fee", "amount" to "500", "payment_id" to "")),
            "financial_records" to listOf(obj("id" to "p", "kind" to "payment", "user_id" to "u", "purpose" to "Membership fee", "amount" to "500", "status" to "verified")))
        val q = officerQueues(w, setOf("elections", "interclub", "finance"), Instant.parse("2026-11-11T00:00:00Z")).associate { it.label to (it.count to it.route) }
        assertEquals(1 to "module:9", q["Candidates to confirm"]); assertEquals(1 to "module:15", q["Delegates to review"])
        assertEquals(1 to "module:15", q["Partner concerns"]); assertEquals(1 to "module:12", q["Payments to match to fees"])
    }

    @Test fun receiptReferencesMatchTheServerRule() {
        assertEquals("9046292514078", receiptReference(" 9046 2925 14078 "))
        assertEquals("OR-12345", receiptReference("or-12345"))
        assertNull(receiptReference("12345")); assertNull(receiptReference("")); assertNull(receiptReference("ref #1234567"))
    }

    @Test fun applicantPayStepFollowsThePayment() {
        val settings = JsonObject(mapOf("payment_details" to obj("gcash_name" to "DLSU Archery Club", "gcash_number" to " 0917 000 0000 ")))
        val d = paymentDetails(settings); assertTrue(d.hasGcash); assertTrue(!d.hasBank); assertEquals("0917 000 0000", d.gcashNumber)
        assertTrue(paymentDetails(null).isEmpty)
        val fee = obj("id" to "f", "user_id" to "me", "purpose" to "Membership fee", "amount" to "800", "due_on" to "2026-11-25", "payment_id" to "")
        val draft = obj("id" to "p", "kind" to "payment", "user_id" to "me", "purpose" to "Membership fee", "amount" to "800", "status" to "draft", "reference" to "9046292514078", "created_at" to "1")
        val w = ws("fee_assessments" to listOf(fee), "financial_records" to listOf(draft))
        val due = membershipFeeDue(w, "me", today)!!
        assertEquals(PayStep.PAY, payStep(due))
        assertEquals(PayStep.CHECKING, payStep(FeeView(fee, obj("status" to "pending_verification"), FeeState.CHECKING)))
        assertNull(payAction("123", null, false))
        assertEquals(PayAction.SAVE_REFERENCE, payAction("9046292514078", null, false))
        assertEquals(PayAction.SAVE_REFERENCE, payAction("9046292514079", draft, true))
        assertEquals(PayAction.UPLOAD_PROOF, payAction("9046 2925 14078", draft, false))
        assertEquals(PayAction.SEND, payAction("9046292514078", draft, true))
        assertNull(membershipFeeDue(ws("fee_assessments" to listOf(obj("purpose" to "Membership fee", "user_id" to "me", "amount" to "800", "payment_id" to "p"))), "me", today))
    }
}
