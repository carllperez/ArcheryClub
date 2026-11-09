package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WorkflowLogicTest {
    private val now = Instant.parse("2026-11-10T00:00:00Z")
    private fun at(hours: Long) = now.plusSeconds(hours * 3600).toString()
    private fun event(id: String, startH: Long = 48, closeH: Long = 36, openH: Long = -24, capacity: Int = 10, published: Boolean = true) = buildJsonObject {
        put("id", id); put("title", id); put("starts_at", at(startH)); put("ends_at", at(startH + 2))
        put("registration_opens", at(openH)); put("registration_closes", at(closeH)); put("capacity", capacity)
        put("published", published); put("archived", false)
    }
    private fun ws(events: List<JsonObject>, regs: List<Pair<String, String>> = emptyList(), wait: List<Pair<String, String>> = emptyList(),
                   attendance: List<Triple<String, String, String>> = emptyList(), stats: List<JsonObject> = emptyList()) = buildJsonObject {
        put("user_id", "me"); put("events", JsonArray(events))
        put("registrations", JsonArray(regs.map { (e, u) -> buildJsonObject { put("event_id", e); put("user_id", u) } }))
        put("waitlist", JsonArray(wait.map { (e, u) -> buildJsonObject { put("event_id", e); put("user_id", u) } }))
        put("attendance", JsonArray(attendance.map { (e, u, s) -> buildJsonObject { put("event_id", e); put("user_id", u); put("status", s) } }))
        put("event_stats", JsonArray(stats))
    }

    @Test fun postgresTimestampsWithOffsetsParse() {
        assertEquals(Instant.parse("2026-11-10T01:00:00Z"), instantOf("2026-11-10T09:00:00+08:00"))
        assertEquals(Instant.parse("2026-11-10T01:00:00.123456Z"), instantOf("2026-11-10T01:00:00.123456+00:00"))
        assertNull(instantOf("")); assertNull(instantOf("not a date"))
    }

    @Test fun closedRegistrationsAreHiddenUnlessTheMemberHoldsAPlace() {
        val w = ws(listOf(event("open"), event("closed", startH = 10, closeH = -1), event("mine-closed", startH = 10, closeH = -1), event("draft", published = false)),
            regs = listOf("mine-closed" to "me"))
        val ids = memberActivities(w, now).map { it.id }
        assertEquals(listOf("mine-closed", "open"), ids)
        assertEquals(ActivityStatus.REGISTERED, memberActivities(w, now).first().status)
    }

    @Test fun statusFollowsDeadlineCapacityAndOwnRecords() {
        assertEquals(ActivityStatus.OPEN, activityView(event("a"), ws(listOf()), now).status)
        assertEquals(ActivityStatus.CLOSING_SOON, activityView(event("a", closeH = 5), ws(listOf()), now).status)
        assertEquals(ActivityStatus.NOT_YET_OPEN, activityView(event("a", openH = 3), ws(listOf()), now).status)
        assertEquals(ActivityStatus.CLOSED, activityView(event("a", startH = 10, closeH = -1), ws(listOf()), now).status)
        val full = ws(listOf(), stats = listOf(buildJsonObject { put("event_id", "a"); put("registered", 2); put("waitlisted", 0) }))
        val v = activityView(event("a", capacity = 2), full, now)
        assertEquals(ActivityStatus.FULL, v.status); assertTrue(v.canRegister); assertEquals(0, v.spotsLeft)
        assertEquals(ActivityStatus.WAITLISTED, activityView(event("a"), ws(listOf(), wait = listOf("a" to "me")), now).status)
        assertEquals(ActivityStatus.ATTENDED, activityView(event("a", startH = -5), ws(listOf(), regs = listOf("a" to "me"), attendance = listOf(Triple("a", "me", "present"))), now).status)
        assertEquals(ActivityStatus.MISSED, activityView(event("a", startH = -5), ws(listOf(), regs = listOf("a" to "me")), now).status)
        assertFalse(activityView(event("a", startH = 10, closeH = -1), ws(listOf()), now).canRegister)
    }

    @Test fun participationRateIgnoresExcusedAbsences() {
        val events = listOf(event("p", startH = -50), event("x", startH = -40), event("m", startH = -30))
        val w = ws(events, regs = events.map { it.text("id") to "me" },
            attendance = listOf(Triple("p", "me", "present"), Triple("x", "me", "excused"), Triple("m", "me", "absent")))
        val s = participationSummary(w, now)
        assertEquals(1, s.attended); assertEquals(1, s.excused); assertEquals(1, s.missed); assertEquals(50, s.rate)
    }

    @Test fun borrowingChecksMirrorServerCompatibilityRules() {
        val bow = buildJsonObject { put("condition", "serviceable"); put("handedness", "right"); put("draw_weight", 28) }
        val start = now.plusSeconds(3600); val end = now.plusSeconds(7200)
        assertTrue(borrowingIssues(bow, "right", 30.0, start, end, now).isEmpty())
        assertTrue(borrowingIssues(bow, "left", 30.0, start, end, now).any { it.contains("right-handed") })
        assertTrue(borrowingIssues(bow, "right", 24.0, start, end, now).any { it.contains("above your limit") })
        assertTrue(borrowingIssues(bow, "right", null, end, start, now).any { it.contains("after pick-up") })
        assertTrue(borrowingIssues(bow, "right", null, start, start.plusSeconds(15 * 86400L), now).any { it.contains("14 days") })
        assertEquals(listOf("returned"), borrowingNextSteps("released")); assertTrue(borrowingNextSteps("returned").isEmpty())
    }

    @Test fun profileValidationMatchesServer() {
        val ok = mapOf("full_name" to "Alex", "phone" to "0917 123 4567", "emergency_contact_name" to "Pat", "emergency_contact_phone" to "09181234567", "draw_weight" to "26")
        assertTrue(profileErrors(ok).isEmpty())
        assertTrue("phone" in profileErrors(ok + ("phone" to "call me")))
        assertTrue("emergency_contact_phone" in profileErrors(ok + ("emergency_contact_phone" to "")))
        assertTrue("draw_weight" in profileErrors(ok + ("draw_weight" to "120")))
        assertTrue("full_name" in profileErrors(ok + ("full_name" to " ")))
    }

    @Test fun officerQueuesCountOnlyActionableRecords() {
        val w = buildJsonObject {
            put("user_id", "officer")
            put("borrowings", JsonArray(listOf(
                buildJsonObject { put("status", "pending") },
                buildJsonObject { put("status", "released"); put("ends_at", at(-2)) },
                buildJsonObject { put("status", "returned"); put("ends_at", at(-2)) })))
        }
        val q = officerQueues(w, setOf("equipment"), now).associate { it.label to it.count }
        assertEquals(1, q["Borrowing requests"]); assertEquals(1, q["Overdue returns"]); assertEquals(0, q["Ready to hand over"])
    }

    @Test fun nameLocksOnceAMembershipExistsAndCorrectionsReachTheQueue() {
        val applicant = buildJsonObject { put("user_id", "me"); put("memberships", JsonArray(emptyList())) }
        assertFalse(nameLocked(applicant))
        val member = buildJsonObject {
            put("user_id", "me"); put("memberships", JsonArray(listOf(buildJsonObject { put("user_id", "me") })))
            put("name_corrections", JsonArray(listOf(
                buildJsonObject { put("user_id", "me"); put("status", "pending"); put("requested_name", "Alexandra") },
                buildJsonObject { put("user_id", "other"); put("status", "pending") },
                buildJsonObject { put("user_id", "other"); put("status", "approved") })))
        }
        assertTrue(nameLocked(member))
        assertEquals("Alexandra", pendingNameCorrection(member)?.text("requested_name"))
        val q = officerQueues(member, setOf("membership"), now).associate { it.label to it.count }
        assertEquals(1, q["Name corrections"])
    }

    @Test fun oneAttendanceRateDefinitionExcludesExcused() {
        assertEquals(50, attendanceRate(present = 1, registered = 3, excused = 1))
        assertNull(attendanceRate(0, 2, 2))
        val events = listOf(event("p", startH = -50), event("x", startH = -40), event("m", startH = -30))
        val w = ws(events, regs = events.map { it.text("id") to "me" },
            attendance = listOf(Triple("p", "me", "present"), Triple("x", "me", "excused"), Triple("m", "me", "absent")))
        assertEquals(attendanceRate(1, 3, 1), participationSummary(w, now).rate)
    }

    @Test fun noShowsOverdueAndWalkInWindow() {
        val w = buildJsonObject {
            put("user_id", "me")
            put("events", JsonArray(listOf(event("a", startH = -100), event("b", startH = -50), event("old", startH = -24 * 90))))
            put("attendance", JsonArray(listOf("a", "b", "old").map { e -> buildJsonObject { put("event_id", e); put("user_id", "m1"); put("status", "absent") } }))
            put("borrowings", JsonArray(listOf(buildJsonObject { put("user_id", "me"); put("status", "released"); put("ends_at", at(-1)) })))
        }
        assertEquals(2, recentNoShows(w, "m1", now))
        assertTrue(hasOverdueItem(w, now))
        assertTrue(walkInWindowOpen(event("live", startH = 1), now))
        assertFalse(walkInWindowOpen(event("later", startH = 10), now))
        assertFalse(walkInWindowOpen(event("long-ago", startH = -50), now))
    }

    @Test fun adminsSeeExpiringAndExpiredRoles() {
        val w = buildJsonObject {
            put("user_id", "admin")
            put("role_assignments", JsonArray(listOf(
                buildJsonObject { put("ends_at", at(24 * 5)) },
                buildJsonObject { put("ends_at", at(-24 * 8)) },
                buildJsonObject { put("ends_at", at(24 * 200)) })))
        }
        val q = officerQueues(w, setOf("administration"), now).associate { it.label to it.count }
        assertEquals(1, q["Roles expiring within 14 days"]); assertEquals(1, q["Expired roles to renew"])
    }
}
