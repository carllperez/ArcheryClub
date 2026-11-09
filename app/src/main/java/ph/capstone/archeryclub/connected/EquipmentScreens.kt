package ph.capstone.archeryclub.connected

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.Duration
import java.time.Instant

/* Equipment: catalog → request (validated period and fit) → officer approval → release → return,
   with inventory state updating at each step. */

private fun itemLines(item: JsonObject) = listOf(
    listOf(item.text("category"), item.text("handedness").takeIf { it in setOf("left", "right") }?.let { "$it-handed" }.orEmpty(),
        item.text("draw_weight").toDoubleOrNull()?.let { "${fmtNumber(it)} lb" }.orEmpty(), item.text("bow_setup")).filter { it.isNotBlank() }.joinToString(" · "),
    item.text("specifications"))

private fun statusFor(item: JsonObject, workspace: JsonObject) = workspace.records("equipment_status").find { it.text("equipment_id") == item.text("id") }

@Composable
private fun EquipmentCard(item: JsonObject, workspace: JsonObject, onClick: () -> Unit) {
    val state = equipmentState(item, workspace)
    val s = statusFor(item, workspace)
    WorkCard(item.text("label"), itemLines(item), status = {
        StatusPill(state.label, state.tone())
        val until = s?.instant("busy_until")
        if (state in setOf(EquipmentState.IN_USE, EquipmentState.RESERVED) && until != null) StatusPill("Free ${fmtDay(until)}", Tone.NEUTRAL)
    }, onClick = onClick)
}

@Composable
private fun BorrowingCard(b: JsonObject, workspace: JsonObject, showMember: Boolean, onClick: () -> Unit) {
    val item = workspace.records("equipment").find { it.text("id") == b.text("equipment_id") }
    val overdue = isOverdue(b)
    val who = if (showMember) workspace.records("member_directory").find { it.text("user_id") == b.text("user_id") }?.text("full_name").orEmpty() else ""
    WorkCard(item?.text("label") ?: "Equipment", listOf(who, fmtRange(b.instant("starts_at"), b.instant("ends_at")), b.text("purpose")),
        status = { StatusPill(borrowingLabel(b.text("status"), overdue), borrowingTone(b.text("status"), overdue)) }, onClick = onClick)
}

