package ph.capstone.archeryclub.connected

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.DiscardDialog
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.Instant

// ------------------------------------------------------------------ profile

private const val PROFILE_FIELDS = "full_name,phone,emergency_contact_name,emergency_contact_phone,program,year_level,dominant_hand,bow_type,draw_weight,arrow_length,experience_level,bio"

@Composable
fun ProfileScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, onEdit: () -> Unit,
                  onRenewal: () -> Unit, onActivities: () -> Unit, onEquipment: () -> Unit) {
    val user = workspace.text("user_id")
    val p = workspace.records("profiles").find { it.text("id") == user }
    val m = workspace.records("memberships").find { it.text("user_id") == user }
    val summary = participationSummary(workspace)
    val completeness = profileCompleteness(p)
    ScreenShell("My profile", onBack, snackbar) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Text(p?.text("full_name").orEmpty().split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(),
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p?.text("full_name").orEmpty().ifBlank { "Club member" }, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(m?.text("status")?.let { "$it member" } ?: "No membership", if (m?.text("status") == "Active") Tone.POSITIVE else Tone.WARNING)
                    m?.text("category")?.takeIf { it.isNotBlank() }?.let { StatusPill(it, Tone.NEUTRAL) }
                }
            }
        }
        pendingNameCorrection(workspace)?.let {
            NoticeCard("Name correction pending", "You asked to change your name to \"${it.text("requested_name")}\". A membership officer will review it.", Tone.WARNING)
        }
        if (completeness < 100) {
            WorkCard("Profile $completeness% complete", listOf("Add your archery details so equipment requests can be checked for fit automatically."),
                footer = { SlotBar(completeness, 100); TextButton(onClick = onEdit) { Text("Complete profile") } })
        }
        TileRow {
            StatTile("${summary.attended}", "Attended", Modifier.weight(1f).fillMaxHeight(), onClick = onActivities)
            StatTile(summary.rate?.let { "$it%" } ?: "—", "Attendance", Modifier.weight(1f).fillMaxHeight(), onClick = onActivities)
            StatTile("${workspace.records("borrowings").count { it.text("user_id") == user && it.text("status") in setOf("released", "returned") }}", "Borrowings", Modifier.weight(1f).fillMaxHeight(), onClick = onEquipment)
        }
        SectionHeader("Contact", "Edit", onEdit)
        RaisedCard { Column(Modifier.padding(16.dp)) {
            KeyValue("Mobile", p?.text("phone").orEmpty().ifBlank { "Not set" })
            KeyValue("Emergency contact", listOf(p?.text("emergency_contact_name").orEmpty(), p?.text("emergency_contact_phone").orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Not set" })
            KeyValue("Program", listOf(p?.text("program").orEmpty(), p?.text("year_level").orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Not set" })
        } }
        SectionHeader("Archery", "Edit", onEdit)
        RaisedCard { Column(Modifier.padding(16.dp)) {
            KeyValue("Shooting hand", p?.text("dominant_hand").orEmpty().ifBlank { "Not set" }.let(::human))
            KeyValue("Bow type", p?.text("bow_type").orEmpty().ifBlank { "Not set" }.let(::human))
            KeyValue("Draw weight", p?.text("draw_weight").orEmpty().ifBlank { null }?.let { "$it lb" } ?: "Not set")
            KeyValue("Arrow length", p?.text("arrow_length").orEmpty().ifBlank { null }?.let { "$it in" } ?: "Not set")
            KeyValue("Level", p?.text("experience_level").orEmpty().ifBlank { "Not set" }.let(::human))
            KeyValue("About", p?.text("bio").orEmpty())
        } }
        if (m != null) {
            SectionHeader("Membership")
            RaisedCard { Column(Modifier.padding(16.dp)) {
                KeyValue("Category", m.text("category")); KeyValue("Status", m.text("status"))
                KeyValue("Student number", m.text("student_number"))
                KeyValue("Member since", fmtDay(m.instant("approved_at")))
            } }
            SecondaryAction("Renewal and membership records", !busy, onRenewal)
        }
    }
}

@Composable
fun ProfileEditScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, onSave: (JsonObject) -> Unit,
                      onCommand: (String, JsonObject, () -> Unit) -> Unit) {
    val user = workspace.text("user_id")
    val p = workspace.records("profiles").find { it.text("id") == user }
    val initial = remember(p) { PROFILE_FIELDS.split(",").associateWith { p?.text(it).orEmpty() } }
    val values = rememberSaveable(saver = mapSaver(save = { it.toMap() }, restore = { saved -> mutableStateMapOf<String, String>().apply { saved.forEach { (k, v) -> put(k, v as String) } } })) {
        mutableStateMapOf<String, String>().apply { putAll(initial) }
    }
    var attempted by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var correction by remember { mutableStateOf(false) }
    val locked = nameLocked(workspace)
    val pending = pendingNameCorrection(workspace)
    val errors = profileErrors(values)
    val changed = values.toMap() != initial
    val leave = { if (changed) discard = true else onBack() }
    BackHandler(onBack = leave)
    if (discard) DiscardDialog(onDismiss = { discard = false }, onDiscard = { discard = false; onBack() })
    @Composable fun field(key: String, label: String, keyboard: KeyboardType = KeyboardType.Text, lines: Int = 1, hint: String = "") =
        OutlinedTextField(values[key].orEmpty(), { values[key] = if (key == "phone") mobileInput(it) else it }, label = { Text(label) }, isError = attempted && key in errors,
            supportingText = { (if (attempted) errors[key] else null)?.let { Text(it) } ?: if (hint.isNotBlank()) Text(hint) else Unit },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), singleLine = lines == 1, minLines = lines, modifier = Modifier.fillMaxWidth())
    ScreenShell("Edit profile", leave, snackbar, actions = { CompletionPill(profileCompleteness(p)) }, stickyAction = {
        if (attempted && errors.isNotEmpty()) Text("Fix the highlighted fields.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        PrimaryAction(if (busy) "Saving…" else if (changed) "Save changes" else "No changes yet", !busy && changed) {
            attempted = true
            if (errors.isEmpty()) onSave(buildJsonObject { values.forEach { (k, v) -> put(k, v.trim()) } })
        }
    }) {
        SectionHeader("Personal")
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (locked) {
            WorkCard(values["full_name"].orEmpty(), listOf("Full name · verified when your membership was approved. It appears on rosters, borrowings and ballots, so changes go through the membership officer."),
                status = { if (pending != null) StatusPill("Correction to \"${pending.text("requested_name")}\" pending", Tone.WARNING) else StatusPill("Verified", Tone.POSITIVE) },
                footer = { if (pending == null) TextButton(onClick = { correction = true }, enabled = !busy) { Text("Request name correction") } })
        } else field("full_name", "Full name")
        field("phone", "Mobile number", KeyboardType.Phone, hint = "e.g. 0917 123 4567")
        field("program", "Program / course", hint = "e.g. BS Information Technology")
        Text("Year level", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(listOf("1st year", "2nd year", "3rd year", "4th year", "5th year", "Alumni").map { it to it }, values["year_level"].orEmpty()) { values["year_level"] = it }
        } }
        SectionHeader("Emergency contact")
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        field("emergency_contact_name", "Contact name")
        field("emergency_contact_phone", "Contact number", KeyboardType.Phone)
        } }
        SectionHeader("Archery details")
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Used to check bow fit when you borrow equipment.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text("Shooting hand", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(listOf("right" to "Right", "left" to "Left"), values["dominant_hand"].orEmpty()) { values["dominant_hand"] = it }
        Text("Bow type", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(listOf("recurve", "compound", "barebow", "traditional", "longbow").map { it to human(it) }, values["bow_type"].orEmpty()) { values["bow_type"] = it }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { field("draw_weight", "Max draw weight (lb)", KeyboardType.Decimal) }
            Box(Modifier.weight(1f)) { field("arrow_length", "Arrow length (in)", KeyboardType.Decimal) }
        }
        Text("Experience", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(listOf("beginner", "intermediate", "advanced", "competitive").map { it to human(it) }, values["experience_level"].orEmpty()) { values["experience_level"] = it }
        field("bio", "About you (optional)", lines = 3, hint = "${values["bio"].orEmpty().length}/500")
        } }
    }
    if (correction) NameCorrectionDialog(values["full_name"].orEmpty(), onDismiss = { correction = false }) { requested, reason ->
        correction = false
        onCommand("request_name_correction", buildJsonObject { put("requested_name", requested); put("reason", reason) }) {}
    }
}

