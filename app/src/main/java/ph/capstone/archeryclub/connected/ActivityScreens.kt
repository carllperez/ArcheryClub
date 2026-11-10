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
import ph.capstone.archeryclub.ui.Club
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant

/* Activities: member discovery → registration/waitlist → attendance → history,
   and the officer side: activity board → participant roster → attendance. */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityCard(view: ActivityView, onClick: () -> Unit, officer: Boolean = false) {
    RaisedCard(radius = 20.dp, onClick = onClick) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DateBlock(view.start, muted = !officer && !view.status.isMine)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(view.event.text("title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(listOf(fmtTime(view.start), fmtTime(view.end)).filter { it.isNotBlank() }.joinToString("–"), view.event.text("venue"))
                    .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (view.capacity > 0) SlotBar(view.registered, view.capacity)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (officer) {
                        StatusPill(if (view.event.text("published") == "true") "Published" else "Draft", if (view.event.text("published") == "true") Tone.POSITIVE else Tone.NEUTRAL)
                        StatusPill("${view.registered}/${view.capacity} registered", if (view.registered >= view.capacity && view.capacity > 0) Tone.WARNING else Tone.NEUTRAL)
                        if (view.waitlisted > 0) StatusPill("${view.waitlisted} waitlisted", Tone.WARNING)
                    } else {
                        StatusPill((if (view.status == ActivityStatus.REGISTERED) "✓ " else "") + view.status.label, view.status.tone())
                        if (view.status in setOf(ActivityStatus.OPEN, ActivityStatus.CLOSING_SOON)) StatusPill("${view.spotsLeft} slot${if (view.spotsLeft == 1) "" else "s"} left", Tone.NEUTRAL)
                        if (view.status == ActivityStatus.FULL) StatusPill("Join waitlist${if (view.waitlisted > 0) " · ${view.waitlisted} waiting" else ""}", Tone.NEUTRAL)
                        if (view.status == ActivityStatus.WAITLISTED && view.waitlistPosition != null) StatusPill("#${view.waitlistPosition} in line", Tone.NEUTRAL)
                        if (view.status == ActivityStatus.CLOSING_SOON) StatusPill("Closes ${relative(view.closes)}", Tone.WARNING)
                    }
                }
            }
        }
    }
}

/** Thin capacity bar for cards; animates when counts change. */
@Composable
fun SlotBar(registered: Int, capacity: Int) {
    val f = if (capacity <= 0) 0f else (registered.toFloat() / capacity).coerceIn(0f, 1f)
    val animated by animateFloatAsState(f, tween(600), label = "slots")
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(animated).clip(CircleShape).background(if (f >= 1f) Club.amberInk else Club.forest))
    }
}

