package ph.capstone.archeryclub.connected

import androidx.compose.material3.*
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
) {
    val key = route.substringBefore(':'); val id = route.substringAfter(':', "")
    val officerActivities = allowed("activities")
    val officerEquipment = allowed("equipment")
    when (key) {
        "activities" -> ScreenShell("Activities", onBack, snackbar) {
            if (busy) LinearProgressIndicator()
            MemberActivitiesContent(workspace) { onPush("activity:$it") }
            TextButton(onClick = { onGo("module:3") }) { Text("Announcements and attendance records") }
        }
        "activity" -> ActivityDetailScreen(workspace, id, busy, snackbar, isMember, officerActivities, onBack, onCommand,
            onRoster = { onPush("roster:$id") }, onEdit = { onStart("save_event", it) },
            onAnnounce = { onStart("publish_announcement", buildJsonObject { put("event_id", it.text("id")) }) })
        "roster" -> RosterScreen(workspace, id, busy, snackbar, onBack, onCommand) { onStart("open_attendance", it) }
        "officer_activities" -> ScreenShell("Manage activities", onBack, snackbar) {
            if (busy) LinearProgressIndicator()
            OfficerActivitiesContent(workspace, { onPush("activity:$it") }) { onStart("save_event", buildJsonObject {}) }
            TextButton(onClick = { onGo("module:8") }) { Text("Announcements, corrections and all records") }
        }
        "equipment" -> if (officerEquipment) ScreenShell("Equipment", onBack, snackbar) {
            if (busy) LinearProgressIndicator()
            OfficerEquipmentContent(workspace, { onPush("item:$it") }, { onPush("borrowing:$it") }) { onStart("save_equipment", buildJsonObject {}) }
            if (isMember) TextButton(onClick = { onPush("equipment_member") }) { Text("Borrow equipment for myself") }
            TextButton(onClick = { onGo("module:10") }) { Text("All inventory records and calendar") }
        } else MemberEquipment(workspace, busy, snackbar, onBack, onPush, onGo)
        "equipment_member" -> MemberEquipment(workspace, busy, snackbar, onBack, onPush, onGo)
        "item" -> EquipmentItemScreen(workspace, id, busy, snackbar, isMember, officerEquipment, onBack, onCommand,
            onEdit = { onStart("save_equipment", it) }, onBorrowing = { onPush("borrowing:$it") })
        "borrowing" -> BorrowingScreen(workspace, id, busy, snackbar, officerEquipment, onBack, onCommand,
            onResubmit = { onStart("request_borrowing", it) }, onItem = { onPush("item:$it") })
        "profile" -> ProfileScreen(workspace, busy, snackbar, onBack, onEdit = { onPush("profile_edit") },
            onRenewal = { onGo("module:1") }, onActivities = { onPush("activities") }, onEquipment = { onPush("equipment_member") })
        "profile_edit" -> ProfileEditScreen(workspace, busy, snackbar, onBack, onSave = { payload -> onCommand("save_profile", payload, onBack) }, onCommand = onCommand)
        "name_corrections" -> NameCorrectionsScreen(workspace, busy, snackbar, onBack, onCommand)
        "application" -> ApplicationScreen(workspace, busy, snackbar, onBack, onCommand, onUpload, onOpenDocument, onPayFee = { onGo("module:12") })
        "applications" -> if (allowed("membership")) ApplicationsQueueScreen(workspace, busy, snackbar, onBack, { onPush("application_review:$it") }) { onGo("module:7") }
            else ScreenShell("Applications", onBack, snackbar) { EmptyState("Membership officers only", "Your current role can't review applications.") }
        "application_review" -> if (allowed("membership")) ApplicationReviewScreen(workspace, id, busy, snackbar, onBack, onCommand, onOpenDocument)
            else ScreenShell("Application", onBack, snackbar) { EmptyState("Membership officers only", "Your current role can't review applications.") }
        else -> ScreenShell("Not found", onBack, snackbar) { EmptyState("This page is unavailable", "Go back and try again.") }
    }
}

@Composable
private fun MemberEquipment(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, onPush: (String) -> Unit, onGo: (String) -> Unit) =
    ScreenShell("Equipment", onBack, snackbar) {
        if (busy) LinearProgressIndicator()
        MemberEquipmentContent(workspace, { onPush("item:$it") }) { onPush("borrowing:$it") }
        TextButton(onClick = { onGo("module:5") }) { Text("Reimbursements and other member requests") }
    }