/** Member equipment tab: browse the catalog or follow my requests. */
@Composable
fun MemberEquipmentContent(workspace: JsonObject, onItem: (String) -> Unit, onBorrowing: (String) -> Unit) {
    val user = workspace.text("user_id")
    var tab by rememberSaveable { mutableStateOf("catalog") }
    var category by rememberSaveable { mutableStateOf("") }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val mine = workspace.records("borrowings").filter { it.text("user_id") == user }.sortedByDescending { it.text("starts_at") }
    val active = mine.filter { it.text("status") in setOf("pending", "approved", "released", "returned_for_correction") }
    TileRow {
        StatTile("${active.count { it.text("status") == "released" }}", "Borrowed now", Modifier.weight(1f).fillMaxHeight(), Tone.NEUTRAL) { tab = "mine" }
        StatTile("${active.count { it.text("status") in setOf("pending", "approved") }}", "Requests in progress", Modifier.weight(1f).fillMaxHeight(), Tone.NEUTRAL) { tab = "mine" }
        val overdue = mine.count { isOverdue(it) }
        StatTile("$overdue", "Overdue", Modifier.weight(1f).fillMaxHeight(), if (overdue > 0) Tone.DANGER else Tone.NEUTRAL) { tab = "mine" }
    }
    ChoiceChips(listOf("catalog" to "Catalog", "mine" to "My borrowings (${active.size})"), tab) { tab = it }
    if (tab == "catalog") {
        val items = workspace.records("equipment").filter { it.text("condition") != "retired" }
        OutlinedTextField(search, { search = it }, label = { Text("Search equipment") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val categories = items.map { it.text("category") }.filter { it.isNotBlank() }.distinct().sorted()
        ChoiceChips(listOf("" to "All") + categories.map { it to it }, category) { category = it }
        FilterChip(selected = availableOnly, onClick = { availableOnly = !availableOnly }, label = { Text("Available now only") })
        val shown = items.filter { i ->
            (category.isBlank() || i.text("category") == category) && (!availableOnly || equipmentState(i, workspace) == EquipmentState.AVAILABLE) &&
                (i.text("label") + i.text("specifications") + i.text("category")).contains(search, true)
        }.sortedWith(compareBy({ equipmentState(it, workspace).ordinal }, { it.text("label") }))
        if (shown.isEmpty()) EmptyState("No equipment matches", "Try another category or turn off \"Available now only\".")
        shown.forEach { EquipmentCard(it, workspace) { onItem(it.text("id")) } }
    } else {
        if (mine.isEmpty()) EmptyState("No borrowings yet", "Pick an item from the catalog and request it for a practice or event.", "Open catalog") { tab = "catalog" }
        mine.forEach { b -> BorrowingCard(b, workspace, false) { onBorrowing(b.text("id")) } }
    }
}

/** Equipment officer: action queue and live inventory. */
@Composable
fun OfficerEquipmentContent(workspace: JsonObject, onItem: (String) -> Unit, onBorrowing: (String) -> Unit, onAddItem: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("queue") }
    val items = workspace.records("equipment").filter { it.text("condition") != "retired" }
    val states = items.map { equipmentState(it, workspace) }
    val borrowings = workspace.records("borrowings")
    val pending = borrowings.filter { it.text("status") == "pending" }.sortedBy { it.text("starts_at") }
    val toRelease = borrowings.filter { it.text("status") == "approved" }.sortedBy { it.text("starts_at") }
    val out = borrowings.filter { it.text("status") == "released" }.sortedBy { it.text("ends_at") }
    TileRow {
        StatTile("${states.count { it == EquipmentState.AVAILABLE }}/${items.size}", "Available", Modifier.weight(1f).fillMaxHeight(), Tone.POSITIVE) { tab = "inventory" }
        StatTile("${out.size}", "Borrowed", Modifier.weight(1f).fillMaxHeight(), Tone.INFO) { tab = "queue" }
        val overdue = out.count { isOverdue(it) }
        StatTile("$overdue", "Overdue", Modifier.weight(1f).fillMaxHeight(), if (overdue > 0) Tone.DANGER else Tone.NEUTRAL) { tab = "queue" }
    }
    ChoiceChips(listOf("queue" to "To do (${pending.size + toRelease.size + out.size})", "inventory" to "Inventory (${items.size})", "history" to "History"), tab) { tab = it }
    when (tab) {
        "queue" -> {
            if (pending.isEmpty() && toRelease.isEmpty() && out.isEmpty()) EmptyState("All clear", "New member requests appear here for review.")
            if (pending.isNotEmpty()) { SectionHeader("Review requests (${pending.size})"); pending.forEach { BorrowingCard(it, workspace, true) { onBorrowing(it.text("id")) } } }
            if (toRelease.isNotEmpty()) { SectionHeader("Ready to hand over (${toRelease.size})"); toRelease.forEach { BorrowingCard(it, workspace, true) { onBorrowing(it.text("id")) } } }
            if (out.isNotEmpty()) { SectionHeader("Awaiting return (${out.size})"); out.sortedByDescending { isOverdue(it) }.forEach { BorrowingCard(it, workspace, true) { onBorrowing(it.text("id")) } } }
        }
        "inventory" -> {
            PrimaryAction("Add equipment", onClick = onAddItem)
            EquipmentState.entries.filter { s -> states.any { it == s } }.forEach { s ->
                SectionHeader("${s.label} (${states.count { it == s }})")
                items.filter { equipmentState(it, workspace) == s }.sortedBy { it.text("label") }.forEach { EquipmentCard(it, workspace) { onItem(it.text("id")) } }
            }
        }
        else -> {
            val done = borrowings.filter { it.text("status") in setOf("returned", "rejected", "cancelled") }.sortedByDescending { it.text("ends_at") }
            if (done.isEmpty()) EmptyState("No completed transactions yet", "Returned, declined and cancelled requests are kept here.")
            done.take(50).forEach { BorrowingCard(it, workspace, true) { onBorrowing(it.text("id")) } }
        }
    }
}

@Composable
fun EquipmentItemScreen(
    workspace: JsonObject, itemId: String, busy: Boolean, snackbar: SnackbarHostState, isMember: Boolean, isOfficer: Boolean,
    onBack: () -> Unit, onCommand: (String, JsonObject, () -> Unit) -> Unit, onEdit: (JsonObject) -> Unit, onBorrowing: (String) -> Unit,
) {
    val item = workspace.records("equipment").find { it.text("id") == itemId }
    if (item == null) { ScreenShell("Equipment", onBack, snackbar) { EmptyState("Item not found", "Refresh and try again.") }; return }
    val user = workspace.text("user_id")
    val profile = workspace.records("profiles").find { it.text("id") == user }
    val state = equipmentState(item, workspace)
    val s = statusFor(item, workspace)
    var start by remember { mutableStateOf<Instant?>(null) }
    var end by remember { mutableStateOf<Instant?>(null) }
    var hand by rememberSaveable { mutableStateOf(profile?.text("dominant_hand").orEmpty().ifBlank { item.text("handedness").takeIf { it in setOf("left", "right") } ?: "right" }) }
    var purpose by rememberSaveable { mutableStateOf("") }
    var eventId by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val maxWeight = profile?.text("draw_weight")?.toDoubleOrNull()
    val issues = borrowingIssues(item, hand, maxWeight, start, end)
    ScreenShell(item.text("label"), onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(state.label, state.tone())
            s?.instant("next_booking")?.let { StatusPill("Next booking ${fmtDay(it)}", Tone.NEUTRAL) }
        }
        Column {
            KeyValue("Category", item.text("category")); KeyValue("Handedness", human(item.text("handedness")))
            KeyValue("Draw weight", item.text("draw_weight").toDoubleOrNull()?.let { "${fmtNumber(it)} lb" }.orEmpty())
            KeyValue("Setup", item.text("bow_setup")); KeyValue("Condition", human(item.text("condition")))
            KeyValue("Times borrowed", s?.text("times_borrowed").orEmpty())
            KeyValue("Specifications", item.text("specifications"))
            if (isOfficer) KeyValue("Notes", item.text("notes"))
        }
        val blocked = hasOverdueItem(workspace)
        if (isMember && state.borrowable && blocked) {
            NoticeCard("Return your overdue item first", "You have equipment past its return time. New requests open again once the officer records the return.", Tone.DANGER)
        } else if (isMember && state.borrowable) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionHeader("Request this item")
            DateTimeField("Pick-up", start, { picked ->
                start = picked
                val currentEnd = end
                if (currentEnd == null || !currentEnd.isAfter(picked)) end = picked.plus(Duration.ofHours(3))
            })
            DateTimeField("Return by", end, { end = it })
            Text("Shooting hand", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("right" to "Right-handed", "left" to "Left-handed"), hand) { hand = it }
            val upcoming = upcomingMine(workspace).filter { it.status == ActivityStatus.REGISTERED }
            if (upcoming.isNotEmpty()) {
                Text("For an activity (optional)", style = MaterialTheme.typography.labelLarge)
                SelectField("Activity", eventId, listOf("" to "Not linked") + upcoming.map { it.id to it.event.text("title") }) { id ->
                    eventId = id
                    upcoming.find { it.id == id }?.let { a -> start = a.start?.minus(Duration.ofMinutes(30)); end = a.end?.plus(Duration.ofMinutes(30)) }
                }
            }
            OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose") }, placeholder = { Text("e.g. Saturday 18 m practice") },
                minLines = 2, modifier = Modifier.fillMaxWidth())
            if (maxWeight == null) Text("Tip: add your draw weight in your profile so the app can check bow fit for you.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            issues.filter { start != null || !it.startsWith("Choose") }.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            PrimaryAction("Review request", !busy && issues.isEmpty() && purpose.isNotBlank()) { confirm = true }
        } else if (isMember) {
            NoticeCard("Not available to borrow", when (state) {
                EquipmentState.OVERDUE -> "This item has not been returned yet."
                EquipmentState.MAINTENANCE -> "This item is under maintenance."
                EquipmentState.DAMAGED -> "This item is damaged and awaiting repair."
                else -> "This item is retired."
            }, Tone.NEUTRAL)
        }
        if (isOfficer) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SecondaryAction("Edit item or condition", !busy) { onEdit(item) }
            val history = workspace.records("borrowings").filter { it.text("equipment_id") == itemId }.sortedByDescending { it.text("starts_at") }
            SectionHeader("Borrowing history (${history.size})")
            if (history.isEmpty()) Text("Not borrowed yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.take(10).forEach { BorrowingCard(it, workspace, true) { onBorrowing(it.text("id")) } }
            val log = workspace.records("equipment_history").filter { it.text("equipment_id") == itemId }.sortedByDescending { it.text("created_at") }
            if (log.isNotEmpty()) {
                SectionHeader("Condition log")
                log.take(10).forEach { KeyValue(fmtDay(it.instant("created_at")), "${human(it.text("condition"))} · ${it.text("notes")}") }
            }
        }
    }
    if (confirm) ConfirmDialog("Send borrowing request?",
        "${item.text("label")}\nPick-up ${fmtDateTime(start)}\nReturn ${fmtDateTime(end)}\n\nThe equipment officer reviews fit and availability. You'll be notified when it is approved and ready for pick-up.",
        "Send request", onConfirm = {
            confirm = false
            onCommand("request_borrowing", buildJsonObject {
                put("equipment_id", itemId); if (eventId.isNotBlank()) put("event_id", eventId)
                put("starts_at", start.toString()); put("ends_at", end.toString()); put("handedness", hand)
                maxWeight?.let { put("maximum_draw_weight", it) }; put("bow_setup", item.text("bow_setup")); put("purpose", purpose.trim())
            }) { purpose = ""; start = null; end = null }
        }, onDismiss = { confirm = false })
}

