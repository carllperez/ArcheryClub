package ph.capstone.archeryclub.connected

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.Instant

/* Activities: member discovery → registration/waitlist → attendance → history,
   and the officer side: activity board → participant roster → attendance. */

@Composable
fun ActivityCard(view: ActivityView, onClick: () -> Unit, officer: Boolean = false) {
    WorkCard(view.event.text("title"), listOf(fmtRange(view.start, view.end), view.event.text("venue")),
        leading = { DateBlock(view.start) },
        status = {
            if (officer) {
                StatusPill(if (view.event.text("published") == "true") "Published" else "Draft", if (view.event.text("published") == "true") Tone.POSITIVE else Tone.NEUTRAL)
                StatusPill("${view.registered}/${view.capacity} registered", if (view.registered >= view.capacity && view.capacity > 0) Tone.WARNING else Tone.NEUTRAL)
                if (view.waitlisted > 0) StatusPill("${view.waitlisted} waitlisted", Tone.WARNING)
            } else {
                StatusPill(view.status.label, view.status.tone())
                if (view.status in setOf(ActivityStatus.OPEN, ActivityStatus.CLOSING_SOON)) StatusPill("${view.spotsLeft} spots left", Tone.NEUTRAL)
                if (view.status == ActivityStatus.WAITLISTED && view.waitlistPosition != null) StatusPill("#${view.waitlistPosition} in line", Tone.NEUTRAL)
                if (view.status == ActivityStatus.CLOSING_SOON) StatusPill("Closes ${relative(view.closes)}", Tone.WARNING)
            }
        }, onClick = onClick)
}

