package ph.capstone.archeryclub.connected

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/* Wording for the member home. Pure functions so they are unit-tested on the JVM. */

fun greetingFor(hour: Int): String = when (hour) { in 0..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }

fun firstName(fullName: String): String = fullName.trim().split(Regex("\\s+")).firstOrNull().orEmpty().ifBlank { "there" }

/** "Today", "Tomorrow", "In 5 days" — counted in calendar days, so 11 PM tonight and 7 AM tomorrow read naturally. */
fun countdownLabel(start: Instant?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (start == null) return ""
    if (!start.isAfter(now)) return "Now"
    val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), start.atZone(zone).toLocalDate())
    return when (days) { 0L -> "Today"; 1L -> "Tomorrow"; else -> "In $days days" }
}