@Composable
fun BorrowingScreen(
    workspace: JsonObject, borrowingId: String, busy: Boolean, snackbar: SnackbarHostState, isOfficer: Boolean,
    onBack: () -> Unit, onCommand: (String, JsonObject, () -> Unit) -> Unit, onResubmit: (JsonObject) -> Unit, onItem: (String) -> Unit,
) {
    val b = workspace.records("borrowings").find { it.text("id") == borrowingId }
    if (b == null) { ScreenShell("Borrowing", onBack, snackbar) { EmptyState("Request not found", "Refresh and try again.") }; return }
    val user = workspace.text("user_id")
    val item = workspace.records("equipment").find { it.text("id") == b.text("equipment_id") }
    val status = b.text("status"); val overdue = isOverdue(b)
    val member = workspace.records("member_directory").find { it.text("user_id") == b.text("user_id") }?.text("full_name").orEmpty()
    var decision by remember { mutableStateOf<String?>(null) }
    var cancel by remember { mutableStateOf(false) }
    ScreenShell(item?.text("label") ?: "Borrowing", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        StatusPill(borrowingLabel(status, overdue), borrowingTone(status, overdue))
        BorrowingTimeline(status)
        Column {
            if (isOfficer) KeyValue("Member", member)
            KeyValue("Pick-up", fmtDateTime(b.instant("starts_at"))); KeyValue("Return by", fmtDateTime(b.instant("ends_at")))
            if (overdue) KeyValue("Overdue", relative(b.instant("ends_at")).removeSuffix(" ago") + " late")
            KeyValue("Purpose", b.text("purpose")); KeyValue("Hand", human(b.text("handedness")))
            b.text("event_id").takeIf { it.isNotBlank() }?.let { id -> KeyValue("Activity", workspace.records("events").find { it.text("id") == id }?.text("title").orEmpty()) }
            KeyValue("Released", fmtDateTime(b.instant("released_at")).takeIf { b.text("released_at").isNotBlank() }.orEmpty())
            KeyValue("Receipt", if (b.text("received_at").isNotBlank()) "Confirmed by member ${fmtDateTime(b.instant("received_at"))}" else if (status == "released") "Not yet confirmed by member" else "")
            KeyValue("Returned", fmtDateTime(b.instant("returned_at")).takeIf { b.text("returned_at").isNotBlank() }.orEmpty())
            KeyValue("Officer remark", b.text("remark"))
        }
        item?.let { SecondaryAction("View item details") { onItem(it.text("id")) } }
        if (b.text("user_id") == user) {
            if (status == "returned_for_correction") PrimaryAction("Edit and resubmit", !busy) { onResubmit(b) }
            if (status in setOf("pending", "approved", "returned_for_correction")) SecondaryAction("Cancel request", !busy) { cancel = true }
            if (status == "released" && b.text("received_at").isBlank()) PrimaryAction("Confirm I received it", !busy) {
                onCommand("confirm_receipt", buildJsonObject { put("id", borrowingId) }) {}
            }
            if (status == "released") NoticeCard(if (overdue) "Please return this item" else "Enjoy your session",
                "Return it to the equipment officer by ${fmtDateTime(b.instant("ends_at"))}. The officer records the return and its condition.", if (overdue) Tone.DANGER else Tone.POSITIVE)
        }
        if (isOfficer && b.text("user_id") != user) {
            val steps = borrowingNextSteps(status)
            if (steps.isNotEmpty()) { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); SectionHeader("Next step") }
            steps.forEachIndexed { i, step ->
                val label = when (step) { "approved" -> "Approve request"; "released" -> "Hand over to member"; "returned" -> "Record return"
                    "returned_for_correction" -> "Ask member to revise"; else -> if (status == "approved") "Cancel approval" else "Decline" }
                if (i == 0) PrimaryAction(label, !busy) { decision = step } else SecondaryAction(label, !busy) { decision = step }
            }
        }
    }
    if (cancel) ConfirmDialog("Cancel this request?", "The item becomes available to other members again.", "Cancel request",
        onConfirm = { cancel = false; onCommand("cancel_borrowing", buildJsonObject { put("id", borrowingId); b["version"]?.let { put("version", it) } }) {} },
        onDismiss = { cancel = false }, destructive = true)
    decision?.let { step -> ReviewDialog(step, b, item, onDismiss = { decision = null }) { payload -> decision = null; onCommand("review_borrowing", payload) {} } }
}