/** Member "Activities" tab: open for registration, my schedule, and participation history. */
@Composable
fun MemberActivitiesContent(workspace: JsonObject, onOpen: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("open") }
    val now = Instant.now()
    val all = memberActivities(workspace, now)
    val open = all.filter { !it.status.isMine }
    val mine = upcomingMine(workspace, now)
    val history = participationHistory(workspace, now)
    val summary = participationSummary(workspace, now)
    TileRow {
        StatTile("${mine.size}", "Upcoming for you", Modifier.weight(1f).fillMaxHeight(), Tone.NEUTRAL) { tab = "mine" }
        StatTile(summary.rate?.let { "$it%" } ?: "—", "Attendance rate", Modifier.weight(1f).fillMaxHeight(), Tone.NEUTRAL) { tab = "history" }
        StatTile("${summary.attended}", "Sessions attended", Modifier.weight(1f).fillMaxHeight(), Tone.NEUTRAL) { tab = "history" }
    }
    ChoiceChips(listOf("open" to "Open (${open.size})", "mine" to "My schedule (${mine.size})", "history" to "History"), tab) { tab = it }
    when (tab) {
        "open" -> {
            if (open.isEmpty()) EmptyState("No open registrations", "New activities appear here as soon as officers publish them. Closed registrations are hidden automatically.")
            open.forEach { v -> ActivityCard(v, { onOpen(v.id) }) }
        }
        "mine" -> {
            if (mine.isEmpty()) EmptyState("Nothing scheduled yet", "Register for an open activity and it will appear here.", "Browse open activities") { tab = "open" }
            mine.forEach { v -> ActivityCard(v, { onOpen(v.id) }) }
        }
        else -> {
            if (history.isEmpty()) EmptyState("No participation history yet", "Your attendance is added here after each activity you join.")
            history.forEach { v -> ActivityCard(v, { onOpen(v.id) }) }
            if (summary.total > 0) Text("Excused absences are not counted against your attendance rate.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ActivityDetailScreen(
    workspace: JsonObject, eventId: String, busy: Boolean, snackbar: SnackbarHostState, isMember: Boolean, isOfficer: Boolean,
    onBack: () -> Unit, onCommand: (String, JsonObject, () -> Unit) -> Unit, onRoster: () -> Unit, onEdit: (JsonObject) -> Unit,
    onAnnounce: (JsonObject) -> Unit,
) {
    val event = workspace.records("events").find { it.text("id") == eventId }
    if (event == null) {
        ScreenShell("Activity", onBack, snackbar) { EmptyState("This activity is no longer available", "It may have been archived or unpublished. Refresh and try again.", "Back", onBack) }
        return
    }
    val v = activityView(event, workspace)
    var confirm by remember { mutableStateOf<String?>(null) }
    var celebrate by remember { mutableStateOf(false) }
    ScreenShell(event.text("title"), onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(v.status.label, v.status.tone())
            if (isOfficer && event.text("published") != "true") StatusPill("Draft · not visible to members", Tone.NEUTRAL)
        }
        WorkCard("When and where", listOf(fmtRange(v.start, v.end), event.text("venue")), leading = { DateBlock(v.start) })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Slots", style = MaterialTheme.typography.titleMedium)
            CapacityBar(v.registered, v.capacity, v.waitlisted)
            Text(registrationWindowText(v), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (v.status) {
            ActivityStatus.REGISTERED -> NoticeCard("You're registered", "Your slot is confirmed. At the venue, scan the attendance code shown by the officer or ask them to mark you present. You can cancel until the activity starts.", Tone.POSITIVE)
            ActivityStatus.WAITLISTED -> NoticeCard("You're on the waitlist${v.waitlistPosition?.let { " · #$it" } ?: ""}", "If a registered member cancels before registration closes, the system registers you automatically and notifies you.", Tone.WARNING)
            ActivityStatus.ATTENDED -> NoticeCard("Attendance recorded", "You were marked present. This counts toward your participation record.", Tone.POSITIVE)
            ActivityStatus.MISSED -> NoticeCard("Marked absent", "If this is wrong, open the attendance record under Participation history and request a correction.", Tone.DANGER)
            else -> Unit
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("About this activity", style = MaterialTheme.typography.titleMedium)
            Text(event.text("description"), style = MaterialTheme.typography.bodyLarge)
        }
        if (event.text("requirements").isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What to bring", style = MaterialTheme.typography.titleMedium)
            Text(event.text("requirements"), style = MaterialTheme.typography.bodyLarge)
        }
        KeyValue("Officer in charge", event.text("assigned_personnel"))

        if (isMember) when {
            v.canRegister -> PrimaryAction(if (v.status == ActivityStatus.FULL) "Join waitlist" else "Register", !busy) { confirm = "register" }
            v.status == ActivityStatus.REGISTERED -> SecondaryAction("Cancel registration", !busy) { confirm = "cancel" }
            v.status == ActivityStatus.WAITLISTED -> SecondaryAction("Leave waitlist", !busy) { confirm = "cancel" }
            v.status == ActivityStatus.NOT_YET_OPEN -> PrimaryAction("Registration opens ${relative(v.opens)}", false) {}
            v.status == ActivityStatus.CLOSED -> PrimaryAction("Registration closed", false) {}
        }
        if (isOfficer) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Officer tools", style = MaterialTheme.typography.titleMedium)
            PrimaryAction("Manage participants (${v.registered})", !busy, onClick = onRoster)
            if (event.text("published") != "true") SecondaryAction("Publish to members", !busy) { confirm = "publish" }
            SecondaryAction("Edit activity details", !busy) { onEdit(event) }
            if (event.text("published") == "true") SecondaryAction("Post an announcement for this activity", !busy) { onAnnounce(event) }
        }
    }
    when (confirm) {
        "register" -> ConfirmDialog(
            if (v.status == ActivityStatus.FULL) "Join the waitlist?" else "Confirm registration",
            "${event.text("title")}\n${fmtRange(v.start, v.end)}\n${event.text("venue")}\n\n" +
                if (v.status == ActivityStatus.FULL) "All ${v.capacity} slots are taken. You'll be #${v.waitlisted + 1} in line and registered automatically if a slot opens."
                else "This uses 1 of the ${v.spotsLeft} remaining slots. You can cancel until the activity starts.",
            if (v.status == ActivityStatus.FULL) "Join waitlist" else "Register",
            onConfirm = { confirm = null; onCommand("register_event", buildJsonObject { put("event_id", eventId) }) { celebrate = true } },
            onDismiss = { confirm = null })
        "cancel" -> ConfirmDialog(
            if (v.status == ActivityStatus.WAITLISTED) "Leave the waitlist?" else "Cancel your registration?",
            if (v.status == ActivityStatus.WAITLISTED) "You'll lose your place in line." else "Your slot will be released and offered to the next member on the waitlist.",
            if (v.status == ActivityStatus.WAITLISTED) "Leave waitlist" else "Cancel registration",
            onConfirm = { confirm = null; onCommand("cancel_registration", buildJsonObject { put("event_id", eventId) }) {} },
            onDismiss = { confirm = null }, destructive = true)
        "publish" -> ConfirmDialog("Publish this activity?", "Active members will see it immediately and can register from ${fmtDateTime(v.opens)} until ${fmtDateTime(v.closes)}.", "Publish",
            onConfirm = { confirm = null; onCommand("publish_event", buildJsonObject { put("id", eventId); event["version"]?.let { put("version", it) } }) {} },
            onDismiss = { confirm = null })
    }
    if (celebrate) RegistrationResult(v, event, onDone = { celebrate = false })
}

/** Shown after the registration transaction returns; the status is read from the refreshed records. */
@Composable
private fun RegistrationResult(v: ActivityView, event: JsonObject, onDone: () -> Unit) {
    val (title, body) = when (v.status) {
        ActivityStatus.REGISTERED -> "You're in!" to "Your registration for ${event.text("title")} is confirmed. It's now in My schedule, and officers can see you on the participant list."
        ActivityStatus.WAITLISTED -> "You're on the waitlist" to "You're #${v.waitlistPosition ?: v.waitlisted} in line. We'll register you automatically if a slot opens and send you a notice."
        else -> "Confirming…" to "Saving your registration. This takes a moment."
    }
    AlertDialog(onDismissRequest = onDone, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(body)
            KeyValue("When", fmtRange(v.start, v.end)); KeyValue("Where", event.text("venue"))
        } },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } })
}

