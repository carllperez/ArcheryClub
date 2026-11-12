package ph.capstone.archeryclub.connected

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/* Wording for the member home. Pure functions so they are unit-tested on the JVM. */

fun greetingFor(hour: Int): String = when (hour) { in 0..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }

fun firstName(fullName: String): String = fullName.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
/** "Good evening, Mia", or just "Good evening" when there is no name yet. */
fun hello(hour: Int, fullName: String): String = greetingFor(hour) + firstName(fullName).let { if (it.isBlank()) "" else ", $it" }

/** "Today", "Tomorrow", "In 5 days" — counted in calendar days, so 11 PM tonight and 7 AM tomorrow read naturally. */
fun countdownLabel(start: Instant?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (start == null) return ""
    if (!start.isAfter(now)) return "Now"
    val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), start.atZone(zone).toLocalDate())
    return when (days) { 0L -> "Today"; 1L -> "Tomorrow"; else -> "In $days days" }
}

/** Compact time left (or late) for a return: "45m", "2h 15m", "3d 4h". */
fun durationLabel(from: Instant, to: Instant): String {
    val m = kotlin.math.abs(java.time.Duration.between(from, to).toMinutes())
    return when {
        m < 60 -> "${m.coerceAtLeast(1)}m"
        m < 24 * 60 -> "${m / 60}h${if (m % 60 > 0) " ${m % 60}m" else ""}"
        else -> "${m / (24 * 60)}d${if ((m / 60) % 24 > 0) " ${(m / 60) % 24}h" else ""}"
    }
}

/** Whether an item suits the member's profile (hand and draw weight only; dates are checked when requesting). */
fun fitProblems(itemHand: String, itemWeight: Double?, hand: String, maxWeight: Double?): List<String> = buildList {
    if (hand.isNotBlank() && itemHand in setOf("left", "right") && itemHand != hand) add("${itemHand.replaceFirstChar { it.uppercase() }}-handed")
    if (itemWeight != null && maxWeight != null && itemWeight > maxWeight) add("Heavier than your ${if (maxWeight % 1.0 == 0.0) maxWeight.toLong() else maxWeight} lb")
}
