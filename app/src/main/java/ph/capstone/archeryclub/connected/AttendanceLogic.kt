package ph.capstone.archeryclub.connected

import kotlinx.serialization.json.JsonObject
import java.time.Instant

/* Attendance desk (migration 062): who came to what, events and training alike. Pure logic over the workspace's
 * "attendance_overview", which only Administrators, Activity officers, Training officers and Presidents receive. */

/** Who opens the desk: the same roles the server gives the overview to. */
val ATTENDANCE_DESK_PERMISSIONS = setOf("administration", "activities", "training", "president")
fun canSeeAttendance(workspace: JsonObject, permissions: Set<String>): Boolean =
    permissions.any { it in ATTENDANCE_DESK_PERMISSIONS } && workspace["attendance_overview"] is JsonObject && featureOn(workspace, "activities")

val ACTIVITY_KINDS = listOf("training" to "Training", "event" to "Events", "meeting" to "Meetings")
fun activityKindLabel(kind: String) = when (kind) { "training" -> "Training"; "meeting" -> "Meeting"; else -> "Event" }

data class AttRow(val userId: String, val name: String, val registered: Boolean, val status: String, val method: String, val by: String)
data class AttEvent(val id: String, val title: String, val kind: String, val start: Instant?, val end: Instant?, val venue: String, val roster: List<AttRow>) {
    /** Attendance was taken if anyone has a record. */
    val taken get() = roster.any { it.status.isNotBlank() }
    val present get() = roster.count { it.status == "present" }
    val excused get() = roster.count { it.status == "excused" }
    val absent get() = roster.count { it.status == "absent" }
    val noRecord get() = roster.count { it.status.isBlank() }
    /** Excused people don't count against the rate (same rule as the reports, migration M13). */
    val expected get() = roster.count { it.status != "excused" }
    fun ended(now: Instant) = end?.isBefore(now) ?: true
    /** Registered, then absent or never checked in, once the activity is over. */
    fun noShows(now: Instant) = if (ended(now)) roster.filter { it.registered && (it.status == "absent" || it.status.isBlank()) } else emptyList()
}

fun attendanceEvents(workspace: JsonObject, kind: String? = null): List<AttEvent> =
    (workspace["attendance_overview"] as? JsonObject)?.records("events").orEmpty().map { e ->
        AttEvent(e.text("id"), e.text("title"), e.text("kind").ifBlank { "event" }, e.instant("starts_at"), e.instant("ends_at"), e.text("venue"),
            e.records("roster").map { r -> AttRow(r.text("user_id"), r.text("name"), r.text("registered") == "true", r.text("status"), r.text("method"), r.text("by")) })
    }.filter { kind == null || it.kind == kind }.sortedByDescending { it.start }

data class MemberAttendance(val userId: String, val name: String, val attended: Int, val counted: Int, val last: Instant?, val missedInARow: Int,
                            val timeline: List<Pair<AttEvent, String>>) {
    val rate get() = if (counted == 0) null else attended * 100 / counted
}

/** Each Active member against the activities where attendance was taken since they joined (excused ones left out). */
fun memberAttendance(workspace: JsonObject, kind: String? = null): List<MemberAttendance> {
    val events = attendanceEvents(workspace, kind).filter { it.taken }
    return (workspace["attendance_overview"] as? JsonObject)?.records("members").orEmpty().map { m ->
        val id = m.text("user_id"); val since = m.instant("since")
        val mine = events.filter { e -> since == null || e.start == null || !e.start.isBefore(since.minusSeconds(86_400)) }
            .map { e -> e to (e.roster.find { it.userId == id }?.status ?: "") }
        val counted = mine.filter { it.second != "excused" }
        var missed = 0
        for ((_, s) in counted) { if (s == "present") break; missed++ }
        MemberAttendance(id, m.text("name"), counted.count { it.second == "present" }, counted.size,
            mine.firstOrNull { it.second == "present" }?.first?.start, missed, mine)
    }.sortedWith(compareBy({ it.rate ?: 101 }, { it.name }))
}

/** "Missed 3 in a row": members whose last three counted activities had no check-in. */
fun missingStreaks(workspace: JsonObject, kind: String? = null) = memberAttendance(workspace, kind).filter { it.missedInARow >= 3 }

fun statusLabel(s: String) = when (s) { "present" -> "Present"; "absent" -> "Absent"; "excused" -> "Excused"; else -> "No record" }

private fun csv(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

/** One row per person per activity, for a spreadsheet. */
fun attendanceCsv(events: List<AttEvent>): String = buildString {
    append("Activity,Type,Date,Member,Registered,Status,Method,Recorded by\n")
    events.forEach { e -> e.roster.forEach { r ->
        append(listOf(e.title, activityKindLabel(e.kind), e.start?.toString()?.take(10).orEmpty(), r.name, if (r.registered) "Yes" else "No",
            statusLabel(r.status), r.method, r.by).joinToString(",") { csv(it) }).append('\n')
    } }
}

fun memberAttendanceCsv(rows: List<MemberAttendance>): String = buildString {
    append("Member,Attended,Counted,Rate %,Last attended,Missed in a row\n")
    rows.forEach { m -> append(listOf(m.name, "${m.attended}", "${m.counted}", m.rate?.toString().orEmpty(), m.last?.toString()?.take(10).orEmpty(), "${m.missedInARow}").joinToString(",") { csv(it) }).append('\n') }
}
