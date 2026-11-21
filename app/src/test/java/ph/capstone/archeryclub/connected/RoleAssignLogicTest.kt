package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RoleAssignLogicTest {
    private val now = Instant.parse("2026-11-19T12:00:00Z")
    private fun ws(standing: Boolean = true) = buildJsonObject {
        put("roles", JsonArray(listOf(
            buildJsonObject { put("id", "administrator"); put("name", "Administrator"); put("permissions", JsonArray(listOf(JsonPrimitive("administration")))) },
            buildJsonObject { put("id", "interclub_officer"); put("name", "Interclub officer"); put("permissions", JsonArray(listOf(JsonPrimitive("interclub")))) },
            buildJsonObject { put("id", "activity_officer"); put("name", "Activity officer"); put("permissions", JsonArray(listOf(JsonPrimitive("activities")))) })))
        put("role_assignments", JsonArray(listOf(
            buildJsonObject { put("user_id", "rel"); put("role_id", "activity_officer"); put("ends_at", "2026-12-31T15:00:00Z") },
            buildJsonObject { put("user_id", "rel"); put("role_id", "interclub_officer"); put("ends_at", "2026-11-01T00:00:00Z") })))
        if (standing) put("member_standing", JsonArray(listOf(buildJsonObject { put("user_id", "rel"); put("status", "Active") },
            buildJsonObject { put("user_id", "pres"); put("status", "Pending payment") })))
    }

    @Test fun choicesFollowTheServerRules() {
        val rel = roleChoices(ws(), "rel", now).associateBy { it.id }
        assertEquals("Already has this role", rel.getValue("activity_officer").note)
        assertEquals(true, rel.getValue("interclub_officer").enabled) // an expired term can be given again
        val stranger = roleChoices(ws(), "nobody", now).associateBy { it.id }
        assertEquals("Members only", stranger.getValue("interclub_officer").note)
        assertEquals("Members only", stranger.getValue("administrator").note) // no exception since migration 042
        assertEquals("Members only", roleChoices(ws(), "pres", now).first { it.id == "activity_officer" }.note)
        // An older database without standings: don't block on a guess; the server still checks.
        assertEquals(true, roleChoices(ws(false), "nobody", now).first { it.id == "activity_officer" }.enabled)
    }

    @Test fun labelsAndSummary() {
        assertNull(memberStandings(ws(false)))
        assertEquals("Active member", standingLabel(memberStandings(ws())!!["rel"]))
        assertEquals("membership pending payment", standingLabel("Pending payment")); assertEquals("not a member", standingLabel(null))
        assertEquals("Test Relation now has 2 new roles: Activity officer, Interclub officer.", assignedSummary("Test Relation", listOf("Activity officer", "Interclub officer"), emptyList()))
        assertEquals("Ana now has 1 new role: Treasurer. Coach wasn't assigned: Officer roles can only go to Active members.",
            assignedSummary("Ana", listOf("Treasurer"), listOf("Coach" to "Officer roles can only go to Active members.")))
    }

    @Test fun theDeveloperAccountCanHoldRolesForTesting() {
        val w = buildJsonObject { ws().forEach { (k, v) -> if (k != "member_standing") put(k, v) }
            put("member_standing", JsonArray(listOf(buildJsonObject { put("user_id", "dev"); put("status", DEVELOPER) }))) }
        assertEquals(true, roleChoices(w, "dev", now).first { it.id == "interclub_officer" }.enabled)
        assertEquals("developer account (testing)", standingLabel(DEVELOPER))
        assertEquals(false, canHoldRoles("Pending payment")); assertEquals(true, canHoldRoles("Active"))
    }
}
