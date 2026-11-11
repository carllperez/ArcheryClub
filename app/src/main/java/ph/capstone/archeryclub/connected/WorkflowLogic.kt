package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/*
 * Pure business rules used by the purpose-built workflow screens. They mirror the server
 * rules (which stay authoritative) so the UI never offers an action the server will refuse,
 * and they are unit-tested without Android.
 */

fun instantOf(value: String): Instant? = if (value.isBlank()) null else
    runCatching { OffsetDateTime.parse(value).toInstant() }.recoverCatching { Instant.parse(value) }.getOrNull()

fun JsonObject.instant(key: String): Instant? = instantOf(text(key))

/** What a member sees on an activity, in priority order. */
enum class ActivityStatus(val label: String) {
    REGISTERED("Registered"), WAITLISTED("Waitlisted"), ATTENDED("Attended"), EXCUSED("Excused"), MISSED("Missed"),
    OPEN("Open"), CLOSING_SOON("Closing soon"), FULL("Full · waitlist open"), NOT_YET_OPEN("Opens soon"),
    CLOSED("Registration closed"), IN_PROGRESS("In progress"), ENDED("Ended");
    val isMine get() = this in setOf(REGISTERED, WAITLISTED, ATTENDED, EXCUSED, MISSED)
}

data class ActivityView(
    val event: JsonObject,
    val status: ActivityStatus,
    val registered: Int,
    val waitlisted: Int,
    val present: Int,
    val excused: Int,
    val capacity: Int,
    val waitlistPosition: Int?,
    val start: Instant?,
    val end: Instant?,
    val closes: Instant?,
    val opens: Instant?,
) {
    val id get() = event.text("id")
    val spotsLeft get() = (capacity - registered).coerceAtLeast(0)
    val canRegister get() = status in setOf(ActivityStatus.OPEN, ActivityStatus.CLOSING_SOON, ActivityStatus.FULL)
    val canCancel get() = status in setOf(ActivityStatus.REGISTERED, ActivityStatus.WAITLISTED)
}

fun activityView(event: JsonObject, workspace: JsonObject, now: Instant = Instant.now()): ActivityView {
    val user = workspace.text("user_id")
    val id = event.text("id")
    val stats = workspace.records("event_stats").find { it.text("event_id") == id }
    val ownRegistration = workspace.records("registrations").any { it.text("event_id") == id && it.text("user_id") == user }
    val ownWaitlist = workspace.records("waitlist").any { it.text("event_id") == id && it.text("user_id") == user }
    val attendance = workspace.records("attendance").find { it.text("event_id") == id && it.text("user_id") == user }?.text("status")
    // Officers can read every registration; members only see their own, so prefer server counts.
    val registered = stats?.text("registered")?.toIntOrNull()
        ?: workspace.records("registrations").count { it.text("event_id") == id }
    val waitlisted = stats?.text("waitlisted")?.toIntOrNull()
        ?: workspace.records("waitlist").count { it.text("event_id") == id }
    val present = stats?.text("present")?.toIntOrNull()
        ?: workspace.records("attendance").count { it.text("event_id") == id && it.text("status") == "present" }
    val excused = stats?.text("excused")?.toIntOrNull()
        ?: workspace.records("attendance").count { it.text("event_id") == id && it.text("status") == "excused" }
    val capacity = event.text("capacity").toIntOrNull() ?: 0
    val start = event.instant("starts_at"); val end = event.instant("ends_at") ?: start
    val opens = event.instant("registration_opens"); val closes = event.instant("registration_closes")
    val ended = end != null && !end.isAfter(now)
    val started = start != null && !start.isAfter(now)
    val status = when {
        attendance == "present" -> ActivityStatus.ATTENDED
        attendance == "excused" -> ActivityStatus.EXCUSED
        attendance == "absent" -> ActivityStatus.MISSED
        ownRegistration && ended -> ActivityStatus.MISSED
        ownRegistration -> ActivityStatus.REGISTERED
        ownWaitlist && !ended -> ActivityStatus.WAITLISTED
        ended -> ActivityStatus.ENDED
        started -> ActivityStatus.IN_PROGRESS
        opens != null && now.isBefore(opens) -> ActivityStatus.NOT_YET_OPEN
        closes != null && now.isAfter(closes) -> ActivityStatus.CLOSED
        capacity in 1..registered -> ActivityStatus.FULL
        closes != null && Duration.between(now, closes) <= Duration.ofHours(24) -> ActivityStatus.CLOSING_SOON
        else -> ActivityStatus.OPEN
    }
    return ActivityView(event, status, registered, waitlisted, present, excused, capacity,
        stats?.text("waitlist_position")?.toIntOrNull(), start, end, closes, opens)
}

