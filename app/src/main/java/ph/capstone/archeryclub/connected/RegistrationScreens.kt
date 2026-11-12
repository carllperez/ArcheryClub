package ph.capstone.archeryclub.connected

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.DiscardDialog
import ph.capstone.archeryclub.ui.ScreenShell

/* Membership registration, end to end:
 *   applicant: details → documents → confirm and submit → follow screening → pay fee → Active
 *   officer:   queue (oldest first) → open documents → verify each → decide with a reason
 * Every transition is a server command; these screens only show the next step and why a button is disabled. */

private val UPLOAD_TYPES = arrayOf("application/pdf", "image/jpeg", "image/png")

@Composable
private fun StepHeader(number: Int, title: String, done: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = androidx.compose.foundation.shape.CircleShape, color = if (done) ph.capstone.archeryclub.ui.Club.forest else ph.capstone.archeryclub.ui.Club.mint) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                if (done) ph.capstone.archeryclub.ui.ClubIcon("check", Modifier.size(16.dp), androidx.compose.ui.graphics.Color.White)
                else Text("$number", style = MaterialTheme.typography.labelLarge, color = ph.capstone.archeryclub.ui.Club.forestDeep)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
    }
}

@Composable
private fun Milestones(items: List<Milestone>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { m ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (m.done) "●" else if (m.current) "◉" else "○",
                    color = if (m.done || m.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                Text(m.title, style = if (m.current) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (m.done || m.current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RequirementRow(req: RequirementView, canUpload: Boolean, busy: Boolean, onOpen: (String) -> Unit, onUpload: () -> Unit,
                           canVerify: Boolean = false, onVerify: (JsonObject) -> Unit = {}) {
    WorkCard(req.name, listOf(req.latest?.text("name")?.let { "Latest file: $it" } ?: "No file uploaded yet",
        if (req.documents.size > 1) "${req.documents.size} current files" else ""),
        status = { StatusPill(req.state.label, req.state.tone) },
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                req.latest?.let { d -> TextButton(onClick = { onOpen(d.text("path")) }, enabled = !busy) { Text("Open") } }
                if (canUpload) TextButton(onClick = onUpload, enabled = !busy) { Text(if (req.documents.isEmpty()) "Upload" else "Replace") }
                if (canVerify) req.documents.filter { it.text("verified_by").isBlank() }.forEach { d ->
                    TextButton(onClick = { onVerify(d) }, enabled = !busy) { Text(if (req.documents.size > 1) "Verify ${d.text("name").take(18)}" else "Mark verified") }
                }
            }
        })
}

// ------------------------------------------------------------------ applicant

@Composable
fun ApplicationScreen(
    workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
    onCommand: (String, JsonObject, () -> Unit) -> Unit,
    onUpload: (Uri, Int, String, String) -> Unit, onOpenDocument: (String) -> Unit, onPayFee: () -> Unit,
) {
    val user = workspace.text("user_id")
    val view = applicationView(workspace)
    val app = view.application
    val membership = workspace.records("memberships").find { it.text("user_id") == user }
    val profile = workspace.records("profiles").find { it.text("id") == user }
    val initial = remember(app?.text("version")) {
        APPLICATION_FIELDS.split(",").associateWith { k -> app?.text(k).orEmpty().ifBlank { if (k in setOf("full_name", "phone")) profile?.text(k).orEmpty() else "" } }
    }
    val values = rememberSaveable(app?.text("version"), saver = mapSaver(save = { it.toMap() }, restore = { s -> mutableStateMapOf<String, String>().apply { s.forEach { (k, v) -> put(k, v as String) } } })) {
        mutableStateMapOf<String, String>().apply { putAll(initial) }
    }
    var confirmed by rememberSaveable(app?.text("version")) { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var submitDialog by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null && target.isNotBlank()) onUpload(uri, 2, view.id, target) }
    val editable = view.stage.editable && membership == null
    val errors = applicationErrors(values)
    val changed = values.toMap() != initial
    val blockers = submitBlockers(view, values, confirmed)
    val leave = { if (editable && changed) discard = true else onBack() }
    BackHandler(onBack = leave)
    if (discard) DiscardDialog(onDismiss = { discard = false }, onDiscard = { discard = false; onBack() })

    fun payload(extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        values.forEach { (k, v) -> put(k, v.trim()) }
        app?.text("version")?.takeIf { it.isNotBlank() }?.toLongOrNull()?.let { put("version", it) }
        extra()
    }
    @Composable fun field(key: String, label: String, keyboard: KeyboardType = KeyboardType.Text, lines: Int = 1, hint: String = "") =
        OutlinedTextField(values[key].orEmpty(), { values[key] = if (key == "phone") mobileInput(it) else it }, label = { Text(label) }, enabled = editable && !busy,
            isError = attempted && key in errors,
            supportingText = { (if (attempted) errors[key] else null)?.let { Text(it) } ?: if (hint.isNotBlank()) Text(hint) else Unit },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), singleLine = lines == 1, minLines = lines, modifier = Modifier.fillMaxWidth())

    val canSubmit = editable && membership == null && view.stage != ApplicationStage.REJECTED
    HeroScaffold("Membership application", leave, snackbar, overlap = 0.dp, busy = busy,
        eyebrow = {
            StatusPill((if (membership != null) membership.text("status") else view.stage.label).uppercase(),
                if (membership?.text("status") == "Active") Tone.POSITIVE else if (membership != null) Tone.WARNING else view.stage.tone)
            if (view.requirements.isNotEmpty()) StatusPill("${view.uploadedCount}/${view.requirements.size} DOCUMENTS", Tone.NEUTRAL)
        },
        heroExtra = { ProgressSegments(applicationMilestones(view, membership?.text("status").orEmpty())) },
        sticky = if (canSubmit) ({
            // The button text is long, so the status line sits above it instead of beside it.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (blockers.isEmpty()) "Ready to send" else "${blockers.size} thing${if (blockers.size == 1) "" else "s"} left",
                        style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("· " + (blockers.firstOrNull() ?: "The club is notified right away"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                StickyButton(if (view.stage == ApplicationStage.NEEDS_CHANGES) "Send again for verification" else "Send membership for verification",
                    !busy && blockers.isEmpty(), Modifier.fillMaxWidth()) {
                    attempted = true; submitDialog = true
                }
            }
        }) else null) {
        Spacer(Modifier.height(4.dp))

        // What the applicant must do now, stated once at the top.
        when {
            membership?.text("status") == "Pending payment" -> {
                val fee = workspace.records("fee_assessments").find { it.text("user_id") == user && it.text("purpose") == "Membership fee" && it.text("payment_id").isBlank() }
                NoticeCard("Approved — one step left", "Pay the membership fee${fee?.let { " of ₱" + it.text("amount") + " by " + it.text("due_on") }.orEmpty()}. Your membership becomes Active once the club verifies the payment.", Tone.POSITIVE)
                SecondaryAction("Go to the payment step on Home", !busy, onClick = onPayFee)
            }
            membership != null -> NoticeCard("You're a member", "Your application was approved. Your profile, activities and equipment are now open to you.", Tone.POSITIVE)
            view.stage == ApplicationStage.SUBMITTED -> NoticeCard("Sent for verification", "The club has your application. Details and documents are locked while it's checked. Once you're approved, you'll pay the membership fee from your Home.", Tone.INFO)
            view.stage == ApplicationStage.UNDER_REVIEW -> NoticeCard("Being checked", "The club is checking your documents. You'll be notified when there's a decision.", Tone.INFO)
            view.stage == ApplicationStage.NEEDS_CHANGES -> NoticeCard("Changes requested", view.remark.ifBlank { "Update your details or documents, then submit again." }, Tone.WARNING)
            view.stage == ApplicationStage.REJECTED -> {
                NoticeCard("Not approved", view.remark.ifBlank { "This application was not approved." }, Tone.DANGER)
                PrimaryAction("Start a new application", !busy) { onCommand("restart_application", buildJsonObject {}) {} }
            }
            else -> Unit
        }

        if (membership == null && view.stage != ApplicationStage.REJECTED) {
            RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepHeader(1, "Your details", view.id.isNotBlank() && errors.isEmpty() && !changed)
            field("full_name", "Full name", hint = "As it appears on your school ID. It becomes your verified club name.")
            field("phone", "Mobile number", KeyboardType.Phone, hint = "e.g. 0917 123 4567")
            field("student_number", "Student number", KeyboardType.Number)
            field("experience", "Archery experience (optional)", lines = 3, hint = "e.g. none yet, PE class, two years recurve")
            if (editable) SecondaryAction(if (view.id.isBlank()) "Save and continue" else "Save details", !busy && (changed || view.id.isBlank())) {
                attempted = true
                if (errors.isEmpty()) onCommand("save_application", payload { put("confirmed", false) }) {}
            }
            } }

            val requirements = view.requirements
            RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepHeader(2, "Documents", view.id.isNotBlank() && view.missing.isEmpty())
            when {
                requirements.isEmpty() -> Text("The club hasn't listed any required documents.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                view.id.isBlank() -> Text("Save your details first; your documents attach to your saved application.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text("PDF, JPG or PNG, up to 20 MB each. Replacing a file keeps the old one on record; the newest is the one checked.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    requirements.forEach { req ->
                        RequirementRow(req, editable, busy, onOpenDocument, onUpload = { target = req.name; picker.launch(UPLOAD_TYPES) })
                    }
                }
            }
            } }

            if (editable) RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepHeader(3, "Review and send", false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmed, { confirmed = it }, enabled = !busy)
                    Text("I confirm my details are correct and the documents are mine.", style = MaterialTheme.typography.bodyMedium)
                }
                if (blockers.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Before you can send it:", style = MaterialTheme.typography.labelLarge)
                    blockers.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } }
        }
    }
    if (submitDialog) ConfirmDialog("Send for verification?",
        "Your details and documents lock while the club checks them. You'll be notified when there's a decision.",
        "Send", onConfirm = { submitDialog = false; onCommand("submit_application", payload { put("confirmed", true) }) { sent = true } },
        onDismiss = { submitDialog = false })
    // After sending: say what happens next, then go back to Home, where the application's status is shown.
    if (sent) AlertDialog(onDismissRequest = { sent = false; onBack() },
        title = { Text("Application sent") },
        text = { Text("The club is checking your application. You'll be notified when there's a decision.") },
        confirmButton = { Button(onClick = { sent = false; onBack() }, shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text("Back to Home", fontWeight = FontWeight.ExtraBold) } })
}