@Composable
private fun NameCorrectionDialog(current: String, onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var requested by remember { mutableStateOf(current) }
    var reason by remember { mutableStateOf("") }
    ConfirmDialog("Request a name correction",
        "A membership officer checks the request against your records. Your name changes only after it is approved.",
        "Send request", onConfirm = { onSubmit(requested.trim(), reason.trim()) }, onDismiss = onDismiss,
        confirmEnabled = requested.isNotBlank() && requested.trim() != current.trim() && reason.isNotBlank(),
        extra = {
            OutlinedTextField(requested, { requested = it }, label = { Text("Correct full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(reason, { reason = it }, label = { Text("Reason") }, placeholder = { Text("e.g. Surname misspelled on application") },
                minLines = 2, modifier = Modifier.fillMaxWidth())
        })
}

/** Membership officer queue: compare current and requested names, then approve or reject with a reason. */
@Composable
fun NameCorrectionsScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
                          onCommand: (String, JsonObject, () -> Unit) -> Unit) {
    val user = workspace.text("user_id")
    val all = workspace.records("name_corrections").sortedByDescending { it.text("created_at") }
    val pending = all.filter { it.text("status") == "pending" }
    var deciding by remember { mutableStateOf<Pair<JsonObject, String>?>(null) }
    ScreenShell("Name corrections", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Members cannot rename themselves after approval. Check each request against the member's records.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        SectionHeader("Pending (${pending.size})")
        if (pending.isEmpty()) EmptyState("No pending requests", "Requests from members appear here and on your dashboard.")
        pending.forEach { c ->
            WorkCard("${c.text("current_name")} → ${c.text("requested_name")}", listOf("Reason: ${c.text("reason")}", "Requested ${fmtDateTime(c.instant("created_at"))}"),
                status = { StatusPill("Pending", Tone.WARNING) },
                footer = {
                    if (c.text("user_id") == user) Text("You cannot review your own request.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { deciding = c to "approved" }, enabled = !busy) { Text("Approve") }
                        OutlinedButton(onClick = { deciding = c to "rejected" }, enabled = !busy) { Text("Reject") }
                    }
                })
        }
        val decided = all.filter { it.text("status") != "pending" }.take(20)
        if (decided.isNotEmpty()) {
            SectionHeader("Decided")
            decided.forEach { c ->
                WorkCard("${c.text("current_name")} → ${c.text("requested_name")}", listOf(c.text("decision"), fmtDateTime(c.instant("reviewed_at"))),
                    status = { StatusPill(human(c.text("status")), if (c.text("status") == "approved") Tone.POSITIVE else Tone.NEUTRAL) })
            }
        }
    }
    deciding?.let { (c, status) ->
        var decision by remember(c, status) { mutableStateOf(if (status == "approved") "Checked against the member's records" else "") }
        ConfirmDialog(if (status == "approved") "Approve name change?" else "Reject request?",
            if (status == "approved") "The member's name becomes \"${c.text("requested_name")}\" everywhere: rosters, borrowings, ballots and reports."
            else "The member keeps their current name and sees your reason.",
            if (status == "approved") "Approve" else "Reject", destructive = status == "rejected",
            confirmEnabled = decision.isNotBlank(), onDismiss = { deciding = null },
            onConfirm = {
                deciding = null
                onCommand("review_name_correction", buildJsonObject { put("id", c.text("id")); put("status", status); put("decision", decision.trim()) }) {}
            },
            extra = { OutlinedTextField(decision, { decision = it }, label = { Text("Decision note") }, minLines = 2, modifier = Modifier.fillMaxWidth()) })
    }
}

// ------------------------------------------------------------------ officer dashboard

@Composable
fun OfficerDashboardContent(workspace: JsonObject, permissions: Set<String>, onRoute: (String) -> Unit) {
    val now = Instant.now()
    val queues = officerQueues(workspace, permissions, now)
    val open = queues.filter { it.count > 0 }
    val members = workspace.records("member_directory").count { it.text("status") == "Active" }
    val events = workspace.records("events").filter { it.text("published") == "true" && it.text("archived") != "true" }.map { activityView(it, workspace, now) }
    val upcoming = events.filter { it.end?.isAfter(now) == true }.sortedBy { it.start }
    val weekEnd = now.plusSeconds(7 * 86400L)
    val past30 = events.filter { it.end?.isBefore(now) == true && it.end.isAfter(now.minusSeconds(30 * 86400L)) }
    val rate30 = attendanceRate(past30.sumOf { it.present }, past30.sumOf { it.registered }, past30.sumOf { it.excused })
    SectionHeader("Today")
    TileRow {
        StatTile("$members", "Active members", Modifier.weight(1f).fillMaxHeight()) { if ("membership" in permissions) onRoute("module:7") }
        StatTile("${upcoming.count { it.start?.isBefore(weekEnd) == true }}", "Activities this week", Modifier.weight(1f).fillMaxHeight()) { if ("activities" in permissions) onRoute("officer_activities") }
    }
    TileRow {
        StatTile(rate30?.let { "$it%" } ?: "—", "Attendance, last 30 days", Modifier.weight(1f).fillMaxHeight()) { if ("reports" in permissions) onRoute("module:13") }
        if ("equipment" in permissions) {
            val items = workspace.records("equipment").filter { it.text("condition") != "retired" }
            StatTile("${items.count { equipmentState(it, workspace) == EquipmentState.AVAILABLE }}/${items.size}", "Equipment available", Modifier.weight(1f).fillMaxHeight()) { onRoute("equipment") }
        } else StatTile("${upcoming.sumOf { it.registered }}", "Upcoming registrations", Modifier.weight(1f).fillMaxHeight())
    }
    SectionHeader("Needs your action")
    if (open.isEmpty()) EmptyState("You're all caught up", "Requests from members appear here as soon as they're submitted.")
    open.sortedByDescending { if (it.tone == Tone.DANGER) 1000 + it.count else it.count }.forEach { q ->
        WorkCard(q.label, emptyList(), status = { StatusPill("${q.count} waiting", q.tone) }, onClick = { onRoute(q.route) })
    }
    if ("activities" in permissions) {
        SectionHeader("Next activities", "See all") { onRoute("officer_activities") }
        if (upcoming.isEmpty()) EmptyState("No published activities", "Create one and publish it so members can register.", "Create activity") { onRoute("officer_activities") }
        upcoming.take(3).forEach { ActivityCard(it, { onRoute("activity:${it.id}") }, officer = true) }
    }
}
