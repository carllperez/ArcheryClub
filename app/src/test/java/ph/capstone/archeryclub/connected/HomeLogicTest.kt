package ph.capstone.archeryclub.connected

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HomeLogicTest {
    private val manila = ZoneId.of("Asia/Manila")
    private val now = Instant.parse("2026-11-10T15:00:00Z") // 11 PM Tuesday in Manila

    @Test fun countdownCountsCalendarDaysNotHours() {
        assertEquals("Tomorrow", countdownLabel(Instant.parse("2026-11-10T23:00:00Z"), now, manila)) // 7 AM Wednesday, 8 h away
        assertEquals("Today", countdownLabel(Instant.parse("2026-11-10T15:30:00Z"), now, manila))
        assertEquals("In 5 days", countdownLabel(Instant.parse("2026-11-15T01:00:00Z"), now, manila))
        assertEquals("Now", countdownLabel(Instant.parse("2026-11-10T14:00:00Z"), now, manila))
        assertEquals("", countdownLabel(null, now, manila))
    }

    @Test fun greetingAndFirstName() {
        assertEquals("Good morning", greetingFor(9)); assertEquals("Good afternoon", greetingFor(14)); assertEquals("Good evening", greetingFor(20))
        assertEquals("Alex", firstName("  Alex  Santos ")); assertEquals("", firstName("")); assertEquals("Good evening", hello(20, " ")); assertEquals("Good morning, Mia", hello(8, "Mia Cruz"))
    }

    @Test fun durationsReadCompactly() {
        val t = Instant.parse("2026-11-10T08:00:00Z")
        assertEquals("45m", durationLabel(t, t.plusSeconds(45 * 60)))
        assertEquals("2h 15m", durationLabel(t, t.plusSeconds(135 * 60)))
        assertEquals("3h", durationLabel(t.plusSeconds(3 * 3600), t))
        assertEquals("3d 4h", durationLabel(t, t.plusSeconds((3 * 24 + 4) * 3600L)))
    }

    @Test fun fitChecksHandAndWeightOnly() {
        assertEquals(emptyList<String>(), fitProblems("right", 24.0, "right", 24.0))
        assertEquals(listOf("Left-handed", "Heavier than your 24 lb"), fitProblems("left", 30.0, "right", 24.0))
        assertEquals(emptyList<String>(), fitProblems("", null, "right", 24.0))
        assertEquals(emptyList<String>(), fitProblems("left", 30.0, "", null))
    }

    @Test fun announcementsKeepReadOnesAndApprovalShowsOnce() {
        fun o(vararg p: Pair<String, String>) = kotlinx.serialization.json.JsonObject(p.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })
        fun arr(vararg x: kotlinx.serialization.json.JsonObject) = kotlinx.serialization.json.JsonArray(x.toList())
        val w = kotlinx.serialization.json.JsonObject(mapOf("user_id" to kotlinx.serialization.json.JsonPrimitive("me"),
            "announcements" to arr(o("id" to "a1", "title" to "Old", "created_at" to "2026-11-01T00:00:00Z"), o("id" to "a2", "title" to "New", "created_at" to "2026-11-12T00:00:00Z")),
            "announcement_reads" to arr(o("user_id" to "me", "announcement_id" to "a1"), o("user_id" to "someone", "announcement_id" to "a2")),
            "memberships" to arr(o("user_id" to "me", "status" to "Active")),
            "notifications" to arr(o("id" to "n1", "user_id" to "me", "module" to "1", "message" to "Membership fee verified. Your membership is now Active.", "notified_at" to "2026-11-12T00:00:00Z"),
                o("id" to "n2", "user_id" to "me", "module" to "12", "message" to "Payment checked"))))
        val views = announcementViews(w)
        assertEquals(listOf("New", "Old"), views.map { it.title }); assertEquals(listOf(false, true), views.map { it.read })
        assertEquals("n1", approvalNotice(w)?.text("id"))
    }

    @Test fun electionRowHidesAfterItsDeadline() {
        fun e(status: String, key: String, at: String) = kotlinx.serialization.json.JsonObject(mapOf("status" to kotlinx.serialization.json.JsonPrimitive(status), key to kotlinx.serialization.json.JsonPrimitive(at)))
        val now = Instant.parse("2026-11-13T01:00:00Z")
        assertEquals(false, electionStageOpen(e("announced", "candidacy_closes", "2026-11-12T15:00:00Z"), now))
        assertEquals(true, electionStageOpen(e("voting", "voting_closes", "2026-11-14T15:00:00Z"), now))
        assertEquals(false, electionStageOpen(e("closed", "voting_closes", "2026-11-14T15:00:00Z"), now))
        assertEquals("Candidacy closed", electionStageLabel(e("announced", "candidacy_closes", "2026-11-12T15:00:00Z"), now))
        assertEquals("Voting open", electionStageLabel(e("voting", "voting_closes", "2026-11-14T15:00:00Z"), now))
    }

    @org.junit.Test fun theSplashSaysWhichProblemItIs() {
        // Mikaela, 22 Nov: the server refuses a turned-off account with this message.
        assertEquals(SplashProblem.ACCOUNT_OFF, splashProblem(true, "Sign in with a verified, enabled account.\nURL: https://example/rpc/club_workspace"))
        assertEquals(SplashProblem.OFFLINE, splashProblem(false, "Unable to resolve host"))
        assertEquals(SplashProblem.NO_ACCESS, splashProblem(true, "JWT expired"))
    }

    @org.junit.Test fun clubsDecideTheFirstScreenTheColourAndTheSchool() {
        fun o(vararg p: Pair<String, String>) = kotlinx.serialization.json.JsonObject(p.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })
        fun arr(vararg x: kotlinx.serialization.json.JsonObject) = kotlinx.serialization.json.JsonArray(x.toList())
        val up = o("id" to "c2", "name" to "UP Archery Club", "school" to "University of the Philippines", "theme" to "red")
        val clubs = arr(o("id" to "c1", "name" to "DLSU Archery Club", "theme" to "green"), up)
        // A new account with two clubs to choose from picks one first; a partner guest and an older backend don't.
        val fresh = kotlinx.serialization.json.JsonObject(mapOf("user_id" to kotlinx.serialization.json.JsonPrimitive("u"), "clubs" to clubs,
            "profiles" to arr(o("id" to "u", "account_type" to "member"))))
        assertEquals(true, needsClub(fresh))
        assertEquals(false, needsClub(kotlinx.serialization.json.JsonObject(fresh + ("profiles" to arr(o("id" to "u", "account_type" to "partner"))))))
        assertEquals(false, needsClub(kotlinx.serialization.json.JsonObject(mapOf("user_id" to kotlinx.serialization.json.JsonPrimitive("u")))))
        val inUp = kotlinx.serialization.json.JsonObject(fresh + ("club" to up) + ("profiles" to arr(o("id" to "u", "platform_admin" to "true"))))
        assertEquals(false, needsClub(inUp)); assertEquals(true, isPlatformAdmin(inUp))
        assertEquals("red", clubTheme(inUp)); assertEquals("green", clubTheme(null)); assertEquals("UP Archery Club", clubName(inUp))
        assertEquals(true, sameSchool("university of the philippines", "University of the Philippines"))
        assertEquals(true, sameSchool("DLSU", "De La Salle University")); assertEquals(false, sameSchool("Ateneo", "De La Salle University"))
    }

    @org.junit.Test fun aNewClubsAdministratorIsGreetedByNameAndSentToSetup() {
        assertEquals("Karl Perez", nameFromEmail("karl_perez@ateneo.edu.ph"))
        assertEquals("Ana Cruz", nameFromEmail("ana.cruz2@up.edu.ph".replace("2", "")))
        assertEquals("", nameFromEmail("12345@x.test")); assertEquals("", nameFromEmail(null))
        fun o(vararg p: Pair<String, String>) = kotlinx.serialization.json.JsonObject(p.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })
        val clubs = kotlinx.serialization.json.JsonArray(listOf(o("id" to "a", "name" to "Ateneo Archery Club", "short_name" to "ADMU"), o("id" to "b", "name" to "UP Archery Club")))
        // A brand-new club: blank settings, nothing in any queue -> Home points the administrator at Club setup.
        val ws = kotlinx.serialization.json.JsonObject(mapOf("user_id" to kotlinx.serialization.json.JsonPrimitive("u"), "clubs" to clubs,
            "club" to clubs[0], "settings" to kotlinx.serialization.json.JsonArray(listOf(o("club_name" to "Ateneo Archery Club")))))
        assertEquals("ADMU", clubPill(ws))
        val admin = setOf("administration")
        assertTrue(setupRemaining(ws, admin) > 0); assertEquals(0, setupRemaining(ws, setOf("finance")))
        val focus = officerFocus(ws, admin, java.time.Instant.now())!!
        assertEquals("club_setup", focus.route); assertTrue(focus.title.startsWith("Set up your club"))
        assertEquals(null, officerFocus(ws, setOf("finance"), java.time.Instant.now()))
    }

    @org.junit.Test fun clubRequestsShowTheStudentTheirStatusAndThePlatformAdminWhatsWaiting() {
        assertEquals("feu.edu.ph", emailDomain("Maria@FEU.edu.ph")); assertTrue(isPersonalEmail("someone@gmail.com")); assertEquals(false, isPersonalEmail("maria@feu.edu.ph"))
        fun o(vararg p: Pair<String, String>) = kotlinx.serialization.json.JsonObject(p.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })
        val reqs = kotlinx.serialization.json.JsonArray(listOf(
            o("id" to "1", "user_id" to "u", "status" to "declined", "created_at" to "2026-11-22T10:00:00Z"),
            o("id" to "2", "user_id" to "u", "status" to "pending", "created_at" to "2026-11-23T10:00:00Z"),
            o("id" to "3", "user_id" to "v", "status" to "pending", "created_at" to "2026-11-21T10:00:00Z")))
        val ws = kotlinx.serialization.json.JsonObject(mapOf("user_id" to kotlinx.serialization.json.JsonPrimitive("u"), "club_requests" to reqs))
        assertEquals("2", myClubRequest(ws)!!.text("id"))
        assertEquals(listOf("3", "2"), pendingClubRequests(ws).map { it.text("id") })
    }
}