// ------------------------------------------------------------------ officer

@Composable
fun ApplicationsQueueScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, onOpen: (String) -> Unit, onRecords: () -> Unit) =
    ScreenShell("Applications", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val queue = applicationsToReview(workspace)
        val ready = queue.count { it.missing.isEmpty() && it.unverified == 0 }
        TileRow {
            StatTile("${queue.size}", "Waiting", Modifier.weight(1f).fillMaxHeight(), if (queue.isEmpty()) Tone.NEUTRAL else Tone.WARNING)
            StatTile("${queue.sumOf { it.unverified }}", "Files to verify", Modifier.weight(1f).fillMaxHeight(), Tone.WARNING)
            StatTile("$ready", "Ready to decide", Modifier.weight(1f).fillMaxHeight(), Tone.POSITIVE)
        }
        if (queue.isEmpty()) EmptyState("No applications waiting", "New submissions appear here, oldest first.")
        queue.forEach { v ->
            val a = v.application!!
            WorkCard(a.text("full_name").ifBlank { "Applicant" },
                listOf("Submitted ${fmtDateTime(a.instant("submitted_at"))}", a.text("student_number").let { if (it.isBlank()) "" else "Student no. $it" }),
                leading = { Initials(a.text("full_name").ifBlank { "Applicant" }, 44) },
                status = {
                    StatusPill(v.stage.label, v.stage.tone)
                    if (v.unverified > 0) StatusPill("${v.unverified} to verify", Tone.WARNING)
                    else if (v.missing.isEmpty()) StatusPill("Ready", Tone.POSITIVE)
                    if (v.missing.isNotEmpty()) StatusPill("${v.missing.size} missing", Tone.DANGER)
                },
                onClick = { onOpen(v.id) })
        }
        TextButton(onClick = onRecords) { Text("Renewals, memberships and all records") }
    }

