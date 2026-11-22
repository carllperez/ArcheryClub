package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.JsonObject
import java.time.Instant

/* M15 Interclub: the event's six steps and what each page lists. The database (migration 006) keeps only four
 * stages (preparation, active, completed, archived); the six steps are read from the event's records. */

val IC_STEPS = listOf("Invite", "Delegates", "Schedule", "Check-in", "Results", "Archive")
const val IC_INVITE = 0; const val IC_DELEGATES = 1; const val IC_SCHEDULE = 2; const val IC_CHECKIN = 3; const val IC_RESULTS = 4; const val IC_ARCHIVE = 5
/** Past the last step: the event is archived. */
const val IC_DONE = 6

/** Delegates that still count (a substituted delegate was replaced by a new row). */
fun icDelegates(workspace: JsonObject, eventId: String) =
    workspace.records("delegates").filter { it.text("event_id") == eventId && it.text("status") != "substituted" }

fun icApproved(workspace: JsonObject, eventId: String) =
    icDelegates(workspace, eventId).filter { it.text("status") in setOf("eligible", "checked_in") }

fun icAssignments(workspace: JsonObject, eventId: String) =
    workspace.records("interclub_assignments").filter { it.text("event_id") == eventId }

fun icResults(workspace: JsonObject, eventId: String) =
    workspace.records("interclub_results").filter { it.text("event_id") == eventId }

/** Partner representatives with access to this event (coordinators are DLSU staff, not partners). */
fun icRepresentatives(workspace: JsonObject, eventId: String) =
    workspace.records("event_access").filter { it.text("event_id") == eventId && it.text("revoked_at").isBlank() && it.text("role") == "partner" }

/** Which of the six steps the event is on, from its stage and records. */
fun interclubStep(e: JsonObject, workspace: JsonObject, now: Instant): Int {
    val id = e.text("event_id")
    return when (e.text("status")) {
        "archived" -> IC_DONE
        "completed" -> if (icResults(workspace, id).let { r -> r.isEmpty() || r.any { it.text("verified_by").isBlank() } }) IC_RESULTS else IC_ARCHIVE
        "active" -> if (icResults(workspace, id).isEmpty()) IC_CHECKIN else IC_RESULTS
        else -> {
            val closes = e.instant("registration_closes")
            when {
                icRepresentatives(workspace, id).isEmpty() -> IC_INVITE
                (closes == null || now.isBefore(closes)) || icDelegates(workspace, id).any { it.text("status") == "pending" } -> IC_DELEGATES
                else -> IC_SCHEDULE
            }
        }
    }
}

/** The one thing to do now on this step, for the card at the top of the event page. Null when nothing waits. */
data class IcNext(val step: Int, val title: String, val detail: String, val route: String?)

fun interclubNext(e: JsonObject, workspace: JsonObject, now: Instant): IcNext? {
    val id = e.text("event_id")
    val step = interclubStep(e, workspace, now)
    val pending = icDelegates(workspace, id).count { it.text("status") == "pending" }
    val approved = icApproved(workspace, id)
    val assigned = icAssignments(workspace, id).map { it.text("delegate_id") }.toSet()
    return when (step) {
        IC_INVITE -> IcNext(step, "Invite a partner club", "No club has a representative for this event yet.", null)
        IC_DELEGATES -> if (pending > 0) IcNext(step, "Review $pending delegate${if (pending == 1) "" else "s"}", "Check their requirements before sign-ups close.", "ic_delegates:$id")
            else IcNext(step, "Waiting for delegations", "Sign-ups are still open. Partner clubs are still naming their archers.", "ic_delegates:$id")
        IC_SCHEDULE -> approved.count { it.text("id") !in assigned }.let { left ->
            if (left > 0) IcNext(step, "Assign $left archer${if (left == 1) "" else "s"} to targets", "Every approved delegate needs a flight and target.", "ic_schedule:$id")
            else IcNext(step, "Schedule is ready", "Start the event day to open check-in.", "ic_schedule:$id") }
        // Once everyone is here, the job is recording results, so the card opens Results, not an empty check-in list (21 Nov testing).
        IC_CHECKIN -> approved.count { it.text("status") == "eligible" }.let { left ->
            if (left > 0) IcNext(step, "Check in $left archer${if (left == 1) "" else "s"}", "Record results as flights finish.", "ic_checkin:$id")
            else IcNext(step, "Everyone is checked in", "Record each archer's result as their flight finishes.", "ic_results:$id") }
        IC_RESULTS -> resultsNext(workspace, id).let { (title, detail) -> IcNext(step, title, detail, "ic_results:$id") }
        IC_ARCHIVE -> IcNext(step, "Archive the event", "Keep the agreement, minutes and lessons for the next officers.", null)
        else -> null
    }
}