private fun registrationWindowText(v: ActivityView): String = when (v.status) {
    ActivityStatus.NOT_YET_OPEN -> "Registration opens ${fmtDateTime(v.opens)} (${relative(v.opens)})."
    ActivityStatus.CLOSED, ActivityStatus.IN_PROGRESS, ActivityStatus.ENDED -> "Registration closed ${fmtDateTime(v.closes)}."
    else -> if (v.closes != null) "Registration closes ${fmtDateTime(v.closes)} (${relative(v.closes)})." else ""
}

@Composable
fun NoticeCard(title: String, body: String, tone: Tone) {
    WorkCard(title, listOf(body), status = { StatusPill(when (tone) { Tone.POSITIVE -> "Confirmed"; Tone.WARNING -> "Pending"; Tone.DANGER -> "Action needed"; else -> "Info" }, tone) })
}

// ------------------------------------------------------------------ officer

/** Officer activity board: drafts, upcoming (with fill rate) and recent activities. */
@Composable
fun OfficerActivitiesContent(workspace: JsonObject, onOpen: (String) -> Unit, onCreate: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("upcoming") }
    val now = Instant.now()
    val all = workspace.records("events").filter { it.text("archived") != "true" }.map { activityView(it, workspace, now) }
    val drafts = all.filter { it.event.text("published") != "true" }.sortedBy { it.start }
    val upcoming = all.filter { it.event.text("published") == "true" && it.end?.isAfter(now) == true }.sortedBy { it.start }
    val past = all.filter { it.event.text("published") == "true" && it.end?.isAfter(now) != true }.sortedByDescending { it.start }
    PrimaryAction("Create activity", onClick = onCreate)
    TileRow {
        StatTile("${upcoming.size}", "Upcoming", Modifier.weight(1f).fillMaxHeight()) { tab = "upcoming" }
        StatTile("${upcoming.sumOf { it.registered }}", "Registrations", Modifier.weight(1f).fillMaxHeight())
        StatTile("${drafts.size}", "Drafts", Modifier.weight(1f).fillMaxHeight(), if (drafts.isNotEmpty()) Tone.WARNING else Tone.NEUTRAL) { tab = "drafts" }
    }
    ChoiceChips(listOf("upcoming" to "Upcoming", "drafts" to "Drafts", "past" to "Past"), tab) { tab = it }
    val list = when (tab) { "drafts" -> drafts; "past" -> past; else -> upcoming }
    if (list.isEmpty()) EmptyState(when (tab) { "drafts" -> "No drafts"; "past" -> "No past activities"; else -> "No upcoming activities" },
        "Create an activity, then publish it so members can register.")
    list.forEach { v -> ActivityCard(v, { onOpen(v.id) }, officer = true) }
}

