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
        assertEquals("Alex", firstName("  Alex  Santos ")); assertEquals("there", firstName(""))
    }
}