/** A result as a number when it is one ("612" or "612/720"), for ranking; otherwise null. */
fun icScore(result: String): Int? = Regex("""^\s*(\d{1,4})""").find(result)?.groupValues?.get(1)?.toIntOrNull()

/** Results ranked by score (highest first) within a category; non-numeric results keep their order at the end. */
fun icRanked(results: List<JsonObject>): List<JsonObject> =
    results.sortedWith(compareByDescending<JsonObject> { icScore(it.text("result")) ?: -1 }.thenBy { it.text("id") })

/** Short club initials for the coloured badge, e.g. "North Range Archers" -> "NR". */
fun clubInitials(name: String) = name.split(Regex("\\s+")).filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }

/** Results still to record or verify, worded for the person looking: the officer who recorded a result can't verify it
 *  (the server refuses), so they are told someone else has to instead of being asked to "Verify" (21 Nov testing). */
fun resultsNext(workspace: JsonObject, eventId: String): Pair<String, String> {
    val results = icResults(workspace, eventId)
    val recorded = results.map { it.text("delegate_id") }.toSet()
    val toRecord = icDelegates(workspace, eventId).count { it.text("status") == "checked_in" && it.text("id") !in recorded }
    val unverified = results.filter { it.text("verified_by").isBlank() }
    val forMe = unverified.count { !recordedByMe(it, workspace) }
    fun n(k: Int, word: String) = "$k $word${if (k == 1) "" else "s"}"
    return when {
        forMe > 0 -> "Verify ${n(forMe, "result")}" to "Check each against the signed scoresheet. Partners see a result only after it's verified."
        toRecord > 0 -> "Record ${n(toRecord, "result")}" to "Enter each archer's score as their flight finishes."
        unverified.isNotEmpty() -> "Another officer verifies ${if (unverified.size == 1) "your result" else "your ${unverified.size} results"}" to
            "You recorded ${if (unverified.size == 1) "it" else "them"}, so someone else checks against the signed scoresheet. Ask another interclub officer."
        results.isEmpty() -> "Record results" to "Enter each archer's score as their flight finishes."
        else -> "Every result is verified" to "Partners can see them now."
    }
}

/** The banner on the Results page: nothing recorded, some waiting for verification, or all verified. */
fun resultsBanner(results: List<JsonObject>): Pair<String, Tone> {
    val unverified = results.count { it.text("verified_by").isBlank() }
    return when {
        results.isEmpty() -> "No results recorded yet" to Tone.NEUTRAL
        unverified > 0 -> "$unverified not verified yet" to Tone.WARNING
        else -> "Every recorded result is verified" to Tone.POSITIVE
    }
}

/** Whether the person looking recorded this result (and so can't verify it). */
fun recordedByMe(result: JsonObject, workspace: JsonObject) = result.text("recorded_by").isNotBlank() && result.text("recorded_by") == workspace.text("user_id")

/** The four stages a partner representative sees (22 Nov mockups): each answers "what does my club need now?". */
enum class PartnerStage(val pill: String, val step: Int) { OPEN("SIGN-UPS OPEN", 0), DAY("EVENT DAY", 1), CHECKING("FINISHED", 2), FINAL("FINAL", 4) }

fun partnerStage(event: JsonObject): PartnerStage = when (event.text("status")) {
    "active" -> PartnerStage.DAY
    "completed" -> PartnerStage.CHECKING
    "archived" -> PartnerStage.FINAL
    else -> PartnerStage.OPEN
}

/** "4 days left", "Last day", or null once closed. Whole days in Manila time. */
fun daysLeftLabel(closes: Instant?, now: Instant): String? {
    if (closes == null || !now.isBefore(closes)) return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.atZone(CLUB_ZONE).toLocalDate(), closes.atZone(CLUB_ZONE).toLocalDate())
    return if (days <= 0) "Last day" else "$days day${if (days == 1L) "" else "s"} left"
}

/** A partner club's published results: how many archers competed, how many results are out, and the best score. */
data class PartnerTally(val competed: Int, val published: Int, val pending: Int, val best: Int?)

fun partnerTally(workspace: JsonObject, eventId: String): PartnerTally {
    val mine = icDelegates(workspace, eventId)
    val results = icResults(workspace, eventId)
    val published = results.filter { it.text("verified_by").isNotBlank() }
    val competed = mine.count { it.text("status") == "checked_in" }
    return PartnerTally(competed, published.size, (competed - published.size).coerceAtLeast(0), published.mapNotNull { icScore(it.text("result")) }.maxOrNull())
}
