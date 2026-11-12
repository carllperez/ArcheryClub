package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RegistrationLogicTest {
    private fun doc(id: String, requirement: String, verified: Boolean = false, superseded: String = "", record: String = "app1") = buildJsonObject {
        put("id", id); put("module", 2); put("record_id", record); put("requirement", requirement); put("name", "$id.pdf"); put("path", "p/$id")
        put("created_at", "2026-11-0${id.last()}T00:00:00Z")
        if (verified) put("verified_by", "officer"); if (superseded.isNotBlank()) put("superseded_by", superseded)
    }
    private fun ws(status: String?, docs: List<JsonObject> = emptyList(), requirements: List<String> = listOf("School ID", "Medical clearance"), owner: String = "me") = buildJsonObject {
        put("user_id", "me")
        put("settings", JsonArray(listOf(buildJsonObject { put("application_requirements", JsonArray(requirements.map { JsonPrimitive(it) })) })))
        put("applications", JsonArray(if (status == null) emptyList() else listOf(buildJsonObject {
            put("id", "app1"); put("user_id", owner); put("status", status); put("full_name", "Ana Cruz"); put("version", 3); put("submitted_at", "2026-11-05T00:00:00Z")
        })))
        put("documents", JsonArray(docs))
    }

    @Test fun signUpChecksEmailPasswordStrengthAndMatch() {
        assertTrue(signUpErrors("ana@dlsu.edu.ph", "arrows2026", "arrows2026").isEmpty())
        assertEquals(setOf("email", "password", "confirm"), signUpErrors("ana@", "short", "other").keys)
        assertEquals("Use letters and at least one number.", signUpErrors("a@b.co", "onlyletters", "onlyletters")["password"])
        assertTrue("surrounding spaces are trimmed", signUpErrors("  ana@dlsu.edu.ph ", "arrows2026", "arrows2026").isEmpty())
    }

    @Test fun stagesMapServerStatusesAndLocking() {
        assertEquals(ApplicationStage.NOT_STARTED, applicationStage(""))
        assertEquals(ApplicationStage.NEEDS_CHANGES, applicationStage("returned_for_correction"))
        assertEquals(ApplicationStage.NEEDS_CHANGES, applicationStage("incomplete"))
        assertTrue(ApplicationStage.NEEDS_CHANGES.editable)
        assertFalse(ApplicationStage.SUBMITTED.editable)
        assertTrue(ApplicationStage.UNDER_REVIEW.decidable)
        assertFalse(ApplicationStage.APPROVED.decidable)
    }

    @Test fun requirementsTrackMissingUploadedVerifiedAndIgnoreReplacedFiles() {
        val v = applicationView(ws("draft", listOf(doc("d1", "School ID", superseded = "d2"), doc("d2", "School ID", verified = true))))
        assertEquals(RequirementState.VERIFIED, v.requirements[0].state)
        assertEquals(1, v.requirements[0].documents.size)
        assertEquals(RequirementState.MISSING, v.requirements[1].state)
        assertEquals(listOf("Medical clearance"), v.missing)
        val partial = applicationView(ws("pending", listOf(doc("d3", "School ID"), doc("d4", "Medical clearance", verified = true))))
        assertEquals(RequirementState.UPLOADED, partial.requirements[0].state)
        assertEquals(1, partial.unverified)
    }

    @Test fun submitExplainsEveryBlockerAndClearsWhenReady() {
        val none = applicationView(ws(null))
        val b = submitBlockers(none, mapOf("full_name" to ""), confirmed = false)
        assertTrue(b.any { it.startsWith("Enter your full name") })
        assertTrue("documents need a saved draft first", b.any { it.startsWith("Save your details first") })
        assertTrue(b.any { it.startsWith("Confirm") })
        val ready = applicationView(ws("draft", listOf(doc("d1", "School ID"), doc("d2", "Medical clearance"))))
        assertEquals(emptyList<String>(), submitBlockers(ready, mapOf("full_name" to "Ana Cruz", "phone" to "0917 123 4567", "sex" to "Female", "college" to "CCS", "program" to "BSIT", "year_level" to "2nd year"), confirmed = true))
        assertEquals(listOf(MOBILE_HINT), submitBlockers(ready, mapOf("full_name" to "Ana", "phone" to "abc", "sex" to "Female", "college" to "CCS", "program" to "BSIT", "year_level" to "2nd year"), confirmed = true))
        assertEquals(listOf(MOBILE_HINT), submitBlockers(ready, mapOf("full_name" to "Ana", "phone" to "0917 123", "sex" to "Female", "college" to "CCS", "program" to "BSIT", "year_level" to "2nd year"), confirmed = true))
        assertTrue(validMobile("+63 917-123-4567")); assertTrue(validMobile("09171234567")); assertTrue(!validMobile("0817 123 4567")); assertTrue(!validMobile("123-4567"))
        val locked = applicationView(ws("pending"))
        assertEquals(1, submitBlockers(locked, mapOf("full_name" to "Ana"), true).size)
    }

    private fun officerView(w: JsonObject) = applicationView(w, w.records("applications").first())

    @Test fun approvalNeedsEveryCurrentFileVerifiedAndAnotherOfficer() {
        val waiting = officerView(ws("pending", listOf(doc("d1", "School ID", verified = true), doc("d2", "Medical clearance")), owner = "ana"))
        assertEquals(listOf("Verify 1 uploaded document first."), approvalBlockers(waiting, "officer"))
        val ready = officerView(ws("under_review", listOf(doc("d1", "School ID", verified = true), doc("d2", "Medical clearance", verified = true),
            doc("d0", "Medical clearance", superseded = "d2")), owner = "ana"))
        assertEquals(emptyList<String>(), approvalBlockers(ready, "officer"))
        assertTrue(approvalBlockers(ready, "ana").any { it.startsWith("Another membership officer") })
    }

    @Test fun queueExcludesOwnAndDecidedApplicationsOldestFirst() {
        val w = buildJsonObject {
            put("user_id", "officer"); put("documents", JsonArray(emptyList()))
            put("applications", JsonArray(listOf(
                buildJsonObject { put("id", "a"); put("user_id", "x"); put("status", "pending"); put("submitted_at", "2026-11-06T00:00:00Z") },
                buildJsonObject { put("id", "b"); put("user_id", "y"); put("status", "under_review"); put("submitted_at", "2026-11-02T00:00:00Z") },
                buildJsonObject { put("id", "c"); put("user_id", "officer"); put("status", "pending"); put("submitted_at", "2026-11-01T00:00:00Z") },
                buildJsonObject { put("id", "d"); put("user_id", "z"); put("status", "approved"); put("submitted_at", "2026-11-01T00:00:00Z") },
            )))
        }
        assertEquals(listOf("b", "a"), applicationsToReview(w).map { it.id })
        assertEquals(listOf("d"), decidedApplications(w).map { it.id })
        assertEquals("Not accepted", decisionLabel(ApplicationStage.REJECTED)); assertEquals("Returned", decisionLabel(applicationStage("incomplete")))
    }

    @Test fun milestonesPointAtTheNextStep() {
        val m = applicationMilestones(applicationView(ws("pending", listOf(doc("d1", "School ID"), doc("d2", "Medical clearance")))), "")
        assertEquals(listOf(true, true, false, false, false), m.map { it.done })
        assertTrue(m[2].current)
        val paid = applicationMilestones(applicationView(ws("approved", listOf(doc("d1", "School ID", true), doc("d2", "Medical clearance", true)))), "Active")
        assertTrue(paid.all { it.done })
    }
}