@Composable
fun ApplicationReviewScreen(
    workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
    onCommand: (String, JsonObject, () -> Unit) -> Unit, onOpenDocument: (String) -> Unit,
) {
    val me = workspace.text("user_id")
    val app = workspace.records("applications").find { it.text("id") == id }
    if (app == null) { ScreenShell("Application", onBack, snackbar) { EmptyState("Application not found", "It may have been decided already. Go back and refresh.") }; return }
    val view = applicationView(workspace, app)
    val own = app.text("user_id") == me
    val blockers = approvalBlockers(view, me)
    val categories = (workspace.records("settings").firstOrNull()?.get("membership_categories") as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.ifEmpty { listOf("Regular member") }
    var decision by remember { mutableStateOf<String?>(null) }
    var remark by remember(decision) { mutableStateOf("") }
    var category by remember { mutableStateOf(categories.first()) }

    fun decide(status: String, extra: JsonObjectBuilder.() -> Unit = {}) = onCommand("review_application", buildJsonObject {
        put("id", id); app.text("version").toLongOrNull()?.let { put("version", it) }; put("status", status); put("remark", remark.trim()); extra()
    }) { decision = null }

    ScreenShell(app.text("full_name").ifBlank { "Application" }, onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusPill(view.stage.label, view.stage.tone); if (own) StatusPill("Your own application", Tone.DANGER) }
        SectionHeader("Applicant")
        RaisedCard { Column(Modifier.padding(16.dp)) {
            KeyValue("Full name", app.text("full_name"))
            KeyValue("Mobile", app.text("phone"))
            KeyValue("Student no.", app.text("student_number"))
            KeyValue("Experience", app.text("experience"))
            KeyValue("Submitted", fmtDateTime(app.instant("submitted_at")))
            if (view.remark.isNotBlank()) KeyValue("Last remark", view.remark)
        } }

        SectionHeader("Documents")
        if (view.requirements.isEmpty() && view.otherDocuments.isEmpty()) Text("No documents are required by the club settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        view.requirements.forEach { req ->
            RequirementRow(req, canUpload = false, busy = busy, onOpen = onOpenDocument, onUpload = {}, canVerify = !own && view.stage.decidable,
                onVerify = { d -> onCommand("verify_document", buildJsonObject { put("id", d.text("id")) }) {} })
        }
        view.otherDocuments.forEach { d ->
            WorkCard(d.text("name"), listOf("Labelled \"${d.text("requirement")}\""),
                status = { if (d.text("verified_by").isBlank()) StatusPill("Waiting for verification", Tone.WARNING) else StatusPill("Verified", Tone.POSITIVE) },
                footer = { Row {
                    TextButton(onClick = { onOpenDocument(d.text("path")) }) { Text("Open") }
                    if (!own && d.text("verified_by").isBlank()) TextButton(onClick = { onCommand("verify_document", buildJsonObject { put("id", d.text("id")) }) {} }, enabled = !busy) { Text("Mark verified") }
                } })
        }

        if (!own && view.stage.decidable) {
            SectionHeader("Decision")
            if (blockers.isNotEmpty()) NoticeCard("Not ready to approve", blockers.joinToString("\n"), Tone.WARNING)
            PrimaryAction("Approve", !busy && blockers.isEmpty()) { decision = "approved" }
            SecondaryAction("Return for correction", !busy) { decision = "returned_for_correction" }
            if (view.missing.isNotEmpty()) SecondaryAction("Mark incomplete", !busy) { decision = "incomplete" }
            if (view.stage == ApplicationStage.SUBMITTED) TextButton(onClick = { decide("under_review") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Mark as under review") }
            TextButton(onClick = { decision = "rejected" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Reject", color = MaterialTheme.colorScheme.error) }
        }
    }

    val d = decision
    if (d == "approved") ConfirmDialog("Approve ${app.text("full_name")}?",
            "Their verified name becomes ${app.text("full_name")}. If the club charges a membership fee, they become Active once the Treasurer verifies payment.",
            "Approve", onConfirm = { decide("approved") { put("category", category); put("requirements_verified", true) } }, onDismiss = { decision = null },
            extra = {
                Text("Membership category", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(categories.map { it to it }, category) { category = it }
                OutlinedTextField(remark, { remark = it }, label = { Text("Note to applicant (optional)") }, modifier = Modifier.fillMaxWidth())
            })
    else if (d != null) ConfirmDialog(when (d) { "rejected" -> "Reject this application?"; "incomplete" -> "Mark as incomplete?"; else -> "Return for correction?" },
            when (d) { "rejected" -> "The applicant sees your reason and may start a new application."
                else -> "The application unlocks so the applicant can fix it and submit again. Tell them exactly what to change." },
            when (d) { "rejected" -> "Reject"; "incomplete" -> "Mark incomplete"; else -> "Return" },
            onConfirm = { decide(d) }, onDismiss = { decision = null }, destructive = d == "rejected", confirmEnabled = remark.isNotBlank() && !busy,
            extra = { OutlinedTextField(remark, { remark = it }, label = { Text("Reason (required)") }, minLines = 2, modifier = Modifier.fillMaxWidth()) })
}
