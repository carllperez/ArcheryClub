package ph.capstone.archeryclub.connected

import org.junit.Assert.assertEquals
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
}
