package ph.capstone.archeryclub.connected

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import ph.capstone.archeryclub.ui.ClubIcon
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.Instant

/* Administration -> Club setup. One checklist; each line says who may change it (the server enforces the same:
 * configure_club needs Administration; set_membership_fee and set_payment_details take Finance or Administration). */

@Composable
private fun OwnerTag(label: String) {
    val treasurer = label.startsWith("Treasurer")
    Text(label, Modifier.clip(RoundedCornerShape(999.dp)).background(if (treasurer) Color(0xFFE3E8F7) else Club.mint).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = if (treasurer) Color(0xFF253B7A) else Club.forestDeep)
}

@Composable
private fun DoneDot(done: Boolean) {
    Box(Modifier.size(24.dp).clip(CircleShape).background(if (done) Club.forest else Color.Transparent)
        .border(2.dp, if (done) Club.forest else Color(0xFFB26B00), CircleShape), contentAlignment = Alignment.Center) {
        if (done) ClubIcon("check", Modifier.size(14.dp), Color.White)
    }
}

@Composable
fun ClubSetupScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, permissions: Set<String>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val settings = workspace.records("settings").firstOrNull()
    val lines = setupChecklist(settings)
    val done = lines.count { it.done }
    ScreenShell("Club setup", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$done of ${lines.size} done", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            LinearProgressIndicator(progress = { done / lines.size.toFloat() }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
                color = Club.forest, trackColor = Club.mint)
            Text("Tags show who can change each part.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        SetupGroup.values().forEach { group ->
            DeskLabel(group.label)
            val groupLines = lines.filter { it.group == group }
            RowGroup {
                groupLines.forEachIndexed { i, l ->
                    val editable = l.canEdit(permissions)
                    Row(Modifier.fillMaxWidth().clickable(enabled = editable && !busy) { onOpen(l.route) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DoneDot(l.done)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(l.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                                OwnerTag(l.ownerLabel)
                            }
                            Text(l.detail, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                color = if (l.done) MaterialTheme.colorScheme.onSurfaceVariant else Club.amberInk, fontWeight = if (l.done) FontWeight.Normal else FontWeight.Bold)
                        }
                        Text(when { !editable -> "Set by ${l.ownerLabel.substringBefore(" ·")}"; l.key == "rules_validated" && !l.done -> "Review"; l.done -> "Edit"; else -> "Set up" },
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold,
                            color = if (editable) Club.forest else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i < groupLines.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                }
            }
        }
    }
}

