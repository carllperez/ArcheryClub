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
import ph.capstone.archeryclub.ui.Club
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
    WorkCard(item.text("label"), itemLines(item), leading = { IconChip(gearIcon(item), if (state.borrowable) Tone.POSITIVE else Tone.NEUTRAL) }, status = {
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
        leading = { IconChip(if (overdue) "clock" else if (item != null) gearIcon(item) else "box", if (overdue) Tone.DANGER else if (b.text("status") == "pending") Tone.WARNING else Tone.POSITIVE) },
        status = { StatusPill(borrowingLabel(b.text("status"), overdue), borrowingTone(b.text("status"), overdue)) }, onClick = onClick)
}

private fun gearIcon(item: JsonObject) = item.text("category").lowercase().let { c ->
    when { "bow" in c -> "bow"; "arrow" in c -> "arrows"; else -> "box" }
}

/** Member Gear tab: what I have now, catalog cards that say whether they fit me, and my requests. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemberEquipmentContent(workspace: JsonObject, onItem: (String) -> Unit, onBorrowing: (String) -> Unit) {
    val user = workspace.text("user_id")
    val profile = workspace.records("profiles").find { it.text("id") == user }
    val hand = profile?.text("dominant_hand").orEmpty()
    val maxWeight = profile?.text("draw_weight")?.toDoubleOrNull()
    var category by rememberSaveable { mutableStateOf("fits") }
    var search by rememberSaveable { mutableStateOf("") }
    val mine = workspace.records("borrowings").filter { it.text("user_id") == user }.sortedByDescending { it.text("starts_at") }
    val withMe = mine.filter { it.text("status") == "released" }
    val requests = mine.filter { it.text("status") in setOf("pending", "approved", "returned_for_correction") }
    withMe.forEach { b -> WithYouCard(b, workspace) { onBorrowing(b.text("id")) } }
    if (hasOverdueItem(workspace)) NoticeCard("Return your overdue item first", "New requests open again once the equipment officer records the return.", Tone.DANGER)

    val items = workspace.records("equipment").filter { it.text("condition") != "retired" }
    val categories = items.map { it.text("category") }.filter { it.isNotBlank() }.distinct().sorted()
    ChoiceChips(listOf("fits" to "Fits me", "" to "All") + categories.map { it to it }, category) { category = it }
    OutlinedTextField(search, { search = it }, placeholder = { Text("Search gear") }, singleLine = true, shape = CircleShape,
        leadingIcon = { ph.capstone.archeryclub.ui.ClubIcon("search", Modifier.size(18.dp)) },
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth())
    val shown = items.filter { i ->
        val fits = fitProblems(i.text("handedness"), i.text("draw_weight").toDoubleOrNull(), hand, maxWeight).isEmpty()
        when (category) { "fits" -> fits && equipmentState(i, workspace).borrowable; "" -> true; else -> i.text("category") == category } &&
            (i.text("label") + i.text("specifications") + i.text("category")).contains(search, true)
    }.sortedWith(compareBy({ equipmentState(it, workspace).ordinal }, { it.text("label") }))
    if (category == "fits" && (hand.isBlank() || maxWeight == null))
        Text("Add your shooting hand and draw weight to your profile for better matches.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (shown.isEmpty()) EmptyState("No gear matches", "Try All or another category.")
    shown.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { item -> GearCard(item, workspace, hand, maxWeight, Modifier.weight(1f).fillMaxHeight()) { onItem(item.text("id")) } }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (requests.isNotEmpty()) { SectionHeader("My requests"); requests.forEach { b -> BorrowingCard(b, workspace, false) { onBorrowing(b.text("id")) } } }
    val past = mine.filter { it.text("status") in setOf("returned", "rejected", "cancelled") }
    if (past.isNotEmpty()) { SectionHeader("History"); past.take(5).forEach { b -> BorrowingCard(b, workspace, false) { onBorrowing(b.text("id")) } } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GearCard(item: JsonObject, workspace: JsonObject, hand: String, maxWeight: Double?, modifier: Modifier, onClick: () -> Unit) {
    val state = equipmentState(item, workspace)
    val problems = fitProblems(item.text("handedness"), item.text("draw_weight").toDoubleOrNull(), hand, maxWeight)
    val dim = !state.borrowable || problems.isNotEmpty()
    RaisedCard(modifier.alpha(if (dim) .78f else 1f), radius = 18.dp, onClick = onClick) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background))), contentAlignment = Alignment.Center) {
                ph.capstone.archeryclub.ui.ClubIcon(gearIcon(item), Modifier.size(38.dp), if (dim) MaterialTheme.colorScheme.onSurfaceVariant else Club.forest)
            }
            Text(item.text("label"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(itemLines(item).first(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Spacer(Modifier.weight(1f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusPill(state.label, state.tone())
                if (problems.isEmpty() && (item.text("handedness") in setOf("left", "right") || item.text("draw_weight").isNotBlank())) StatusPill("✓ Fits", Tone.NEUTRAL)
            }
            problems.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Club.roseInk) }
        }
    }
}

/** Dark countdown card for an item the member currently has. */
@Composable
private fun WithYouCard(b: JsonObject, workspace: JsonObject, onClick: () -> Unit) {
    val item = workspace.records("equipment").find { it.text("id") == b.text("equipment_id") }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(b.text("id")) { while (true) { kotlinx.coroutines.delay(30_000); now = Instant.now() } }
    val due = b.instant("ends_at")
    val late = due != null && now.isAfter(due)
    Box(Modifier.fillMaxWidth().clubCard(22, 10).clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(if (late) listOf(Club.roseInk, Color(0xFF5E120F)) else listOf(Club.forestDeep, Club.forest))).clickable(onClick = onClick)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (late) "OVERDUE" else "WITH YOU NOW", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.6.sp, color = if (late) Club.roseSoft else Club.mint)
            Text(item?.text("label") ?: "Equipment", style = MaterialTheme.typography.titleLarge, color = Color.White)
            if (due != null) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(durationLabel(now, due), style = MaterialTheme.typography.displaySmall, color = if (late) Club.roseSoft else Club.lime)
                Text(if (late) "late · was due ${fmtTime(due)}" else "left · return by ${fmtTime(due)}", style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = .9f), modifier = Modifier.padding(bottom = 6.dp))
            }
            Text("Hand it to the equipment officer. New requests pause while anything is overdue.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .8f))
        }
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
        RaisedCard { Column(Modifier.padding(16.dp)) {
            KeyValue("Category", item.text("category")); KeyValue("Handedness", human(item.text("handedness")))
            KeyValue("Draw weight", item.text("draw_weight").toDoubleOrNull()?.let { "${fmtNumber(it)} lb" }.orEmpty())
            KeyValue("Setup", item.text("bow_setup")); KeyValue("Condition", human(item.text("condition")))
            KeyValue("Times borrowed", s?.text("times_borrowed").orEmpty())
            KeyValue("Specifications", item.text("specifications"))
            if (isOfficer) KeyValue("Notes", item.text("notes"))
        } }
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
    ScreenShell(item?.text("label") ?: "Borrowing", onBack, snackbar, actions = { StatusPill(borrowingLabel(status, overdue), borrowingTone(status, overdue)) }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status == "released" && b.text("user_id") == user) WithYouCard(b, workspace) {}
        BorrowingTimeline(b)
        RaisedCard { Column(Modifier.padding(16.dp)) {
            if (isOfficer) KeyValue("Member", member)
            KeyValue("Pick-up", fmtDateTime(b.instant("starts_at"))); KeyValue("Return by", fmtDateTime(b.instant("ends_at")))
            if (overdue) KeyValue("Overdue", relative(b.instant("ends_at")).removeSuffix(" ago") + " late")
            KeyValue("Purpose", b.text("purpose")); KeyValue("Hand", human(b.text("handedness")))
            b.text("event_id").takeIf { it.isNotBlank() }?.let { id -> KeyValue("Activity", workspace.records("events").find { it.text("id") == id }?.text("title").orEmpty()) }
            KeyValue("Released", fmtDateTime(b.instant("released_at")).takeIf { b.text("released_at").isNotBlank() }.orEmpty())
            KeyValue("Receipt", if (b.text("received_at").isNotBlank()) "Confirmed by member ${fmtDateTime(b.instant("received_at"))}" else if (status == "released") "Not yet confirmed by member" else "")
            KeyValue("Returned", fmtDateTime(b.instant("returned_at")).takeIf { b.text("returned_at").isNotBlank() }.orEmpty())
            KeyValue("Officer remark", b.text("remark"))
        } }
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

