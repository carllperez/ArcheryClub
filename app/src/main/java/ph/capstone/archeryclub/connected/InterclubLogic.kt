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
        IC_CHECKIN -> approved.count { it.text("status") == "eligible" }.let { left ->
            IcNext(step, if (left > 0) "Check in $left archer${if (left == 1) "" else "s"}" else "Everyone is checked in", "Record results as flights finish.", "ic_checkin:$id") }
        IC_RESULTS -> icResults(workspace, id).count { it.text("verified_by").isBlank() }.let { left ->
            IcNext(step, if (left > 0) "Verify $left result${if (left == 1) "" else "s"}" else "Record results", "Partners and members see a result only after it's verified.", "ic_results:$id") }
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
