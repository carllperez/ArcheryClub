package ph.capstone.archeryclub.connected

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.LocalDate

/* Finance (M12): what a member owes and has paid, one money record, and the treasurer's desk. */

private fun versionOf(row: JsonObject) = buildJsonObject { put("id", row.text("id")); row.text("version").toLongOrNull()?.let { put("version", it) } }
private fun hasProof(workspace: JsonObject, recordId: String) = workspace.records("documents").any { it.text("record_id") == recordId && it.text("module") == "12" }
private fun kindLabel(kind: String) = when (kind) { "payment" -> "Payment"; "expense" -> "Club expense"; "reimbursement" -> "Reimbursement"; else -> human(kind) }

/** Picks a file for a money record and uploads it as proof (module 12). */
@Composable
private fun rememberProofPicker(onUpload: (android.net.Uri, Int, String, String) -> Unit): (String) -> Unit {
    var target by rememberSaveable { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null && target.isNotBlank()) onUpload(uri, 12, target, "Proof of payment or receipt") }
    return { id -> target = id; launcher.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }
}

/** The three steps for sending a payment or claim: details → proof → send. Shared by the payments list and the record screen. */
@Composable
private fun SendSteps(workspace: JsonObject, record: JsonObject, busy: Boolean, onEdit: () -> Unit, onProof: () -> Unit, onSend: () -> Unit) {
    val proof = hasProof(workspace, record.text("id"))
    val reference = record.text("kind") != "payment" || record.text("reference").isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (record.text("status") in setOf("incomplete", "returned_for_correction") && record.text("remark").isNotBlank())
            NoticeCard("The treasurer sent this back", record.text("remark"), Tone.DANGER)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardButton(if (reference) "Edit details" else "Add reference no.", Modifier.weight(1f), enabled = !busy, onClick = onEdit)
            CardButton(if (proof) "Replace proof" else "Upload proof", Modifier.weight(1f), primary = !proof, enabled = !busy, onClick = onProof)
        }
        if (proof) CardButton("Send for checking", Modifier.fillMaxWidth(), primary = reference, enabled = !busy && reference, onClick = onSend)
        Text(when { !reference -> "Add the reference number from your receipt or transfer."; !proof -> "Upload a photo or screenshot of your receipt."; else -> "Ready. The treasurer will check it and you'll get a notice." },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PaymentsScreen(
    workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, isMember: Boolean, onBack: () -> Unit, onOpen: (String) -> Unit,
    onCommand: (String, JsonObject, () -> Unit) -> Unit, onStart: (String, JsonObject) -> Unit, onUpload: (android.net.Uri, Int, String, String) -> Unit,
) {
    val user = workspace.text("user_id")
    val fees = feeViews(workspace, user, LocalDate.now())
    val owed = amountOwed(fees)
    val open = fees.filter { it.state != FeeState.PAID }
    val overdue = open.count { it.state == FeeState.OVERDUE }
    val history = workspace.records("financial_records").filter { it.text("user_id") == user }.sortedByDescending { it.text("created_at") }
    val pickProof = rememberProofPicker(onUpload)
    var sending by remember { mutableStateOf<JsonObject?>(null) }
    HeroScaffold(if (owed > 0) peso(owed) else "All paid up", onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill("PAYMENTS", Color.White.copy(alpha = .16f), Color.White) },
        heroExtra = { Text(when { open.isEmpty() -> "No fees waiting for you."; overdue > 0 -> "$overdue overdue · pay as soon as you can"
            else -> "${open.size} fee${if (open.size == 1) "" else "s"} open · nothing overdue" }, color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium) }) {
        open.forEach { f ->
            RaisedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconChip("money", f.state.tone, 44)
                        Column(Modifier.weight(1f)) {
                            Text(f.fee.text("purpose"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                            Text("${peso(f.amount)} · due ${fmtDate(f.due)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(f.state.label, f.state.tone)
                    }
                    when (f.state) {
                        FeeState.DUE, FeeState.OVERDUE -> {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("HOW TO PAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("1. Pay the treasurer the way the club announced.\n2. Enter the reference number and upload the receipt.\n3. The treasurer checks it and you get a notice.", style = MaterialTheme.typography.bodySmall)
                            }
                            CardButton("I've paid · add the details", Modifier.fillMaxWidth(), primary = true, enabled = !busy) {
                                onStart("save_financial_request", buildJsonObject { put("kind", "payment"); put("purpose", f.fee.text("purpose")); put("amount", f.fee.text("amount")) })
                            }
                        }
                        FeeState.DRAFT, FeeState.RETURNED -> f.payment?.let { p ->
                            SendSteps(workspace, p, busy, onEdit = { onStart("save_financial_request", p) }, onProof = { pickProof(p.text("id")) }, onSend = { sending = p })
                        }
                        FeeState.CHECKING -> Text("The treasurer is checking your payment${f.payment?.text("reference")?.takeIf { it.isNotBlank() }?.let { " (ref $it)" }.orEmpty()}. You'll get a notice.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FeeState.PAID -> {}
                    }
                }
            }
        }
        if (open.isEmpty() && history.isEmpty()) EmptyState("Nothing to pay", "Fees the treasurer charges you will appear here with how to pay them.")
        if (isMember) RowGroup {
            GroupRow("doc", "Ask for a reimbursement", "You paid for the club", divider = false) {
                onStart("save_financial_request", buildJsonObject { put("kind", "reimbursement") })
            }
        }
        // Claims and payments not tied to a fee that still need the member's action.
        history.filter { it.text("status") in setOf("draft", "incomplete", "returned_for_correction") && fees.none { f -> f.payment?.text("id") == it.text("id") } }.forEach { r ->
            RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.text("purpose"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Text("${kindLabel(r.text("kind"))} · ${peso(r.amount())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    financeStatus(r.text("status")).let { (l, t) -> StatusPill(l, t) }
                }
                SendSteps(workspace, r, busy, onEdit = { onStart("save_financial_request", r) }, onProof = { pickProof(r.text("id")) }, onSend = { sending = r })
            } }
        }
        if (history.isNotEmpty()) {
            DeskLabel("History")
            RowGroup {
                history.forEachIndexed { i, r ->
                    val (label, tone) = financeStatus(r.text("status"))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(r.text("purpose"), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text("${kindLabel(r.text("kind"))} · ${fmtDate(r.date("created_at"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) { Text(peso(r.amount()), style = MaterialTheme.typography.titleSmall); StatusPill(label, tone) }
                        TextButton(onClick = { onOpen(r.text("id")) }) { Text("Open") }
                    }
                    if (i < history.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                }
            }
        }
    }
    sending?.let { r -> ConfirmDialog("Send for checking?", "${kindLabel(r.text("kind"))} of ${peso(r.amount())} for \"${r.text("purpose")}\". You can't change it while the treasurer checks it.",
        "Send", onConfirm = { sending = null; onCommand("submit_financial_request", versionOf(r)) {} }, onDismiss = { sending = null }) }
}

@Composable
fun FinanceRecordScreen(
    workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, permissions: Set<String>, onBack: () -> Unit,
    onCommand: (String, JsonObject, () -> Unit) -> Unit, onStart: (String, JsonObject) -> Unit,
    onUpload: (android.net.Uri, Int, String, String) -> Unit, onOpenDocument: (String) -> Unit,
) {
    val r = workspace.records("financial_records").find { it.text("id") == id }
    if (r == null) { ScreenShell("Payment", onBack, snackbar) { EmptyState("Record not found", "Go back and refresh.") }; return }
    val user = workspace.text("user_id"); val own = r.text("user_id") == user
    val approvals = workspace.records("financial_approvals").filter { it.text("record_id") == id }
    val missing = missingApprovals(r, approvals)
    val pickProof = rememberProofPicker(onUpload)
    var sending by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    val finance = "finance" in permissions; val president = "president" in permissions
    val pending = r.text("status") == "pending_verification"
    val (label, tone) = financeStatus(r.text("status"))
    val sticky: (@Composable RowScope.() -> Unit)? = if (finance && pending && !own) ({
        CardButton("Return", Modifier.weight(1f), danger = true, enabled = !busy) { onStart("verify_financial_request", JsonObject(r + ("status" to JsonPrimitive("returned_for_correction")))) }
        StickyButton("Verify", !busy && missing.isEmpty(), Modifier.weight(1.3f)) { verifying = true }
    }) else null
    HeroScaffold(peso(r.amount()), onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill(kindLabel(r.text("kind")).uppercase(), Color.White.copy(alpha = .16f), Color.White); Pill(label, Club.lime, Club.forestDeep) },
        heroExtra = { Text(r.text("purpose"), color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium) }, sticky = sticky) {
        RaisedCard { Column(Modifier.padding(16.dp)) {
            KeyValue("From", if (own) "You" else memberName(workspace, r.text("user_id")))
            KeyValue("Reference", r.text("reference")); KeyValue("Submitted", fmtDate(r.date("created_at")))
            KeyValue("Verified", r.date("verified_at")?.let(::fmtDate).orEmpty()); KeyValue("Remark", r.text("remark"))
        } }
        if (r.text("kind") != "payment") {
            DeskLabel("Approvals before release")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("president" to "President", "finance" to "Treasurer").forEach { (office, name) ->
                    val done = approvals.find { it.text("office") == office }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (done != null) Club.mint else MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
                        Text(if (done != null) "✓ $name" else "$name: waiting", style = MaterialTheme.typography.labelLarge, color = if (done != null) Club.forestDeep else MaterialTheme.colorScheme.onSurfaceVariant)
                        done?.let { Text(it.text("remark"), style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    }
                }
            }
            val myOffice = when { president && "president" in missing -> "president"; finance && "finance" in missing -> "finance"; else -> null }
            if (pending && !own && myOffice != null) CardButton("Approve as ${if (myOffice == "president") "President" else "Treasurer"}", Modifier.fillMaxWidth(), primary = true, enabled = !busy) {
                onStart("approve_funds", JsonObject(r + ("office" to JsonPrimitive(myOffice))))
            }
        }
        DeskLabel("Proof")
        if (!hasProof(workspace, id)) Text("No receipt uploaded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AttachedFiles(workspace, id, onOpenDocument)
        if (own && r.text("status") in setOf("draft", "incomplete", "returned_for_correction")) RaisedCard {
            Column(Modifier.padding(16.dp)) { SendSteps(workspace, r, busy, onEdit = { onStart("save_financial_request", r) }, onProof = { pickProof(id) }, onSend = { sending = true }) }
        }
        if (finance && r.text("kind") == "payment" && r.text("status") == "verified") {
            val fees = workspace.records("fee_assessments")
            val linkedFee = fees.find { it.text("payment_id") == id }
            val match = if (linkedFee == null) matchingFee(r, fees) else null
            when {
                linkedFee != null -> NoticeCard("Settles a fee", "${linkedFee.text("purpose")} · due ${fmtDate(linkedFee.date("due_on"))}", Tone.POSITIVE)
                match != null -> CardButton("Match to \"${match.text("purpose")}\"", Modifier.fillMaxWidth(), primary = true, enabled = !busy) {
                    onCommand("link_fee_payment", buildJsonObject { put("id", match.text("id")); put("payment_id", id) }) {}
                }
            }
        }
    }
    if (sending) ConfirmDialog("Send for checking?", "You can't change it while the treasurer checks it.", "Send",
        onConfirm = { sending = false; onCommand("submit_financial_request", versionOf(r)) {} }, onDismiss = { sending = false })
    if (verifying) VerifyDialog(workspace, r, onCommand) { verifying = false }
}

/** Verify a payment in one step; a verified payment is then matched to the fee it pays, which activates a pending membership. */
@Composable
private fun VerifyDialog(workspace: JsonObject, r: JsonObject, onCommand: (String, JsonObject, () -> Unit) -> Unit, onClose: () -> Unit) {
    val fee = if (r.text("kind") == "payment") matchingFee(r, workspace.records("fee_assessments")) else null
    ConfirmDialog("Verify ${peso(r.amount())}?", buildString {
        append("From ${memberName(workspace, r.text("user_id"))} for \"${r.text("purpose")}\"")
        if (r.text("reference").isNotBlank()) append(", ref ${r.text("reference")}")
        append(". Only verify after checking the receipt against the club's records.")
        if (fee != null) append(" It will also be recorded as payment for \"${fee.text("purpose")}\".")
    }, "Verify", onConfirm = {
        onClose()
        onCommand("verify_financial_request", JsonObject(versionOf(r) + mapOf("status" to JsonPrimitive("verified"), "remark" to JsonPrimitive("Checked against the uploaded receipt.")))) {
            if (fee != null) onCommand("link_fee_payment", buildJsonObject { put("id", fee.text("id")); put("payment_id", r.text("id")) }) {}
        }
    }, onDismiss = onClose)
}

@Composable
fun FinanceDeskScreen(
    workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, permissions: Set<String>, onBack: () -> Unit, onOpen: (String) -> Unit,
    onCommand: (String, JsonObject, () -> Unit) -> Unit, onStart: (String, JsonObject) -> Unit, onRecords: () -> Unit, onMine: () -> Unit,
) {
    val finance = "finance" in permissions; val president = "president" in permissions
    val records = workspace.records("financial_records"); val approvals = workspace.records("financial_approvals")
    val funds = fundsSummary(records)
    val toVerify = financeToVerify(workspace)
    val myOffice = if (president) "president" else "finance"
    val toApprove = toVerify.filter { it.text("kind") != "payment" && myOffice in missingApprovals(it, approvals) }
    val toLink = paymentsToLink(workspace)
    val unpaid = workspace.records("fee_assessments").filter { it.text("payment_id").isBlank() }
    val tabs = buildList {
        if (finance) add("check" to "To check · ${toVerify.size}")
        add("approvals" to "Approvals · ${toApprove.size}")
        if (finance) add("fees" to "Fees")
        add("ledger" to "Ledger")
    }
    var tab by rememberSaveable { mutableStateOf(tabs.first().first) }
    var verifying by remember { mutableStateOf<JsonObject?>(null) }
    val settingsFee = workspace.records("settings").firstOrNull()?.amount("membership_fee") ?: 0.0
    HeroScaffold("Club funds", onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill(if (finance) "TREASURER" else "PRESIDENT", Club.lime, Club.forestDeep) },
        heroExtra = {
            HeroStats(listOf(peso(funds.received) to "Verified in", peso(funds.spent) to "Verified out", peso(funds.balance) to "Balance"))
            HeroTabs(tabs, tab) { tab = it }
        }) {
        when (tab) {
            "check" -> {
                if (toLink.isNotEmpty()) {
                    DeskLabel("Verified, not yet matched to a fee")
                    toLink.forEach { (p, fee) ->
                        DeskRow(memberName(workspace, p.text("user_id")), "${fee.text("purpose")} · ${peso(p.amount())}", leading = { IconChip("money", Tone.INFO) },
                            trailing = { CardButton("Match", primary = true, enabled = !busy) { onCommand("link_fee_payment", buildJsonObject { put("id", fee.text("id")); put("payment_id", p.text("id")) }) {} } },
                            onClick = { onOpen(p.text("id")) })
                    }
                }
                if (toVerify.isEmpty()) EmptyState("Nothing to check", "Payments and claims members send appear here.")
                toVerify.forEach { r ->
                    val missing = missingApprovals(r, approvals)
                    val proof = hasProof(workspace, r.text("id"))
                    DeskRow(memberName(workspace, r.text("user_id")), "${r.text("purpose")}${r.text("reference").takeIf { it.isNotBlank() }?.let { " · ref $it" }.orEmpty()}",
                        leading = { Initials(memberName(workspace, r.text("user_id")), 40) },
                        trailing = { Text(peso(r.amount()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold) }, onClick = { onOpen(r.text("id")) }) {
                        Text(listOfNotNull(kindLabel(r.text("kind")), if (proof) "receipt attached" else "no receipt",
                            missing.takeIf { it.isNotEmpty() }?.let { m -> "waiting for " + m.joinToString(" and ") { if (it == "president") "President" else "Treasurer" } + " approval" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = if (missing.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Club.amberInk)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CardButton("Return", Modifier.weight(1f), danger = true, enabled = !busy) { onStart("verify_financial_request", JsonObject(r + ("status" to JsonPrimitive("returned_for_correction")))) }
                            CardButton("Verify", Modifier.weight(1f), primary = true, enabled = !busy && missing.isEmpty()) { verifying = r }
                        }
                    }
                }
            }
            "approvals" -> {
                if (toApprove.isEmpty()) EmptyState("No approvals waiting", "Club expenses and reimbursements need both the President's and the Treasurer's approval before they're verified.")
                toApprove.forEach { r ->
                    DeskRow(r.text("purpose"), "${kindLabel(r.text("kind"))} · ${memberName(workspace, r.text("user_id"))}", leading = { IconChip("doc", Tone.WARNING) },
                        trailing = { Text(peso(r.amount()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold) }, onClick = { onOpen(r.text("id")) }) {
                        CardButton("Approve as ${if (myOffice == "president") "President" else "Treasurer"}", Modifier.fillMaxWidth(), primary = true, enabled = !busy && r.text("user_id") != workspace.text("user_id")) {
                            onStart("approve_funds", JsonObject(r + ("office" to JsonPrimitive(myOffice))))
                        }
                    }
                }
            }
            "fees" -> {
                RowGroup {
                    GroupRow("users", "Charge a fee to all members", "Once per member") { onStart("assess_fee_bulk", buildJsonObject {}) }
                    GroupRow("profile", "Charge one member") { onStart("assess_fee", buildJsonObject {}) }
                    GroupRow("money", "Membership fee for new members", if (settingsFee > 0) peso(settingsFee) else "Free", divider = false) {
                        onStart("set_membership_fee", buildJsonObject { put("amount", settingsFee.toString()) })
                    }
                }
                DeskLabel("Unpaid fees · ${unpaid.size}")
                if (unpaid.isEmpty()) EmptyState("Everyone is paid up", "Charged fees that haven't been paid appear here.")
                unpaid.sortedBy { it.text("due_on") }.take(30).forEach { f ->
                    val p = paymentFor(f, records); val state = feeState(f, p, LocalDate.now())
                    DeskRow(memberName(workspace, f.text("user_id")), "${f.text("purpose")} · due ${fmtDate(f.date("due_on"))}", leading = { Initials(memberName(workspace, f.text("user_id")), 40) },
                        status = state.label to state.tone, trailing = { Text(peso(f.amount()), style = MaterialTheme.typography.titleSmall) }, onClick = p?.let { { onOpen(it.text("id")) } })
                }
            }
            else -> {
                val ledger = records.filter { it.text("status") == "verified" }.sortedByDescending { it.text("verified_at") }
                if (ledger.isEmpty()) EmptyState("No verified records yet", "Verified payments and expenses build the ledger.")
                if (ledger.isNotEmpty()) RowGroup {
                    ledger.take(25).forEachIndexed { i, r ->
                        val incoming = r.text("kind") == "payment"
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.text("purpose"), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text("${memberName(workspace, r.text("user_id"))} · ${fmtDate(r.date("verified_at"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text((if (incoming) "+" else "−") + peso(r.amount()), style = MaterialTheme.typography.titleSmall, color = if (incoming) Club.forest else Club.roseInk)
                        }
                        if (i < minOf(ledger.size, 25) - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    }
                }
                TextButton(onClick = onRecords, modifier = Modifier.fillMaxWidth()) { Text("All finance records and audit trail") }
            }
        }
        TextButton(onClick = onMine, modifier = Modifier.fillMaxWidth()) { Text("My own payments and claims") }
    }
    verifying?.let { r -> VerifyDialog(workspace, r, onCommand) { verifying = null } }
}