/** Vertical progress with the real times recorded at each step. */
@Composable
private fun BorrowingTimeline(b: JsonObject) {
    val status = b.text("status")
    val reached = when (status) { "pending", "returned_for_correction" -> 0; "approved" -> 1; "released" -> 2; "returned" -> 3; else -> -1 }
    if (reached < 0) { NoticeCard(if (status == "cancelled") "Cancelled" else "Declined", if (status == "cancelled") "This request was cancelled." else "This request was declined. ${b.text("remark")}", Tone.NEUTRAL); return }
    val steps = listOf(
        "Requested" to "For ${fmtDateTime(b.instant("starts_at"))}",
        "Approved by equipment officer" to (if (reached >= 1) "Reserved for you" else "Waiting for review"),
        "Handed over" to listOfNotNull(b.instant("released_at")?.let { fmtDateTime(it) }, b.instant("received_at")?.let { "you confirmed receipt" }).joinToString(" · ").ifBlank { "At the range" },
        "Returned" to (b.instant("returned_at")?.let { fmtDateTime(it) } ?: "Due ${fmtDateTime(b.instant("ends_at"))}"),
    )
    RaisedCard { Column(Modifier.padding(16.dp)) {
        Text("Progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(bottom = 10.dp))
        steps.forEachIndexed { i, (title, sub) ->
            val done = i <= reached; val current = i == reached + 1
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(if (done) Club.forest else MaterialTheme.colorScheme.surface)
                        .then(if (!done) Modifier.border(2.5.dp, if (current) Club.lime else MaterialTheme.colorScheme.outlineVariant, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center) { if (done) ph.capstone.archeryclub.ui.ClubIcon("check", Modifier.size(13.dp), Color.White) }
                    if (i < steps.lastIndex) Box(Modifier.width(2.dp).weight(1f).defaultMinSize(minHeight = 16.dp).background(if (i < reached) Club.forest else MaterialTheme.colorScheme.outlineVariant))
                }
                Column(Modifier.padding(bottom = if (i < steps.lastIndex) 12.dp else 0.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = if (done || current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = if (current && b.text("status") == "released" && isOverdue(b)) Club.roseInk else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } }
}