@Composable
private fun ReviewDialog(step: String, b: JsonObject, item: JsonObject?, onDismiss: () -> Unit, onSubmit: (JsonObject) -> Unit) {
    var remark by remember { mutableStateOf(when (step) { "approved" -> "Fit and availability checked"; "released" -> "Handed over at the range"; "returned" -> "Returned complete"; else -> "" }) }
    var checked by remember { mutableStateOf(false) }
    var condition by remember { mutableStateOf("serviceable") }
    val needsCheck = step in setOf("approved", "released")
    val fitNotes = item?.let { borrowingIssues(it, b.text("handedness"), b.text("maximum_draw_weight").toDoubleOrNull(), b.instant("starts_at"), b.instant("ends_at"), Instant.EPOCH) }
        .orEmpty().filter { !it.contains("future") }
    ConfirmDialog(when (step) { "approved" -> "Approve request"; "released" -> "Hand over equipment"; "returned" -> "Record return"; "returned_for_correction" -> "Ask for changes"; else -> "Decline request" },
        when (step) {
            "approved" -> "The item is reserved for this period. Overlapping requests can no longer be approved."
            "released" -> "Inventory changes to Borrowed and the member gets a return reminder."
            "returned" -> "The item's condition is recorded. Anything other than Serviceable removes it from the catalog until fixed."
            else -> "The member is notified with your remark."
        }, "Confirm", confirmEnabled = remark.isNotBlank() && (!needsCheck || checked), onDismiss = onDismiss, destructive = step == "rejected",
        onConfirm = {
            onSubmit(buildJsonObject {
                put("id", b.text("id")); b["version"]?.let { put("version", it) }; put("status", step); put("remark", remark.trim())
                put("compatibility_confirmed", checked); if (step == "returned") put("condition", condition)
            })
        }, extra = {
            fitNotes.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
            if (needsCheck) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, { checked = it }); Text("I checked fit and safety (hand, draw weight, setup)")
            }
            if (step == "returned") {
                Text("Condition on return", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf("serviceable" to "Serviceable", "maintenance" to "Needs maintenance", "damaged" to "Damaged"), condition) { condition = it }
            }
            OutlinedTextField(remark, { remark = it }, label = { Text("Remark to member") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        })
}

@Composable
private fun BorrowingTimeline(status: String) {
    val steps = listOf("Requested", "Approved", "Handed over", "Returned")
    val reached = when (status) { "pending", "returned_for_correction" -> 0; "approved" -> 1; "released" -> 2; "returned" -> 3; else -> -1 }
    if (reached < 0) { Text(if (status == "cancelled") "This request was cancelled." else "This request was declined.", color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (i <= reached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest))
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (i <= reached) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
