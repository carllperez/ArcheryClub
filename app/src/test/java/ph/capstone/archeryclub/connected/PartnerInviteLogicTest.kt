package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PartnerInviteLogicTest {
    @Test fun codeBoxFormatsAsYouType() {
        assertEquals("7KQ2", inviteCodeInput("7kq2"))
        assertEquals("7KQ2-9F01", inviteCodeInput("7kq2 9f01"))
        assertEquals("7KQ2-9F01", inviteCodeInput("7KQ2-9F01-EXTRA"))
        assertEquals("7KQ2-9F", inviteCodeInput("7k#q2/9f"))
        assertTrue(validInviteCode("7KQ2-9F01")); assertEquals(false, validInviteCode("7KQ2-9F0"))
    }

    @Test fun claimFormNeedsCodeNameSchoolAndMobile() {
        val e = partnerClaimErrors("7KQ2", "", "U", "8123 4567")
        assertEquals(setOf("code", "full_name", "university", "phone"), e.keys)
        assertTrue(partnerClaimErrors("7KQ2-9F01", "Uma Partner", "UP Diliman", "0917 123 4567").isEmpty())
    }

    @Test fun inviteStatesAndPartnerEvents() {
        val now = Instant.parse("2026-11-20T12:00:00Z")
        fun inv(id: String, claimed: String = "", revoked: String = "", expires: String = "2026-11-25T00:00:00Z") = buildJsonObject {
            put("id", id); put("code", "AAAA-000$id"); put("event_id", "e1"); put("partner_id", "p1"); put("created_at", "2026-11-20T0$id:00:00Z")
            put("claimed_by", claimed); put("revoked_at", revoked); put("expires_at", expires)
        }
        val ws = buildJsonObject {
            put("user_id", "rep")
            put("partner_invites", JsonArray(listOf(inv("1", claimed = "rep"), inv("2", revoked = "2026-11-20T03:00:00Z"), inv("3", expires = "2026-11-19T00:00:00Z"), inv("4"))))
            put("partner_clubs", JsonArray(listOf(buildJsonObject { put("id", "p1"); put("name", "UP Archery Club") })))
            put("event_access", JsonArray(listOf(buildJsonObject { put("event_id", "e1"); put("user_id", "rep"); put("revoked_at", "") })))
            put("interclub_events", JsonArray(listOf(buildJsonObject { put("event_id", "e1"); put("shared_start", "2026-11-28T01:00:00Z") },
                buildJsonObject { put("event_id", "e2"); put("shared_start", "2026-11-29T01:00:00Z") })))
        }
        val v = inviteViews(ws, "e1", now) { if (it == "rep") "Uma Partner" else it }
        assertEquals(listOf("4", "3", "2", "1"), v.map { it.id })
        assertEquals(listOf(InviteState.WAITING, InviteState.EXPIRED, InviteState.CANCELLED, InviteState.USED), v.map { it.state })
        assertEquals("Uma Partner", v.last().claimedBy); assertEquals("UP Archery Club", v.first().partnerName)
        assertEquals(listOf("e1"), partnerEvents(ws).map { it.text("event_id") })
        assertTrue(inviteMessage("DLSU-UP Friendly Shoot", "UP Archery Club", "7KQ2-9F01", "Wed, Nov 25").contains("7KQ2-9F01"))
    }

    @Test fun aUsedCodeShowsTheNameTheRepGave() {
        val now = java.time.Instant.parse("2026-11-21T00:00:00Z")
        val w = buildJsonObject {
            put("partner_clubs", JsonArray(listOf(buildJsonObject { put("id", "up"); put("name", "UP Archery Club") })))
            put("partner_invites", JsonArray(listOf(buildJsonObject { put("id", "i"); put("code", "DAA2-2516"); put("event_id", "e"); put("partner_id", "up")
                put("claimed_by", "u"); put("claimed_name", "Uma Reyes"); put("expires_at", "2026-11-25T00:00:00Z") })))
        }
        assertEquals("Uma Reyes", inviteViews(w, "e", now) { "Club member" }.single().claimedBy)
    }

    @Test fun aRepresentativesProfileNamesTheirClub() {
        val w = buildJsonObject {
            put("user_id", "rep")
            put("partner_clubs", JsonArray(listOf(buildJsonObject { put("id", "up"); put("name", "UP Archery Club (UP Diliman)") })))
            put("event_access", JsonArray(listOf(buildJsonObject { put("user_id", "rep"); put("partner_id", "up"); put("event_id", "e1") },
                buildJsonObject { put("user_id", "rep"); put("partner_id", "up"); put("event_id", "e2") },
                buildJsonObject { put("user_id", "other"); put("partner_id", "x"); put("event_id", "e1") })))
        }
        assertEquals(listOf("UP Archery Club (UP Diliman)"), partnerClubNames(w))
    }
}
