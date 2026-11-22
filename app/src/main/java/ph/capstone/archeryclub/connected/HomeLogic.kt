package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/* Wording for the member home. Pure functions so they are unit-tested on the JVM. */

/** The club runs on Philippine time. MainActivity makes this the app's default zone at start-up, so every date,
 *  deadline and greeting reads in Manila time even on a phone (or emulator) set to another zone (21 Nov testing:
 *  the emulator was on GMT and said "Good evening" at 7:39 AM). */
const val CLUB_ZONE_ID = "Asia/Manila"
val CLUB_ZONE: ZoneId = ZoneId.of(CLUB_ZONE_ID)

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

/** Why the club couldn't open (22 Nov feedback: Mikaela's turned-off account showed "Can't reach your club" and a
 *  Try again that could never work). OFFLINE: no answer from the server. ACCOUNT_OFF: the server says this account is
 *  turned off. NO_ACCESS: the server answered but refused for another reason. */
enum class SplashProblem { OFFLINE, ACCOUNT_OFF, NO_ACCESS }

fun splashProblem(serverReplied: Boolean, message: String?): SplashProblem = when {
    !serverReplied -> SplashProblem.OFFLINE
    message.orEmpty().contains("enabled account", ignoreCase = true) -> SplashProblem.ACCOUNT_OFF
    else -> SplashProblem.NO_ACCESS
}

/* Many clubs, one app (migration 050). */
/** The caller's club, or null when the account hasn't picked one yet. */
fun myClub(workspace: JsonObject): JsonObject? = workspace["club"] as? JsonObject
fun allClubs(workspace: JsonObject): List<JsonObject> = workspace.records("clubs")
/** A new account must pick a club once there is more than one (a backend without clubs never asks). */
fun needsClub(workspace: JsonObject): Boolean {
    if (!workspace.containsKey("clubs") || myClub(workspace) != null) return false
    val me = workspace.text("user_id")
    return workspace.records("profiles").find { it.text("id") == me }?.text("account_type") != "partner"
}
fun isPlatformAdmin(workspace: JsonObject): Boolean =
    workspace.records("profiles").find { it.text("id") == workspace.text("user_id") }?.text("platform_admin") == "true"
/** A feature a club can switch off for everyone in it (migration 055). Membership, payments and administration always stay on. */
data class ClubFeature(val key: String, val title: String, val detail: String, val icon: String)
val CLUB_FEATURES = listOf(
    ClubFeature("activities", "Activities & announcements", "Sessions, registration, attendance and announcements", "calendar"),
    ClubFeature("equipment", "Equipment borrowing", "Borrow Club Gear, the gear desk and inventory", "box"),
    ClubFeature("training", "Training", "Training logs and the coach desk", "target"),
    ClubFeature("elections", "Elections", "Candidacy, voting and results", "vote"),
    ClubFeature("interclub", "Partner clubs", "Interclub events with other schools", "users"),
    ClubFeature("reports", "Reports & goals", "Club goals and dashboards", "chart"),
)
fun disabledFeatures(workspace: JsonObject): Set<String> =
    (workspace.records("settings").firstOrNull()?.get("disabled_features") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
fun featureOn(workspace: JsonObject, key: String) = key !in disabledFeatures(workspace)
/** The feature a module belongs to, or null when it can't be switched off. */
fun featureForModule(n: Int): String? = when (n) { 3, 8 -> "activities"; 4, 11 -> "training"; 5, 10 -> "equipment"; 9 -> "elections"; 13 -> "reports"; 15 -> "interclub"; else -> null }
/** Desks of switched-off features are dropped as the workspace arrives, so every screen that reads "permissions" agrees with the server's can(). */
fun withFeatureSwitches(workspace: JsonObject): JsonObject {
    val off = disabledFeatures(workspace)
    if (off.isEmpty()) return workspace
    val perms = (workspace["permissions"] as? JsonArray).orEmpty().filter { (it as? JsonPrimitive)?.contentOrNull !in off }
    return JsonObject(workspace + ("permissions" to JsonArray(perms)))
}
/** Role definitions are shared by every club (migration 050): with several clubs only the platform administrator edits them. */
fun rolesLocked(workspace: JsonObject): Boolean = allClubs(workspace).size > 1 && !isPlatformAdmin(workspace)
/** The club's own name for headings and messages, falling back to the club's settings. */
fun clubName(workspace: JsonObject): String = myClub(workspace)?.text("name")?.ifBlank { null }
    ?: workspace.records("settings").firstOrNull()?.text("club_name")?.ifBlank { null } ?: "your club"

/** The club's colour name ("green", "red", "blue"); signed-out screens and older backends use green. */
fun clubTheme(workspace: JsonObject?): String = workspace?.let { myClub(it)?.text("theme") }?.ifBlank { null } ?: "green"
/** A readable name from an email when the account has none yet: karl_perez@ateneo.edu.ph -> "Karl Perez". */
fun nameFromEmail(email: String?): String = email.orEmpty().substringBefore('@').split(Regex("[._-]+"))
    .filter { it.isNotBlank() && it.any(Char::isLetter) }.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
/** The club's short name for a pill on Home (DLSU, UP, ADMU); blank with a single club, where it adds nothing. */
fun clubPill(workspace: JsonObject): String = if (allClubs(workspace).size < 2) "" else
    myClub(workspace)?.let { c -> c.text("short_name").ifBlank { c.text("name") } }.orEmpty().uppercase()

/* Club requests (migration 052): a student whose school has no club asks for one; the platform administrator decides. */
val PERSONAL_EMAIL_DOMAINS = setOf("gmail.com", "yahoo.com", "ymail.com", "outlook.com", "hotmail.com", "live.com", "icloud.com", "me.com",
    "proton.me", "protonmail.com", "aol.com", "gmx.com")
fun emailDomain(email: String?): String = email.orEmpty().substringAfter('@', "").trim().lowercase()
fun isPersonalEmail(email: String?): Boolean = emailDomain(email) in PERSONAL_EMAIL_DOMAINS
/** The signed-in student's latest request, if any. */
fun myClubRequest(workspace: JsonObject): JsonObject? = workspace.records("club_requests")
    .filter { it.text("user_id") == workspace.text("user_id") }.maxByOrNull { it.text("created_at") }
/** Requests waiting for the platform administrator, oldest first. */
fun pendingClubRequests(workspace: JsonObject): List<JsonObject> = workspace.records("club_requests")
    .filter { it.text("status") == "pending" }.sortedBy { it.text("created_at") }