/** Participant roster: confirmed members, attendance marking, waitlist order and the check-in code. */
@Composable
fun RosterScreen(
    workspace: JsonObject, eventId: String, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
    onCommand: (String, JsonObject, () -> Unit) -> Unit, onOpenCheckIn: (JsonObject) -> Unit,
) {
    val event = workspace.records("events").find { it.text("id") == eventId }
    if (event == null) { ScreenShell("Participants", onBack, snackbar) { EmptyState("Activity not found", "Refresh and try again.") }; return }
    val v = activityView(event, workspace)
    val names = workspace.records("member_directory").associate { it.text("user_id") to it.text("full_name") }
    val registrations = workspace.records("registrations").filter { it.text("event_id") == eventId }.sortedBy { it.text("created_at") }
    val attendance = workspace.records("attendance").filter { it.text("event_id") == eventId }.associateBy { it.text("user_id") }
    val waitlist = workspace.records("waitlist").filter { it.text("event_id") == eventId }.sortedBy { it.text("created_at") }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var walkIn by remember { mutableStateOf(false) }
    val present = attendance.values.count { it.text("status") == "present" }
    val absent = attendance.values.count { it.text("status") == "absent" }
    val unmarked = registrations.count { it.text("user_id") !in attendance.keys }
    val window = workspace.records("attendance_windows").filter { it.text("event_id") == eventId }
        .filter { val now = Instant.now(); (it.instant("opens_at")?.isBefore(now) ?: false) && (it.instant("closes_at")?.isAfter(now) ?: false) }
        .maxByOrNull { it.text("closes_at") }
    ScreenShell("Participants", onBack, snackbar) {
        Text(event.text("title"), style = MaterialTheme.typography.titleLarge)
        Text(fmtRange(v.start, v.end), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TileRow {
            StatTile("${registrations.size}/${v.capacity}", "Registered", Modifier.weight(1f).fillMaxHeight())
            StatTile("$present", "Present", Modifier.weight(1f).fillMaxHeight(), Tone.POSITIVE) { filter = "present" }
            StatTile("$unmarked", "Not yet marked", Modifier.weight(1f).fillMaxHeight(), if (unmarked > 0) Tone.WARNING else Tone.NEUTRAL) { filter = "unmarked" }
        }
        if (registrations.isNotEmpty() && attendance.isNotEmpty()) {
            val excusedCount = attendance.values.count { it.text("status") == "excused" }
            val rate = attendanceRate(present, registrations.size, excusedCount)
            Text("Attendance rate ${rate?.let { "$it%" } ?: "—"} · $absent absent · $excusedCount excused (not counted)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (window != null) {
            WorkCard("Self check-in is open", listOf("Members scan this code to mark themselves present. Closes ${fmtTime(window.instant("closes_at"))}."),
                footer = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CheckInQr("archeryclub://attendance?token=${window.text("token")}") } })
        } else if (event.text("published") == "true") SecondaryAction("Open self check-in (QR)", !busy) { onOpenCheckIn(event) }

        if (walkInWindowOpen(event)) SecondaryAction("Add a walk-in (not registered)", !busy) { walkIn = true }
        SectionHeader("Registered members")
        OutlinedTextField(search, { search = it }, label = { Text("Search by name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChoiceChips(listOf("all" to "All", "unmarked" to "Not marked", "present" to "Present", "absent" to "Absent", "excused" to "Excused"), filter) { filter = it }
        val shown = registrations.filter { r ->
            val status = attendance[r.text("user_id")]?.text("status") ?: "unmarked"
            (filter == "all" || filter == status) && names[r.text("user_id")].orEmpty().contains(search, true)
        }
        if (registrations.isEmpty()) EmptyState("No registrations yet", "Members who register in the app appear here instantly.")
        else if (shown.isEmpty()) Text("No members match this filter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        shown.forEach { r ->
            val uid = r.text("user_id")
            val record = attendance[uid]
            val status = record?.text("status").orEmpty()
            WorkCard(names[uid].orEmpty().ifBlank { "Club member" },
                listOf("Registered ${fmtDateTime(r.instant("created_at"))}", record?.let { "Marked ${it.text("method")} · ${it.text("remark")}" }.orEmpty()),
                status = {
                    StatusPill(if (status.isBlank()) "Not marked" else human(status), when (status) { "present" -> Tone.POSITIVE; "absent" -> Tone.DANGER; "excused" -> Tone.WARNING; else -> Tone.NEUTRAL })
                    val noShows = recentNoShows(workspace, uid)
                    if (noShows >= 2) StatusPill("$noShows no-shows in 60 days", Tone.DANGER)
                },
                footer = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("present" to "Present", "absent" to "Absent", "excused" to "Excused").forEach { (value, label) ->
                            FilterChip(selected = status == value, enabled = !busy && status != value, label = { Text(label) }, onClick = {
                                onCommand("record_attendance", buildJsonObject {
                                    put("event_id", eventId); put("user_id", uid); put("status", value)
                                    put("remark", "Marked $value from the participant roster")
                                    record?.get("version")?.let { put("version", it) }
                                }) {}
                            })
                        }
                    }
                })
        }
        if (waitlist.isNotEmpty()) {
            SectionHeader("Waitlist (${waitlist.size})")
            Text("Promoted automatically, in this order, when a registered member cancels before the deadline.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            waitlist.forEachIndexed { i, w ->
                WorkCard("${i + 1}. ${names[w.text("user_id")].orEmpty().ifBlank { "Club member" }}", listOf("Joined ${fmtDateTime(w.instant("created_at"))}"))
            }
        }
    }
    if (walkIn) {
        val registeredIds = registrations.map { it.text("user_id") }.toSet()
        val candidates = workspace.records("member_directory").filter { it.text("status") == "Active" && it.text("user_id") !in registeredIds }
        var picked by remember { mutableStateOf("") }
        ConfirmDialog("Record a walk-in", "Adds an active member who came without registering and marks them present. Capacity does not block walk-ins.",
            "Mark present", confirmEnabled = picked.isNotBlank(), onDismiss = { walkIn = false },
            onConfirm = { walkIn = false; onCommand("record_walk_in", buildJsonObject { put("event_id", eventId); put("user_id", picked) }) {} },
            extra = {
                if (candidates.isEmpty()) Text("Every active member is already on the list.")
                else SelectField("Member", picked, candidates.map { it.text("user_id") to it.text("full_name") }) { picked = it }
            })
    }
}

@Composable
fun CheckInQr(value: String) {
    val bitmap = remember(value) {
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 480, 480)
        Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 480) for (x in 0 until 480) setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    Image(bitmap.asImageBitmap(), contentDescription = "Attendance check-in code", modifier = Modifier.size(220.dp))
}
