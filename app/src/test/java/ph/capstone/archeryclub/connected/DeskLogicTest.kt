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

    @Test fun officersAreGroupedOncePerPersonWithRealRoleNames() {
        val now = Instant.parse("2026-11-12T00:00:00Z")
        val w = ws("roles" to listOf(obj("id" to "administrator", "name" to "Administrator"), obj("id" to "president", "name" to "President"), obj("id" to "treasurer", "name" to "Treasurer / Finance officer")),
            "member_directory" to listOf(obj("user_id" to "c", "full_name" to "Carll"), obj("user_id" to "p", "full_name" to "Test Pres")),
            "role_assignments" to listOf(
                obj("id" to "1", "user_id" to "c", "role_id" to "administrator", "position" to "Development administrator", "ends_at" to "2027-10-09T00:00:00Z", "retired_at" to ""),
                obj("id" to "2", "user_id" to "c", "role_id" to "administrator", "position" to "Admin", "ends_at" to "2026-12-31T00:00:00Z", "retired_at" to ""),
                obj("id" to "3", "user_id" to "c", "role_id" to "president", "position" to "President", "ends_at" to "2027-06-30T00:00:00Z", "retired_at" to ""),
                obj("id" to "4", "user_id" to "c", "role_id" to "treasurer", "position" to "Test Fin", "ends_at" to "2026-11-20T00:00:00Z", "retired_at" to ""),
                obj("id" to "5", "user_id" to "p", "role_id" to "administrator", "position" to "President", "ends_at" to "2026-12-31T00:00:00Z", "retired_at" to "")))
        val people = officerViews(w, now)
        assertEquals(listOf("Carll", "Test Pres"), people.map { it.name })
        val carll = people[0]
        assertEquals(TermState.ENDING, carll.worst)
        assertEquals(listOf("Administrator", "President", "Treasurer / Finance officer"), carll.roleNames)
        assertEquals(1, carll.duplicates); assertEquals("2", carll.roles.last().assignment.text("id"))
        assertEquals("", carll.roles.first { it.roleName == "President" }.title)
        assertEquals("President", people[1].roles.single().title) // the typed title no longer hides the real role
        assertEquals("Administrator", people[1].roles.single().roleName)
    }

    @Test fun waitingApplicantsSeeWhatComesNext() {
        val view = ApplicationView(obj("id" to "a", "status" to "under_review"), ApplicationStage.UNDER_REVIEW, emptyList(),
            listOf(obj("verified_by" to "x"), obj("verified_by" to "")))
        val steps = waitingSteps(view, 800.0)
        assertEquals(3, steps.size); assertTrue(steps[0].current)
        assertEquals("Checking your documents now: 1 of 2 verified.", steps[0].detail)
        assertEquals("You pay the ₱800 membership fee", steps[1].title)
        assertEquals("No membership fee", waitingSteps(view, 0.0)[1].title)
    }

    // ---------------------------------------------------------------- Roles & access

    private fun role(id: String, name: String, perms: List<String>, defaults: List<String>? = null) = JsonObject(buildMap {
        put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name))
        put("permissions", JsonArray(perms.map { JsonPrimitive(it) }))
        if (defaults != null) put("default_permissions", JsonArray(defaults.map { JsonPrimitive(it) }))
    })

    private val rolesNow = Instant.parse("2026-11-12T00:00:00Z")
    private fun rolesWorkspace() = ws(
        "roles" to listOf(role("equipment_officer", "Armorer", listOf("training", "equipment"), listOf("equipment")),
            role("president", "President", listOf("president"), listOf("president")),
            role("administrator", "Administrator", listOf("administration"), listOf("administration")),
            role("range_marshal", "Range marshal", emptyList())),
        "role_assignments" to listOf(
            obj("id" to "1", "user_id" to "a", "role_id" to "equipment_officer", "starts_at" to "2026-01-01T00:00:00Z", "ends_at" to "2027-06-30T00:00:00Z"),
            obj("id" to "2", "user_id" to "b", "role_id" to "equipment_officer", "starts_at" to "2026-01-01T00:00:00Z", "ends_at" to "2027-06-30T00:00:00Z"),
            obj("id" to "3", "user_id" to "c", "role_id" to "equipment_officer", "starts_at" to "2026-01-01T00:00:00Z", "ends_at" to "2026-02-01T00:00:00Z"),
            obj("id" to "4", "user_id" to "a", "role_id" to "president", "starts_at" to "2026-01-01T00:00:00Z", "ends_at" to "2027-06-30T00:00:00Z", "retired_at" to "2026-03-01T00:00:00Z"),
            obj("id" to "5", "user_id" to "d", "role_id" to "administrator", "starts_at" to "2026-01-01T00:00:00Z", "ends_at" to "2027-06-30T00:00:00Z")),
        "profiles" to listOf(obj("id" to "a", "full_name" to "Ana Cruz"), obj("id" to "b", "full_name" to "Ben Uy"), obj("id" to "d", "full_name" to "Dee Lim")))

    @Test fun roleViewsCountOnlyCurrentHoldersAndKeepDeskOrder() {
        val roles = roleViews(rolesWorkspace(), rolesNow)
        val armorer = roles.first()
        assertEquals("equipment_officer", armorer.id)
        assertEquals(listOf("Ana Cruz", "Ben Uy"), armorer.holders)
        assertEquals(listOf("equipment", "training"), armorer.permissions)
        assertTrue(armorer.changedFromDefault)
        assertEquals(emptyList<String>(), roles.first { it.id == "president" }.holders)
        assertNull(roles.first { it.id == "range_marshal" }.defaults)
    }

    @Test fun roleIdsComeFromTheNameAndNeverClash() {
        assertEquals("range_marshal", roleIdFrom("  Range Marshal! ", emptySet()))
        assertEquals("range_marshal_2", roleIdFrom("Range marshal", setOf("range_marshal")))
        assertEquals("role_2nd_coach", roleIdFrom("2nd coach", emptySet()))
        assertEquals("role", roleIdFrom("!!", emptySet()))
    }

    @Test fun roleChangeListsAddedAndRemovedDesks() {
        val c = roleChange(listOf("equipment"), setOf("training", "activities"))
        assertEquals(listOf("activities", "training"), c.added)
        assertEquals(listOf("equipment"), c.removed)
        assertTrue(roleChange(listOf("equipment"), setOf("equipment")).none)
    }

    @Test fun warningsOnlyWhenADeskIsLeftWithNobody() {
        val roles = roleViews(rolesWorkspace(), rolesNow)
        val admin = roleWarnings(roles, "administrator", emptySet())
        assertEquals(Severity.BLOCK, admin.single().severity)
        // Nobody holds President right now, so removing Fund approval breaks nothing new.
        assertTrue(roleWarnings(roles, "president", emptySet()).isEmpty())
        val held = roleViews(rolesWorkspace(), rolesNow).map { if (it.id == "equipment_officer") it.copy(permissions = listOf("equipment", "president")) else it }
        val w = roleWarnings(held, "equipment_officer", setOf("equipment"))
        assertEquals(Severity.WARN, w.single().severity)
        assertTrue(w.single().text.contains("approve fund releases"))
        assertTrue(roleWarnings(roles, "equipment_officer", setOf("equipment", "training", "finance")).isEmpty())
    }

    // ---------------------------------------------------------------- Officer home

    @Test fun deskTilesShowEveryDeskEvenWhenNothingWaits() {
        val w = ws("borrowings" to listOf(obj("id" to "b1", "status" to "pending", "user_id" to "m1", "equipment_id" to "e1", "starts_at" to "2026-11-14T01:00:00Z", "ends_at" to "2026-11-14T05:00:00Z")),
            "equipment" to listOf(obj("id" to "e1", "label" to "Recurve 24 lb")), "profiles" to listOf(obj("id" to "m1", "full_name" to "Ana Cruz")))
        val tiles = deskTiles(w, setOf("training", "equipment"), rolesNow)
        assertEquals(listOf("Equipment", "Coach desk"), tiles.map { it.title })
        assertEquals(1, tiles[0].waiting)
        assertEquals("1 borrowing requests", tiles[0].detail)
        assertEquals(0, tiles[1].waiting)
        assertEquals("Check training rounds", tiles[1].detail)
        assertEquals("training_desk", tiles[1].route)
        // Fund approval shares the finance desk: one tile, not two.
        assertEquals(listOf("Finance desk"), deskTiles(ws(), setOf("finance", "president"), rolesNow).map { it.title })
        val focus = officerFocus(w, setOf("training", "equipment"), rolesNow)!!
        assertEquals("Ana Cruz", focus.title)
        assertTrue(focus.detail.startsWith("Recurve 24 lb · from Nov 14"))
        assertNull(officerFocus(ws(), setOf("equipment"), rolesNow))
    }

    @Test fun focusPicksTheApplicationWaitingLongest() {
        val w = ws("applications" to listOf(
            obj("id" to "a1", "user_id" to "x", "full_name" to "Late Applicant", "status" to "pending", "submitted_at" to "2026-11-11T00:00:00Z"),
            obj("id" to "a2", "user_id" to "y", "full_name" to "Early Applicant", "status" to "under_review", "submitted_at" to "2026-11-09T00:00:00Z")))
        val f = officerFocus(w, setOf("membership"), rolesNow)!!
        assertEquals("Early Applicant", f.title)
        assertEquals("Review application", f.action)
        assertTrue(f.detail.contains("1 more"))
    }

    // ---------------------------------------------------------------- Club setup

    private fun settings(vararg extra: Pair<String, JsonElement>) = JsonObject(mapOf<String, JsonElement>(
        "club_name" to JsonPrimitive("DLSU Archery Club"), "membership_fee" to JsonPrimitive("800"), "rules_validated" to JsonPrimitive("false"),
        "retention_policy" to JsonPrimitive("Awaiting club validation"),
        "membership_categories" to JsonArray(listOf(JsonPrimitive("Undergraduate"))),
        "application_requirements" to JsonArray(emptyList()), "renewal_requirements" to JsonArray(emptyList())) + extra)

    @Test fun setupChecklistFlagsWhatIsMissingAndWhoOwnsIt() {
        val lines = setupChecklist(settings()).associateBy { it.key }
        assertTrue(!lines.getValue("application_requirements").done)
        assertTrue(!lines.getValue("retention_policy").done)
        assertTrue(!lines.getValue("payment_details").done)
        assertEquals("Treasurer · Admin", lines.getValue("payment_details").ownerLabel)
        assertEquals("Admin", lines.getValue("club_name").ownerLabel)
        assertTrue(lines.getValue("membership_fee").canEdit(setOf("finance")))
        assertTrue(!lines.getValue("club_name").canEdit(setOf("finance")))
        val free = setupChecklist(settings("membership_fee" to JsonPrimitive("0"))).first { it.key == "payment_details" }
        assertTrue(free.done)
        val paid = setupChecklist(settings("payment_details" to JsonObject(mapOf("gcash_number" to JsonPrimitive("0917"))))).first { it.key == "payment_details" }
        assertEquals("GCash 0917", paid.detail)
    }

    @Test fun configurePayloadKeepsEverythingElse() {
        val s = settings("rules_validated" to JsonPrimitive("true"))
        val p = configureClubPayload(s, mapOf("club_name" to JsonPrimitive("New name")))
        assertEquals("New name", p.text("club_name"))
        assertEquals("true", p.text("rules_validated")) // editing one part must not un-confirm the rules
        assertEquals("Undergraduate", (p["membership_categories"] as JsonArray).map { (it as JsonPrimitive).content }.single())
        assertEquals("Awaiting club validation", p.text("retention_policy"))
    }

    @Test fun suggestionsSkipWhatTheClubAlreadyHas() {
        assertEquals(listOf("Graduate", "Alumni", "Faculty/Staff"), setupSuggestions("membership_categories", listOf("undergraduate")))
        assertTrue(setupSuggestions("club_name", emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------- Coach CSV import

    private fun coachWs() = ws("member_directory" to listOf(obj("user_id" to "u1", "full_name" to "Ana Cruz", "status" to "Active"),
        obj("user_id" to "u2", "full_name" to "Ben Uy", "status" to "Active"), obj("user_id" to "u3", "full_name" to "Old Member", "status" to "Inactive")))

    @Test fun coachImportMatchesMembersByNameAndBuildsServerRows() {
        val csv = "Member,Date,Distance,Target face,Bow,Round,Score,Max score,Notes\nana cruz,2026-11-12,18 m,80 cm,Recurve,WA 360,286,360,\"Windy, gusts\"\n"
        val r = coachImport(csv, coachWs())
        assertTrue(r.problems.isEmpty())
        val row = r.rows.single()
        assertEquals("u1", row.text("user_id"))
        assertEquals("18", row.text("distance"))
        assertEquals("Windy, gusts", row.text("observations"))
        assertEquals("csv:u1:2026-11-12:WA 360:286", row.text("import_key"))
        assertEquals("Ana Cruz · 286/360 · WA 360 · 2026-11-12", r.preview.single())
    }

    @Test fun coachImportExplainsWhatToFix() {
        assertEquals("Add the missing column: max_score.", coachImport("member,date,distance,target_face,bow,round,score\nAna Cruz,2026-11-12,18,80 cm,Recurve,WA 360,286\n", coachWs()).problems.single())
        val r = coachImport("member,date,distance,target_face,bow,round,score,max_score\nOld Member,2026-11-12,18,80 cm,Recurve,WA 360,286,360\nBen Uy,12/11/2026,18,80 cm,Recurve,WA 360,286,360\nBen Uy,2026-11-12,18,80 cm,Recurve,WA 360,400,360\n", coachWs())
        assertEquals(listOf("Row 2: \"Old Member\" is not an active member.", "Row 3: date must look like 2026-11-12.", "Row 4: score must be between 0 and the max score."), r.problems)
        assertTrue(coachCsvTemplate("Ana Cruz", LocalDate.parse("2026-11-12")).lines()[1].startsWith("Ana Cruz,2026-11-12,18,80 cm"))
    }

    // ---------------------------------------------------------------- Arrow exports from scoring apps

    private val arrowCsv = "\"Title\";\"Date\";\"Standard round\";\"Indoor\";\"Bow\";\"Arrow\";\"Round\";\"Distance\";\"Target\";\"End\";\"Timestamp\";\"Points\";\"Arrow numbers\";\"x\";\"y\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"1\";\"17:23:30.728\";\"9\";\"\";\"0.10574722\";\"0.03908038\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"1\";\"17:23:30.728\";\"6\";\"\";\"0.006896615\";\"0.4137931\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"1\";\"17:23:30.728\";\"6\";\"\";\"0.018390894\";\"0.43784785\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"1\";\"17:23:30.728\";\"6\";\"\";\"-0.24482757\";\"0.34482765\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"1\";\"17:23:30.728\";\"5\";\"\";\"-0.36781603\";\"0.41149426\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"1\";\"17:23:30.728\";\"4\";\"\";\"-0.27816087\";\"0.5793104\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"2\";\"17:35:14.906\";\"9\";\"\";\"0.06206906\";\"0.17701149\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"2\";\"17:35:14.906\";\"9\";\"\";\"-0.14942527\";\"0.14712644\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"2\";\"17:35:14.906\";\"8\";\"\";\"0.1448276\";\"0.27356315\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"2\";\"17:35:14.906\";\"7\";\"\";\"-0.1252873\";\"0.30574703\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"2\";\"17:35:14.906\";\"6\";\"\";\"-0.016091943\";\"0.4666667\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"2\";\"17:35:14.906\";\"5\";\"\";\"-0.2712643\";\"0.4527905\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"3\";\"17:53:22.218\";\"6\";\"\";\"0.03678167\";\"0.43908048\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"3\";\"17:53:22.218\";\"6\";\"\";\"0.19310355\";\"0.4000001\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"3\";\"17:53:22.218\";\"5\";\"\";\"0.17471266\";\"0.5747125\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"3\";\"17:53:22.218\";\"5\";\"\";\"0.009195447\";\"0.60689664\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"3\";\"17:53:22.218\";\"4\";\"\";\"-0.08045304\";\"0.6505747\"\n\"Final Year T1 Week 9\";\"2026-11-05\";\"Practice\";\"Indoor\";\"Fortified Recurve\";\"Happy Archers Super Carbon Arrow\";\"1\";\"18m\";\"WA Full (40cm)\";\"3\";\"17:53:22.218\";\"M\";\"\";\"-0.29426628\";\"1.0712643\"\n"

    @Test fun arrowExportBecomesOneRoundWithEndTotals() {
        assertTrue(isArrowExport(arrowCsv))
        assertTrue(!isArrowExport("member,date,distance\nAna,2026-11-12,18\n"))
        val r = arrowImport(arrowCsv, "u1", "Ana Cruz")
        assertTrue(r.problems.isEmpty())
        val row = r.rows.single()
        assertEquals("106", row.text("score"))
        assertEquals("180", row.text("maximum_score"))
        assertEquals("18", row.text("distance"))
        assertEquals("WA Full (40cm)", row.text("target_face"))
        assertEquals("Recurve", row.text("category"))
        assertEquals("Practice · 18 arrows", row.text("scoring_format"))
        assertEquals("2026-11-05", row.text("training_date"))
        assertEquals("Final Year T1 Week 9 · Indoor · ends 36, 44, 26", row.text("observations"))
        assertEquals("Ana Cruz · 106/180 · 18 arrows, 3 ends · 2026-11-05", r.preview.single())
        assertEquals(10, arrowPoints("X")); assertEquals(0, arrowPoints("M"))
    }

    @Test fun importedArrowsGiveEndsStatsAndGroupCentre() {
        val row = arrowImport(arrowCsv, "u1", "Ana Cruz").rows.single()
        val ends = roundArrows(row)
        assertEquals(listOf(6, 6, 6), ends.map { it.size })
        assertEquals("M", ends[2][5].p)
        val st = roundStats(ends)
        assertEquals(106, st.total); assertEquals(180, st.max)
        assertEquals(listOf(36, 44, 26), st.endTotals)
        assertEquals(17, st.hits); assertEquals(1, st.misses); assertEquals(17, st.plotted)
        assertEquals(listOf("9" to 3, "8" to 1, "7" to 1, "6" to 6, "5" to 4, "4" to 2, "M" to 1), st.counts)
        assertEquals("low and slightly left", groupDescription(st.centre))
        assertEquals("Dropped 3.0 points per arrow from end 2 to end 3.", endTrend(st))
        assertEquals(ends, roundArrows(JsonObject(mapOf("arrows" to arrowsJson(ends)))))
        assertEquals("centred", groupDescription(0.02 to -0.02))
        assertEquals("slightly high", groupDescription(0.0 to -0.05))
        assertTrue(roundArrows(obj("id" to "r")).isEmpty())
    }

    @Test fun tapPositionsScoreLikeTheFace() {
        assertEquals("X", scoreAt(0.02, 0.03))
        assertEquals("10", scoreAt(0.07, 0.0))
        assertEquals("9", scoreAt(0.106, 0.039))
        assertEquals("5", scoreAt(-0.368, 0.411))
        assertEquals("4", scoreAt(-0.278, 0.579))
        assertEquals("M", scoreAt(-0.294, 1.071))
        // the scoring app's own points agree with ours for every arrow in the export
        roundArrows(arrowImport(arrowCsv, "u1", "Ana").rows.single()).flatten().forEach { a -> assertEquals(a.p, scoreAt(a.x!!, a.y!!)) }
        val ends = listOf(listOf(Arrow("9", 0.1, 0.2), Arrow("M")), listOf(Arrow("X")))
        assertEquals(ends, decodeEnds(encodeEnds(ends)))
        assertTrue(groupSpread(listOf(Arrow("9", 0.0, 0.0), Arrow("9", 0.2, 0.0)))!!.first > 0.09)
    }
}
