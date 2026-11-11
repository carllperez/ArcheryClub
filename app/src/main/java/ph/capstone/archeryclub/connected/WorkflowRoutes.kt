package ph.capstone.archeryclub.connected

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.ScreenShell

/** Renders one purpose-built workflow screen for the current route. */
@Composable
fun WorkflowRoute(
    route: String, workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, isMember: Boolean, allowed: (String) -> Boolean,
    onBack: () -> Unit, onPush: (String) -> Unit, onGo: (String) -> Unit, onStart: (String, JsonObject) -> Unit,
    onCommand: (String, JsonObject, () -> Unit) -> Unit,
    onUpload: (android.net.Uri, Int, String, String) -> Unit = { _, _, _, _ -> },
    onOpenDocument: (String) -> Unit = {},
    vm: SystemViewModel? = null,
) {
    val key = route.substringBefore(':'); val id = route.substringAfter(':', "")
    val officerActivities = allowed("activities")
    val officerEquipment = allowed("equipment")
    val perms = listOf("finance", "president", "training", "elections", "administration", "interclub", "reports").filter(allowed).toSet()
    @Composable fun denied(title: String, body: String) = ScreenShell(title, onBack, snackbar) { EmptyState(title, body) }
    when (key) {
        "activities" -> ScreenShell("Activities", onBack, snackbar) {
            if (busy) LinearProgressIndicator()
            MemberActivitiesContent(workspace) { onPush("activity:$it") }
            TextButton(onClick = { onGo("records:3") }) { Text("Announcements and attendance records") }
        }
        "activity" -> ActivityDetailScreen(workspace, id, busy, snackbar, isMember, officerActivities, onBack, onCommand,
            onRoster = { onPush("roster:$id") }, onEdit = { onStart("save_event", it) },
            onAnnounce = { onStart("publish_announcement", buildJsonObject { put("event_id", it.text("id")) }) },
            onBorrow = { onPush("equipment_member") })
        "roster" -> RosterScreen(workspace, id, busy, snackbar, onBack, onCommand) { onStart("open_attendance", it) }
        "officer_activities" -> ScreenShell("Manage activities", onBack, snackbar) {
            if (busy) LinearProgressIndicator()
            OfficerActivitiesContent(workspace, { onPush("activity:$it") }) { onStart("save_event", buildJsonObject {}) }
            TextButton(onClick = { onGo("records:8") }) { Text("Announcements, corrections and all records") }
        }
        "equipment" -> if (officerEquipment) ScreenShell("Equipment", onBack, snackbar) {
            if (busy) LinearProgressIndicator()
            OfficerEquipmentContent(workspace, { onPush("item:$it") }, { onPush("borrowing:$it") }) { onStart("save_equipment", buildJsonObject {}) }
            if (isMember) TextButton(onClick = { onPush("equipment_member") }) { Text("Borrow equipment for myself") }
            TextButton(onClick = { onGo("records:10") }) { Text("All inventory records and calendar") }
        } else MemberEquipment(workspace, busy, snackbar, onBack, onPush, onGo)
        "equipment_member" -> MemberEquipment(workspace, busy, snackbar, onBack, onPush, onGo)
        "item" -> EquipmentItemScreen(workspace, id, busy, snackbar, isMember, officerEquipment, onBack, onCommand,
            onEdit = { onStart("save_equipment", it) }, onBorrowing = { onPush("borrowing:$it") })
        "borrowing" -> BorrowingScreen(workspace, id, busy, snackbar, officerEquipment, onBack, onCommand,
            onResubmit = { onStart("request_borrowing", it) }, onItem = { onPush("item:$it") })
        "profile" -> ProfileScreen(workspace, busy, snackbar, onBack, onEdit = { onPush("profile_edit") },
            onRenewal = { onGo("records:1") }, onActivities = { onPush("activities") }, onEquipment = { onPush("equipment_member") })
        "profile_edit" -> ProfileEditScreen(workspace, busy, snackbar, onBack, onSave = { payload -> onCommand("save_profile", payload, onBack) }, onCommand = onCommand)
        "name_corrections" -> NameCorrectionsScreen(workspace, busy, snackbar, onBack, onCommand)
        "application" -> ApplicationScreen(workspace, busy, snackbar, onBack, onCommand, onUpload, onOpenDocument, onPayFee = onBack)
        "applications" -> if (allowed("membership")) ApplicationsQueueScreen(workspace, busy, snackbar, onBack, { onPush("application_review:$it") }) { onGo("records:7") }
            else ScreenShell("Applications", onBack, snackbar) { EmptyState("Membership officers only", "Your current role can't review applications.") }
        "application_review" -> if (allowed("membership")) ApplicationReviewScreen(workspace, id, busy, snackbar, onBack, onCommand, onOpenDocument)
            else ScreenShell("Application", onBack, snackbar) { EmptyState("Membership officers only", "Your current role can't review applications.") }
        "training" -> TrainingScreen(workspace, busy, snackbar, onBack, { onPush("scorecard:$it") }) { onStart("save_personal_training", it) }
        "scorecard" -> ScorecardScreen(workspace, id, busy, snackbar, allowed("training"), vm, onBack, onCommand, onStart, onOpenDocument)
        "training_desk" -> if (allowed("training")) TrainingDeskScreen(workspace, busy, snackbar, vm, onBack, { onPush("scorecard:$it") }, onStart) { onGo("records:11") }
            else denied("Coaches only", "Your current role can't validate scorecards.")
        "payments" -> PaymentsScreen(workspace, busy, snackbar, isMember, onBack, { onPush("finance:$it") }, onCommand, onStart, onUpload)
        "finance" -> FinanceRecordScreen(workspace, id, busy, snackbar, perms, onBack, onCommand, onStart, onUpload, onOpenDocument)
        "finance_desk" -> if ("finance" in perms || "president" in perms)
            FinanceDeskScreen(workspace, busy, snackbar, perms, onBack, { onPush("finance:$it") }, onCommand, onStart, { onGo("records:12") }, vm) { onPush("payments") }
            else PaymentsScreen(workspace, busy, snackbar, isMember, onBack, { onPush("finance:$it") }, onCommand, onStart, onUpload)
        "elections", "election" -> ElectionScreen(workspace, id, busy, snackbar, isMember, allowed("elections"), vm, onBack, { onPush("election:$it") }, onCommand, onStart) { onGo("records:9") }
        "reports" -> if (allowed("reports")) ReportsScreen(workspace, busy, snackbar, vm, onBack, onStart) { onGo("records:13") }
            else denied("Reports officers only", "Your current role can't open club reports.")
        "admin" -> if (allowed("administration")) AdminScreen(workspace, busy, snackbar, perms, onBack, onStart) { onGo("records:14") }
            else denied("Administrators only", "Your current role can't change club settings or roles.")
        "interclub" -> InterclubScreen(workspace, id, busy, snackbar, allowed("coordinator"), allowed("interclub"), onBack, { onPush("interclub:$it") }, onStart) { onGo("records:15") }
        else -> ScreenShell("Not found", onBack, snackbar) { EmptyState("This page is unavailable", "Go back and try again.") }
    }
}

@Composable
private fun MemberEquipment(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, onPush: (String) -> Unit, onGo: (String) -> Unit) {
    val profile = workspace.records("profiles").find { it.text("id") == workspace.text("user_id") }
    HeroScaffold("Club Gear", onBack, snackbar, overlap = 0.dp, busy = busy,
        heroExtra = { Text(gearMatchLine(profile), color = androidx.compose.ui.graphics.Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodySmall) }) {
        Spacer(Modifier.height(4.dp))
        MemberEquipmentContent(workspace, { onPush("item:$it") }) { onPush("borrowing:$it") }
        TextButton(onClick = { onPush("payments") }) { Text("Payments and reimbursements") }
    }
}