/** Requirements and categories: a list to add to, remove from and reorder, instead of "one entry per line". */
@Composable
fun SetupListScreen(workspace: JsonObject, key: String, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
                    onCommand: (String, JsonObject, () -> Unit) -> Unit) {
    val settings = workspace.records("settings").firstOrNull()
    val saved = settingList(settings, key)
    var items by rememberSaveable(key) { mutableStateOf(saved.joinToString("\n")) }
    val list = items.split('\n').filter { it.isNotBlank() }
    var draft by rememberSaveable(key) { mutableStateOf("") }
    fun addItem(text: String) { val t = text.trim(); if (t.isNotEmpty() && list.none { it.equals(t, true) }) items = (list + t).joinToString("\n"); draft = "" }
    val changed = list != saved
    val categories = key == "membership_categories"
    ScreenShell(setupListTitle(key), onBack, snackbar, stickyAction = {
        Button(onClick = { onCommand("configure_club", configureClubPayload(settings, mapOf(key to JsonArray(list.map(::JsonPrimitive))))) { onBack() } },
            enabled = !busy && changed && !(categories && list.isEmpty()), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text("Save", fontWeight = FontWeight.ExtraBold) }
    }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(when (key) {
            "membership_categories" -> "Applicants pick one of these. Keep at least one."
            "renewal_requirements" -> "Each one becomes an upload slot when a member renews."
            else -> "Each one becomes an upload slot on the application. The Secretary checks every file before approving."
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RowGroup {
            list.forEachIndexed { i, item ->
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    if (i > 0) TextButton(onClick = { items = list.toMutableList().apply { add(i - 1, removeAt(i)) }.joinToString("\n") }, enabled = !busy) { Text("Up") }
                    TextButton(onClick = { items = (list - item).joinToString("\n") }, enabled = !busy) { Text("Remove", color = Club.roseInk, fontWeight = FontWeight.ExtraBold) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { draft = it.take(120) }, placeholder = { Text(if (categories) "Add a category…" else "Add a requirement…") },
                    singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { addItem(draft) }, enabled = draft.isNotBlank() && !busy) { Text("Add", fontWeight = FontWeight.ExtraBold) }
            }
        }
        val suggestions = setupSuggestions(key, list)
        if (suggestions.isNotEmpty()) {
            DeskLabel("Suggested from the bylaws")
            suggestions.forEach { s -> OutlinedButton(onClick = { addItem(s) }, enabled = !busy, shape = RoundedCornerShape(999.dp)) { Text("+ $s") } }
        }
        if (key != "membership_categories" && changed) NoticeCard("Applies to new submissions", "Applications or renewals already sent keep the list they were sent with.", Tone.WARNING)
    }
}

/** Club name and record keeping: one text each. */
@Composable
fun SetupTextScreen(workspace: JsonObject, key: String, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
                    onCommand: (String, JsonObject, () -> Unit) -> Unit) {
    val settings = workspace.records("settings").firstOrNull()
    val current = settings?.text(key).orEmpty().takeUnless { key == "retention_policy" && it == DEFAULT_RETENTION }.orEmpty()
    var value by rememberSaveable(key) { mutableStateOf(current) }
    val retention = key == "retention_policy"
    ScreenShell(if (retention) "Record keeping" else "Club name", onBack, snackbar, stickyAction = {
        Button(onClick = { onCommand("configure_club", configureClubPayload(settings, mapOf(key to JsonPrimitive(value.trim())))) { onBack() } },
            enabled = !busy && value.trim().length >= 2 && value.trim() != current, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text("Save", fontWeight = FontWeight.ExtraBold) }
    }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (retention) "How long the club keeps member records and files after a membership ends. Members can read this." else "Shown on every screen and notice.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value, { value = it.take(if (retention) 600 else 160) }, label = { Text(if (retention) "Record keeping policy" else "Club name") },
            minLines = if (retention) 3 else 1, singleLine = !retention, modifier = Modifier.fillMaxWidth())
        if (retention) OutlinedButton(onClick = { value = "Keep member records and files for 5 years after the membership ends, then delete them." },
            enabled = !busy, shape = RoundedCornerShape(999.dp)) { Text("Use a 5-year example") }
    }
}

/** Membership fee (Treasurer) and where members pay (Treasurer or Administrator). */
@Composable
fun SetupFeesScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, permissions: Set<String>, vm: SystemViewModel?, onBack: () -> Unit,
                    onCommand: (String, JsonObject, () -> Unit) -> Unit) {
    val settings = workspace.records("settings").firstOrNull()
    val fee = settings?.amount("membership_fee") ?: 0.0
    val pay = paymentDetails(settings)
    val canFee = "finance" in permissions || "administration" in permissions
    var amount by rememberSaveable { mutableStateOf(plainNumber(fee)) }
    var gName by rememberSaveable { mutableStateOf(pay.gcashName) }
    var gNumber by rememberSaveable { mutableStateOf(pay.gcashNumber) }
    var bank by rememberSaveable { mutableStateOf(pay.bankName) }
    var bName by rememberSaveable { mutableStateOf(pay.bankAccountName) }
    var bNumber by rememberSaveable { mutableStateOf(pay.bankAccountNumber) }
    ScreenShell("Fees & payment", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MEMBERSHIP FEE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                OwnerTag("Treasurer · Admin")
            }
            OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(9) }, label = { Text("Yearly fee (₱)") }, singleLine = true,
                enabled = canFee, modifier = Modifier.fillMaxWidth())
            Text(if (canFee) "Approved applicants pay this before they become Active. Set 0 for a free club." else "Only the Treasurer or an Administrator can change the fee.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (canFee) Button(onClick = { onCommand("set_membership_fee", buildJsonObject { put("amount", amount.toDoubleOrNull() ?: 0.0) }) {} },
                enabled = !busy && amount.toDoubleOrNull() != null && amount.toDoubleOrNull() != fee, shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep), modifier = Modifier.fillMaxWidth()) { Text("Save fee", fontWeight = FontWeight.ExtraBold) }
        } }
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WHERE MEMBERS PAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                OwnerTag("Treasurer · Admin")
            }
            OutlinedTextField(gName, { gName = it.take(120) }, label = { Text("GCash account name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(gNumber, { gNumber = it.take(120) }, label = { Text("GCash number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bank, { bank = it.take(120) }, label = { Text("Bank (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bName, { bName = it.take(120) }, label = { Text("Bank account name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(bNumber, { bNumber = it.take(120) }, label = { Text("Bank account number (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Any GCash or bank transfer works if the receipt shows a reference number.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val changed = listOf(gName, gNumber, bank, bName, bNumber).map { it.trim() } != listOf(pay.gcashName, pay.gcashNumber, pay.bankName, pay.bankAccountName, pay.bankAccountNumber)
            Button(onClick = {
                vm?.save("set_payment_details", buildJsonObject { put("p_details", buildJsonObject {
                    put("gcash_name", gName); put("gcash_number", gNumber); put("bank_name", bank); put("bank_account_name", bName); put("bank_account_number", bNumber)
                }) })
            }, enabled = !busy && vm != null && changed && (gNumber.isNotBlank() || bNumber.isNotBlank()), shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep), modifier = Modifier.fillMaxWidth()) { Text("Save payment details", fontWeight = FontWeight.ExtraBold) }
        } }
    }
}

/** A last look against the bylaws; confirming unlocks elections. */
@Composable
fun SetupConfirmScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
                       onCommand: (String, JsonObject, () -> Unit) -> Unit) {
    val settings = workspace.records("settings").firstOrNull()
    val lines = setupChecklist(settings)
    val missing = lines.filter { !it.done && it.key != "rules_validated" }
    val confirmed = settings?.text("rules_validated") == "true"
    val roles = roleViews(workspace, Instant.now())
    val fee = settings?.amount("membership_fee") ?: 0.0
    var agreed by rememberSaveable { mutableStateOf(false) }
    ScreenShell("Confirm club rules", onBack, snackbar, stickyAction = {
        if (!confirmed) Button(onClick = { onCommand("configure_club", configureClubPayload(settings, mapOf("rules_validated" to JsonPrimitive(true)))) { onBack() } },
            enabled = !busy && agreed, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text("Confirm rules", fontWeight = FontWeight.ExtraBold) }
    }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (confirmed) "The rules are confirmed and elections are unlocked. You can still edit any part of Club setup; every change is logged."
            else "Check this matches your bylaws. Confirming unlocks elections; you can still edit later, and every change is logged.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RowGroup {
            val cats = settingList(settings, "membership_categories")
            listOf(
                "Categories" to "${cats.size}: ${cats.joinToString(", ")}",
                "To apply" to settingList(settings, "application_requirements").size.let { if (it == 0) "No documents" else "$it document${if (it == 1) "" else "s"}" },
                "Membership fee" to if (fee > 0) peso(fee) + (paymentDetails(settings).takeIf { !it.isEmpty }?.let { if (it.hasGcash) " · GCash" else " · Bank" } ?: "") else "Free",
                "Officers" to "${roles.size} roles · ${roles.count { it.changedFromDefault }} changed by the club",
                "Records" to (lines.first { it.key == "retention_policy" }.detail),
            ).forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(k, Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                if (i < 4) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        }
        if (missing.isNotEmpty()) NoticeCard("${missing.size} thing${if (missing.size == 1) " is" else "s are"} missing",
            missing.joinToString(", ") { it.title } + ". You can confirm anyway and finish these later.", Tone.DANGER)
        if (!confirmed) Row(Modifier.fillMaxWidth().clickable { agreed = !agreed }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(agreed, { agreed = it })
            Text("These match the club bylaws and the officers approved them.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