/** Published, unarchived activities. Closed registrations are hidden unless the member already holds a place. */
fun memberActivities(workspace: JsonObject, now: Instant = Instant.now()): List<ActivityView> =
    workspace.records("events")
        .filter { it.text("published") == "true" && it.text("archived") != "true" && it.instant("starts_at") != null }
        .map { activityView(it, workspace, now) }
        .filter { v -> v.end?.isAfter(now) == true && (v.status.isMine || v.status !in setOf(ActivityStatus.CLOSED, ActivityStatus.IN_PROGRESS)) }
        .sortedBy { it.start }

fun upcomingMine(workspace: JsonObject, now: Instant = Instant.now()) =
    memberActivities(workspace, now).filter { it.status in setOf(ActivityStatus.REGISTERED, ActivityStatus.WAITLISTED) && it.end?.isAfter(now) == true }

fun participationHistory(workspace: JsonObject, now: Instant = Instant.now()) =
    workspace.records("events").map { activityView(it, workspace, now) }
        .filter { it.status in setOf(ActivityStatus.ATTENDED, ActivityStatus.EXCUSED, ActivityStatus.MISSED) }
        .sortedByDescending { it.start }

/** The one attendance-rate definition used everywhere (member, dashboard, server report): present / (registered − excused). */
fun attendanceRate(present: Int, registered: Int, excused: Int): Int? =
    (registered - excused).takeIf { it > 0 }?.let { present * 100 / it }

data class ParticipationSummary(val attended: Int, val excused: Int, val missed: Int) {
    val total get() = attended + excused + missed
    /** Excused absences do not count against the member. */
    val rate get() = attendanceRate(attended, attended + excused + missed, excused)
}
fun participationSummary(workspace: JsonObject, now: Instant = Instant.now()) = participationHistory(workspace, now).let { h ->
    ParticipationSummary(h.count { it.status == ActivityStatus.ATTENDED }, h.count { it.status == ActivityStatus.EXCUSED }, h.count { it.status == ActivityStatus.MISSED })
}

// ------------------------------------------------------------------ equipment

enum class EquipmentState(val label: String, val borrowable: Boolean) {
    AVAILABLE("Available", true), RESERVED("Reserved", true), IN_USE("Borrowed", true), OVERDUE("Overdue", false),
    MAINTENANCE("Maintenance", false), DAMAGED("Damaged", false), RETIRED("Retired", false);
    companion object {
        fun of(value: String) = when (value) {
            "available", "serviceable" -> AVAILABLE; "reserved" -> RESERVED; "in_use" -> IN_USE; "overdue" -> OVERDUE
            "maintenance" -> MAINTENANCE; "damaged" -> DAMAGED; else -> RETIRED
        }
    }
}

fun equipmentState(item: JsonObject, workspace: JsonObject): EquipmentState {
    if (item.text("condition") != "serviceable") return EquipmentState.of(item.text("condition"))
    return workspace.records("equipment_status").find { it.text("equipment_id") == item.text("id") }
        ?.let { EquipmentState.of(it.text("state")) } ?: EquipmentState.AVAILABLE
}

/** Problems that would make the server refuse approval, shown before the member submits. */
fun borrowingIssues(item: JsonObject, hand: String, maxDrawWeight: Double?, start: Instant?, end: Instant?, now: Instant = Instant.now()): List<String> = buildList {
    if (item.text("condition") != "serviceable") add("This item is not serviceable.")
    val itemHand = item.text("handedness")
    if (hand.isNotBlank() && itemHand in setOf("left", "right") && itemHand != hand) add("This is a ${itemHand}-handed item; your profile says ${hand}-handed.")
    val weight = item.text("draw_weight").toDoubleOrNull()
    if (weight != null && maxDrawWeight != null && weight > maxDrawWeight) add("Draw weight ${fmtNumber(weight)} lb is above your limit of ${fmtNumber(maxDrawWeight)} lb.")
    if (start == null || end == null) add("Choose when you will pick up and return the item.")
    else {
        if (start.isBefore(now)) add("Pick-up time must be in the future.")
        if (!end.isAfter(start)) add("Return time must be after pick-up.")
        if (Duration.between(start, end) > Duration.ofDays(14)) add("Borrowing periods are limited to 14 days.")
    }
}