/** Member "Activities" list for the chosen tab: open for registration, my schedule, or history. */
@Composable
fun MemberActivitiesList(workspace: JsonObject, tab: String, onTab: (String) -> Unit, onOpen: (String) -> Unit) {
    val now = Instant.now()
    val all = memberActivities(workspace, now)
    val open = all.filter { !it.status.isMine }
    val mine = upcomingMine(workspace, now)
    val history = participationHistory(workspace, now)
    val summary = participationSummary(workspace, now)
    AnimatedContent(tab, transitionSpec = { fadeIn(tween(220, 60)) togetherWith fadeOut(tween(90)) }, label = "activity-tab") { t ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (t) {
                "open" -> {
                    if (open.isEmpty()) EmptyState("No open registrations", "New activities appear here as soon as officers publish them. Closed registrations are hidden automatically.")
                    val (week, later) = open.partition { it.start?.isBefore(now.plus(java.time.Duration.ofDays(7))) == true }
                    if (week.isNotEmpty()) SectionHeader("This week")
                    week.forEach { v -> ActivityCard(v, { onOpen(v.id) }) }
                    if (later.isNotEmpty()) SectionHeader("Later")
                    later.forEach { v -> ActivityCard(v, { onOpen(v.id) }) }
                    if (open.isNotEmpty()) Text("Closed sessions are hidden. Past ones are under History.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                }
                "mine" -> {
                    if (mine.isEmpty()) EmptyState("Nothing scheduled yet", "Register for an open activity and it will appear here.", "Browse open activities") { onTab("open") }
                    mine.forEach { v -> ActivityCard(v, { onOpen(v.id) }) }
                }
                else -> {
                    TileRow {
                        StatTile(summary.rate?.let { "$it%" } ?: "—", "Attendance rate", Modifier.weight(1f).fillMaxHeight(), Tone.POSITIVE)
                        StatTile("${summary.attended}", "Sessions attended", Modifier.weight(1f).fillMaxHeight())
                    }
                    if (history.isEmpty()) EmptyState("No participation history yet", "Your attendance is added here after each activity you join.")
                    history.forEach { v -> ActivityCard(v, { onOpen(v.id) }) }
                    if (summary.total > 0) Text("Excused absences are not counted against your attendance rate.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Older entry point (kept for the activities route): tabs as chips above the list. */
@Composable
fun MemberActivitiesContent(workspace: JsonObject, onOpen: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("open") }
    ChoiceChips(listOf("open" to "Open", "mine" to "My schedule", "history" to "History"), tab) { tab = it }
    MemberActivitiesList(workspace, tab, { tab = it }, onOpen)
}

@Composable
fun ActivityDetailScreen(
    workspace: JsonObject, eventId: String, busy: Boolean, snackbar: SnackbarHostState, isMember: Boolean, isOfficer: Boolean,
    onBack: () -> Unit, onCommand: (String, JsonObject, () -> Unit) -> Unit, onRoster: () -> Unit, onEdit: (JsonObject) -> Unit,
    onAnnounce: (JsonObject) -> Unit, onBorrow: () -> Unit = {},
) {
    val event = workspace.records("events").find { it.text("id") == eventId }
    if (event == null) {
        ScreenShell("Activity", onBack, snackbar) { EmptyState("This activity is no longer available", "It may have been archived or unpublished. Refresh and try again.", "Back", onBack) }
        return
    }
    val v = activityView(event, workspace)
    var confirm by remember { mutableStateOf<String?>(null) }
    var celebrate by remember { mutableStateOf(false) }
    HeroScaffold(event.text("title"), onBack, snackbar, busy = busy,
        sticky = if (isMember) ({ ActivitySticky(v, busy) { confirm = it } }) else null,
        eyebrow = {
            StatusPill(v.status.label.uppercase(), v.status.tone())
            if (isOfficer && event.text("published") != "true") StatusPill("DRAFT", Tone.NEUTRAL)
        }) {
        RaisedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                InfoLine("calendar", fmtRange(v.start, v.end), countdownLabel(v.start).takeIf { it.isNotBlank() && v.end?.isAfter(Instant.now()) == true }.orEmpty())
                InfoLine("pin", event.text("venue").ifBlank { "Venue to be confirmed" },
                    event.text("assigned_personnel").takeIf { it.isNotBlank() }?.let { "Officer in charge: $it" }.orEmpty())
            }
        }
        if (v.capacity > 0) RaisedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Slots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Text("${v.registered}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text(" / ${v.capacity}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CapacityBar(v.registered, v.capacity, v.waitlisted)
                registrationWindowText(v).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        when (v.status) {
            ActivityStatus.REGISTERED -> NoticeCard("You're registered", "At the venue, scan the attendance code shown by the officer or ask them to mark you present. You can cancel until the activity starts.", Tone.POSITIVE)
            ActivityStatus.WAITLISTED -> NoticeCard("You're on the waitlist${v.waitlistPosition?.let { " · #$it" } ?: ""}", "If a registered member cancels before the activity starts, the system registers you automatically and notifies you.", Tone.WARNING)
            ActivityStatus.ATTENDED -> NoticeCard("Attendance recorded", "You were marked present. This counts toward your participation record.", Tone.POSITIVE)
            ActivityStatus.MISSED -> NoticeCard("Marked absent", "If this is wrong, open the attendance record under Participation history and request a correction.", Tone.DANGER)
            else -> Unit
        }
        RaisedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Text(event.text("description"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (event.text("requirements").isNotBlank()) {
                    Text("Bring", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 6.dp))
                    Text(event.text("requirements"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (isMember && v.status in setOf(ActivityStatus.REGISTERED, ActivityStatus.OPEN, ActivityStatus.CLOSING_SOON, ActivityStatus.WAITLISTED))
            HintRow("bow", "No bow? Reserve club gear for this session.", "Borrow", onBorrow)
        if (isOfficer) {
            SectionHeader("Officer tools")
            RowGroup {
                GroupRow("users", "Manage participants", "${v.registered}", onClick = onRoster)
                if (event.text("published") != "true") GroupRow("share", "Publish to members", onClick = { if (!busy) confirm = "publish" })
                GroupRow("settings", "Edit activity details", onClick = { onEdit(event) })
                if (event.text("published") == "true") GroupRow("bell", "Post an announcement", divider = false, onClick = { onAnnounce(event) })
            }
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
    if (celebrate) RegistrationResult(v, event, onDone = { celebrate = false }, onBorrow = onBorrow)
}

/** Bottom bar on a session: what your slot looks like and the one action that fits it. */
@Composable
private fun RowScope.ActivitySticky(v: ActivityView, busy: Boolean, onConfirm: (String) -> Unit) {
    Column(Modifier.weight(1f)) {
        Text(when (v.status) { ActivityStatus.REGISTERED -> "Your slot is confirmed"; ActivityStatus.WAITLISTED -> "On the waitlist"; else -> "Free for members" },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when {
            v.status == ActivityStatus.WAITLISTED -> "#${v.waitlistPosition ?: v.waitlisted} in line"
            v.capacity > 0 && v.spotsLeft > 0 -> "${v.spotsLeft} slot${if (v.spotsLeft == 1) "" else "s"} left"
            v.capacity > 0 -> "Full · ${v.waitlisted} waiting"
            else -> v.status.label
        }, style = MaterialTheme.typography.titleSmall)
    }
    when {
        v.canRegister -> StickyButton(if (v.status == ActivityStatus.FULL) "Join waitlist" else "Register", !busy) { onConfirm("register") }
        v.status == ActivityStatus.REGISTERED -> OutlinedButton(onClick = { onConfirm("cancel") }, enabled = !busy, shape = CircleShape,
            modifier = Modifier.heightIn(min = 48.dp), border = BorderStroke(1.5.dp, Club.roseSoft)) { Text("Cancel slot", color = Club.roseInk) }
        v.status == ActivityStatus.WAITLISTED -> OutlinedButton(onClick = { onConfirm("cancel") }, enabled = !busy, shape = CircleShape,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Leave waitlist") }
        v.status == ActivityStatus.NOT_YET_OPEN -> StickyButton("Opens ${relative(v.opens)}", false) {}
        else -> StickyButton(v.status.label, false) {}
    }
}

/** Full-screen confirmation after the registration transaction; the status is read from the refreshed records. */
@Composable
private fun RegistrationResult(v: ActivityView, event: JsonObject, onDone: () -> Unit, onBorrow: () -> Unit) {
    val waitlisted = v.status == ActivityStatus.WAITLISTED
    val (title, body) = when (v.status) {
        ActivityStatus.REGISTERED -> "You're in!" to "Slot ${v.registered} of ${v.capacity} is yours for ${event.text("title")}."
        ActivityStatus.WAITLISTED -> "You're on the waitlist" to "You're #${v.waitlistPosition ?: v.waitlisted} in line. We'll register you automatically if a slot opens."
        else -> "Confirming…" to "Saving your registration. This takes a moment."
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            HeroBand(bottomPadding = 80.dp) {
                Row { ph.capstone.archeryclub.ui.RoundButton("close", "Close", onDone, onDark = true) }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnimatedVisibility(shown, enter = scaleIn(spring(dampingRatio = .55f, stiffness = 300f), initialScale = .4f) + fadeIn()) {
                        Box(Modifier.size(76.dp).clip(CircleShape).background(if (waitlisted) Club.amberSoft else Club.lime), contentAlignment = Alignment.Center) {
                            ph.capstone.archeryclub.ui.ClubIcon(if (waitlisted) "clock" else "check", Modifier.size(36.dp), Club.forestDeep)
                        }
                    }
                    Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .9f), textAlign = TextAlign.Center)
                }
            }
            Column(Modifier.offset(y = (-56).dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RaisedCard {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        DateBlock(v.start)
                        Column(Modifier.weight(1f)) {
                            Text(event.text("title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                            Text("${fmtTime(v.start)} · ${event.text("venue")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                SectionHeader("What's next")
                RowGroup {
                    GroupRow("bow", "Borrow a bow if you need one", onClick = { onDone(); onBorrow() })
                    GroupRow("check", "The officer marks attendance on the day", onClick = null)
                    GroupRow("close", "Can't go? Cancel so the next person gets your slot", divider = false, onClick = null)
                }
                PrimaryAction("Done", onClick = onDone)
            }
        }
    }
}

/** Icon + two-line fact used inside detail cards. */
@Composable
fun InfoLine(icon: String, title: String, subtitle: String = "") {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Club.mint), contentAlignment = Alignment.Center) {
            ph.capstone.archeryclub.ui.ClubIcon(icon, Modifier.size(20.dp), Club.forest)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Mint suggestion strip with an action word on the right. */
@Composable
fun HintRow(icon: String, text: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Club.mint).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ph.capstone.archeryclub.ui.ClubIcon(icon, Modifier.size(22.dp), Club.forestDeep)
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Club.forestDeep, modifier = Modifier.weight(1f))
        Text("$action ›", style = MaterialTheme.typography.labelLarge, color = Club.forestDeep)
    }
}

private fun registrationWindowText(v: ActivityView): String = when (v.status) {
    ActivityStatus.NOT_YET_OPEN -> "Registration opens ${fmtDateTime(v.opens)} (${relative(v.opens)})."
    ActivityStatus.CLOSED, ActivityStatus.IN_PROGRESS, ActivityStatus.ENDED -> "Registration closed ${fmtDateTime(v.closes)}."
    else -> if (v.closes != null) "Registration closes ${fmtDateTime(v.closes)} (${relative(v.closes)})." else ""
}

@Composable
fun NoticeCard(title: String, body: String, tone: Tone) {
    val (bg, ink, icon) = when (tone) {
        Tone.POSITIVE -> Triple(Club.mint, Club.forestDeep, "check")
        Tone.WARNING -> Triple(Club.amberSoft, Club.amberInk, "clock")
        Tone.DANGER -> Triple(Club.roseSoft, Club.roseInk, "warning")
        else -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, "bell")
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ph.capstone.archeryclub.ui.ClubIcon(icon, Modifier.size(20.dp), ink)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = ink)
            Text(body, style = MaterialTheme.typography.bodySmall, color = ink)
        }
    }
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
            Text("Promoted automatically, in this order, when a registered member cancels before the activity starts.",
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