fun fmtNumber(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** Next officer step for a borrowing, mirroring the server's allowed transitions. */
fun borrowingNextSteps(status: String): List<String> = when (status) {
    "pending" -> listOf("approved", "returned_for_correction", "rejected")
    "approved" -> listOf("released", "rejected")
    "released" -> listOf("returned")
    else -> emptyList()
}

fun isOverdue(borrowing: JsonObject, now: Instant = Instant.now()) =
    borrowing.text("status") == "released" && borrowing.instant("ends_at")?.isBefore(now) == true

fun borrowingLabel(status: String, overdue: Boolean) = when {
    overdue -> "Overdue"
    else -> when (status) {
        "pending" -> "Awaiting review"; "approved" -> "Approved · ready for pick-up"; "released" -> "Borrowed"
        "returned" -> "Returned"; "rejected" -> "Declined"; "returned_for_correction" -> "Needs changes"; "cancelled" -> "Cancelled"
        else -> human(status)
    }
}

// ------------------------------------------------------------------ profile and officer queues

enum class Tone { POSITIVE, WARNING, DANGER, INFO, NEUTRAL }

internal val PHONE = Regex("^\\+?[0-9][0-9 -]{6,18}$")
/** Client checks mirror app_private.save_member_profile; the server repeats them. */
fun profileErrors(v: Map<String, String>): Map<String, String> = buildMap {
    if (v["full_name"].orEmpty().isBlank()) put("full_name", "Enter your full name.")
    if (v["phone"].orEmpty().isNotBlank() && !PHONE.matches(v["phone"]!!.trim())) put("phone", "Use a mobile number like 0917 123 4567.")
    val ecName = v["emergency_contact_name"].orEmpty().isNotBlank(); val ecPhone = v["emergency_contact_phone"].orEmpty().isNotBlank()
    if (ecPhone && !PHONE.matches(v["emergency_contact_phone"]!!.trim())) put("emergency_contact_phone", "Enter a valid number.")
    if (ecName != ecPhone) put(if (ecName) "emergency_contact_phone" else "emergency_contact_name", "Provide both the name and the number.")
    v["draw_weight"].orEmpty().takeIf { it.isNotBlank() }?.let { if (it.toDoubleOrNull()?.let { w -> w in 5.0..80.0 } != true) put("draw_weight", "Between 5 and 80 lb.") }
    v["arrow_length"].orEmpty().takeIf { it.isNotBlank() }?.let { if (it.toDoubleOrNull()?.let { w -> w in 20.0..34.0 } != true) put("arrow_length", "Between 20 and 34 in.") }
    if (v["bio"].orEmpty().length > 500) put("bio", "Keep it under 500 characters.")
}

fun profileCompleteness(profile: JsonObject?): Int {
    if (profile == null) return 0
    val keys = listOf("full_name", "phone", "emergency_contact_name", "program", "dominant_hand", "bow_type", "draw_weight", "experience_level")
    return keys.count { profile.text(it).isNotBlank() } * 100 / keys.size
}

data class QueueItem(val label: String, val count: Int, val route: String, val tone: Tone)

/** Live counts from the same records the workflows change; each tile opens its queue. */
fun officerQueues(workspace: JsonObject, permissions: Set<String>, now: Instant = Instant.now()): List<QueueItem> = buildList {
    val user = workspace.text("user_id")
    if ("administration" in permissions) {
        val soon = now.plusSeconds(14 * 86400L)
        val live = workspace.records("role_assignments").filter { it.text("retired_at").isBlank() }
        add(QueueItem("Roles expiring within 14 days", live.count { a -> a.instant("ends_at")?.let { it.isAfter(now) && it.isBefore(soon) } == true }, "module:14", Tone.WARNING))
        add(QueueItem("Expired roles to renew", live.count { a -> a.instant("ends_at")?.let { it.isBefore(now) && it.isAfter(now.minusSeconds(60 * 86400L)) } == true }, "module:14", Tone.DANGER))
    }
    if ("membership" in permissions) {
        val pendingApps = workspace.records("applications").filter { it.text("user_id") != user && it.text("status") in setOf("pending", "under_review") }.map { it.text("id") }.toSet()
        add(QueueItem("Documents to verify", workspace.records("documents").count { it.text("module") == "2" && it.text("record_id") in pendingApps && it.text("verified_by").isBlank() && it.text("superseded_by").isBlank() }, "applications", Tone.WARNING))
        add(QueueItem("Name corrections", workspace.records("name_corrections").count { it.text("user_id") != user && it.text("status") == "pending" }, "name_corrections", Tone.WARNING))
        add(QueueItem("Applications to review", workspace.records("applications").count { it.text("user_id") != user && it.text("status") in setOf("pending", "under_review") }, "applications", Tone.WARNING))
        add(QueueItem("Renewals to review", workspace.records("renewals").count { it.text("user_id") != user && it.text("status") == "pending" }, "records:7", Tone.WARNING))
    }
    if ("equipment" in permissions) {
        val b = workspace.records("borrowings")
        add(QueueItem("Borrowing requests", b.count { it.text("status") == "pending" }, "equipment", Tone.WARNING))
        add(QueueItem("Ready to hand over", b.count { it.text("status") == "approved" }, "equipment", Tone.INFO))
        add(QueueItem("Overdue returns", b.count { isOverdue(it, now) }, "equipment", Tone.DANGER))
    }
    if ("activities" in permissions) {
        add(QueueItem("Attendance corrections", workspace.records("corrections").count { it.text("module") == "8" && it.text("status") == "pending" }, "records:8", Tone.WARNING))
        add(QueueItem("Draft activities", workspace.records("events").count { it.text("published") != "true" && it.text("archived") != "true" }, "officer_activities", Tone.NEUTRAL))
    }
    if ("training" in permissions) add(QueueItem("Scorecards to validate", workspace.records("training_records").count { it.text("validation") == "pending" }, "module:11", Tone.WARNING))
    if ("finance" in permissions || "president" in permissions)
        add(QueueItem("Financial submissions", workspace.records("financial_records").count { it.text("user_id") != user && it.text("status") == "pending_verification" }, "module:12", Tone.WARNING))
    if ("finance" in permissions) add(QueueItem("Payments to match to fees", paymentsToLink(workspace).size, "module:12", Tone.DANGER))
    if ("elections" in permissions) {
        val open = workspace.records("elections").filter { it.text("status") in setOf("announced", "voting") }.map { it.text("id") }.toSet()
        add(QueueItem("Candidates to confirm", workspace.records("candidates").count { it.text("election_id") in open && it.text("confirmed") != "true" }, "module:9", Tone.WARNING))
    }
    if ("interclub" in permissions) {
        add(QueueItem("Delegates to review", workspace.records("delegates").count { it.text("status") == "pending" }, "module:15", Tone.WARNING))
        add(QueueItem("Partner concerns", workspace.records("interclub_concerns").count { it.text("status") == "open" }, "module:15", Tone.WARNING))
    }
}


/** Once a membership exists the name is the verified identity: it changes only through an officer-approved correction. */
fun nameLocked(workspace: JsonObject) = workspace.records("memberships").any { it.text("user_id") == workspace.text("user_id") }

fun pendingNameCorrection(workspace: JsonObject) =
    workspace.records("name_corrections").find { it.text("user_id") == workspace.text("user_id") && it.text("status") == "pending" }

/** Members with repeated unexcused absences are flagged to officers (informational, not a penalty). */
fun recentNoShows(workspace: JsonObject, userId: String, now: Instant = Instant.now(), days: Long = 60): Int {
    val since = now.minusSeconds(days * 86400)
    val events = workspace.records("events").associateBy { it.text("id") }
    return workspace.records("attendance").count { a ->
        a.text("user_id") == userId && a.text("status") == "absent" &&
            events[a.text("event_id")]?.instant("starts_at")?.let { it.isAfter(since) && it.isBefore(now) } == true
    }
}

/** A member with an overdue item cannot request more equipment (server enforces the same rule). */
fun hasOverdueItem(workspace: JsonObject, now: Instant = Instant.now()) =
    workspace.records("borrowings").any { it.text("user_id") == workspace.text("user_id") && isOverdue(it, now) }

/** Walk-ins can be recorded from 2 hours before the start until a day after the end. */
fun walkInWindowOpen(event: JsonObject, now: Instant = Instant.now()): Boolean {
    val start = event.instant("starts_at") ?: return false
    val end = event.instant("ends_at") ?: start
    return !now.isBefore(start.minusSeconds(7200)) && !now.isAfter(end.plusSeconds(86400))
}
