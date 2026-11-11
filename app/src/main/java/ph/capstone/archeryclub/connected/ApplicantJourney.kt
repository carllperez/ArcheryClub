package ph.capstone.archeryclub.connected

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ClubIcon

/* The applicant journey: until the membership is Active, Home is the application, and the last
 * step (paying the membership fee) happens inside one card on Home. */

private fun openPrivate(vm: SystemViewModel, context: Context, path: String) =
    vm.openDocument(path) { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

@Composable
private fun StepLabel(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun PayToRow(icon: String, title: String, number: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(number) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            ClubIcon(icon, Modifier.size(18.dp), Club.forest)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CardButton(if (copied) "Copied" else "Copy") { clipboard.setText(AnnotatedString(number.replace(" ", ""))); copied = true }
    }
}

/** The membership fee step, done entirely in this card: where to pay, the receipt's reference number, the screenshot, send. */
@Composable
fun MembershipFeeStep(workspace: JsonObject, fee: FeeView, busy: Boolean, vm: SystemViewModel) {
    val context = LocalContext.current
    val details = paymentDetails(workspace.records("settings").firstOrNull())
    val step = payStep(fee)
    val payment = fee.payment
    val proof = payment?.let { p -> workspace.records("documents").filter { it.text("record_id") == p.text("id") && it.text("module") == "12" && it.text("superseded_by").isBlank() } }.orEmpty()
    var ref by rememberSaveable(payment?.text("id")) { mutableStateOf(payment?.text("reference").orEmpty()) }
    var tried by remember { mutableStateOf(false) }
    var pickAfterSave by rememberSaveable { mutableStateOf(false) }
    var sendAfterSave by rememberSaveable { mutableStateOf(false) }
    var confirmSend by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(payment)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = current?.text("id")
        if (uri != null && id != null) vm.upload(context, uri, 12, id, "Receipt screenshot")
    }
    // A screenshot needs a saved payment to attach to: save the reference, wait for the refresh, then open the picker.
    LaunchedEffect(payment?.text("id"), payment?.text("reference")) {
        if (payment != null && payment.text("reference") == receiptReference(ref)) {
            if (pickAfterSave) { pickAfterSave = false; picker.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }
            if (sendAfterSave) { sendAfterSave = false; confirmSend = true }
        }
    }
    fun save(thenPick: Boolean, thenSend: Boolean = false) {
        val clean = receiptReference(ref) ?: run { tried = true; return }
        pickAfterSave = thenPick; sendAfterSave = thenSend
        vm.command("save_financial_request", buildJsonObject {
            payment?.let { p -> put("id", p.text("id")); p.text("version").toLongOrNull()?.let { put("version", it) } }
            put("kind", "payment"); put("purpose", fee.fee.text("purpose")); put("amount", fee.fee.text("amount")); put("reference", clean)
        })
    }
    val action = payAction(ref, payment, proof.isNotEmpty())
    // Replacing a screenshot never needs the reference saved again; a first upload needs a saved payment to attach to.
    fun screenshot() { if (payment == null) save(thenPick = true) else picker.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }

    if (step == PayStep.CHECKING) {
        RaisedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconChip("clock", Tone.INFO, 46)
                    Column(Modifier.weight(1f)) {
                        Text("The Treasurer is checking", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        Text("You'll get a notice when it's verified", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    KeyValue("Amount", peso(fee.amount)); KeyValue("Reference no.", payment?.text("reference").orEmpty())
                    KeyValue("Sent", fmtDate(payment?.date("created_at")))
                }
                proof.firstOrNull()?.let { d -> CardButton("Open my receipt", Modifier.fillMaxWidth()) { openPrivate(vm, context, d.text("path")) } }
                Text("You can't change it while it's being checked. If something is wrong, the Treasurer sends it back with a note and you fix it here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RaisedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StepLabel("Unlocks when you're active")
                Text("Practice sessions · Club gear · Training log · Elections", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    RaisedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    StepLabel("Last step · membership fee")
                    Text(peso(fee.amount), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                }
                StatusPill("Due ${fmtDate(fee.due)}", if (fee.state == FeeState.OVERDUE) Tone.DANGER else Tone.WARNING)
            }
            if (step == PayStep.RETURNED) NoticeCard("The Treasurer sent it back", payment?.text("remark")?.ifBlank { null } ?: "Check the reference number and the screenshot, then resend.", Tone.DANGER)

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StepLabel("1 · Send ${peso(fee.amount)} to the club")
                if (details.isEmpty) Text("The Treasurer hasn't added the club's GCash or bank details yet. Check back soon, or ask an officer where to pay.",
                    style = MaterialTheme.typography.bodySmall)
                if (details.hasGcash) PayToRow("money", "GCash" + details.gcashName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), details.gcashNumber)
                if (details.hasBank) PayToRow("settings", listOf(details.bankName, details.bankAccountName).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Bank transfer" }, details.bankAccountNumber)
                Text("Any GCash or bank transfer works, as long as the receipt shows a reference number.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StepLabel("2 · Reference number on your receipt")
                val bad = tried && receiptReference(ref) == null
                OutlinedTextField(ref, { ref = it; tried = false }, singleLine = true, isError = bad, placeholder = { Text("e.g. 9046292514078") },
                    supportingText = { Text(if (bad) "Use 6 to 30 letters or numbers, exactly as on the receipt" else "GCash: \"Ref No.\" · Bank: transaction or reference number") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StepLabel("3 · Screenshot of the receipt")
                val doc = proof.firstOrNull()
                if (doc != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (step == PayStep.RETURNED) Club.roseSoft else Club.mint)
                    .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClubIcon("doc", Modifier.size(18.dp), if (step == PayStep.RETURNED) Club.roseInk else Club.forestDeep)
                    Text(doc.text("name"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1,
                        color = if (step == PayStep.RETURNED) Club.roseInk else Club.forestDeep)
                    TextButton(onClick = { openPrivate(vm, context, doc.text("path")) }) { Text("Open") }
                    TextButton(onClick = { screenshot() }, enabled = !busy) { Text("Replace", fontWeight = FontWeight.ExtraBold) }
                } else {
                    CardButton("Upload screenshot", Modifier.fillMaxWidth(), primary = action == PayAction.UPLOAD_PROOF, enabled = !busy && receiptReference(ref) != null) { screenshot() }
                }
            }

            // One button: a changed reference is saved on the way, then the send is confirmed.
            val sendLabel = if (step == PayStep.RETURNED) "Resend for checking" else "Send for checking"
            when (action) {
                PayAction.SAVE_REFERENCE -> if (proof.isNotEmpty()) StickyLike(sendLabel, !busy) { save(thenPick = false, thenSend = true) }
                    else StickyLike(sendLabel, false) {}
                PayAction.SEND -> StickyLike(sendLabel, !busy) { confirmSend = true }
                else -> StickyLike(sendLabel, false) {}
            }
            when {
                receiptReference(ref) == null -> Text("Enter the reference number to continue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                proof.isEmpty() -> Text("Upload the screenshot to continue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirmSend && payment != null) ConfirmDialog("Send ${peso(fee.amount)} for checking?",
        "Reference no. ${payment.text("reference")}. The Treasurer checks it against the club's account. You can't change it while it's being checked.", "Send",
        onConfirm = {
            confirmSend = false
            vm.command("submit_financial_request", buildJsonObject { put("id", payment.text("id")); payment.text("version").toLongOrNull()?.let { put("version", it) } })
        }, onDismiss = { confirmSend = false })
}

@Composable
private fun StickyLike(text: String, enabled: Boolean, onClick: () -> Unit) =
    Button(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep, contentColor = Color.White)) { Text(text, fontWeight = FontWeight.ExtraBold) }

/** Applicants keep Profile: notices, what they submitted, and sign out. Services stay hidden until they're active. */
@Composable
fun ApplicantProfilePage(
    workspace: JsonObject, fullName: String, noticeCount: Int, state: SystemState, snackbar: SnackbarHostState, bottomBar: @Composable () -> Unit,
    onPage: (String) -> Unit, onModule: (Int) -> Unit, onSignOut: () -> Unit, recovery: @Composable () -> Unit,
) {
    val user = workspace.text("user_id")
    val membership = workspace.records("memberships").find { it.text("user_id") == user }
    val applied = workspace.records("applications").any { it.text("user_id") == user }
    var signOut by remember { mutableStateOf(false) }
    HeroScaffold(fullName, snackbar = snackbar, overlap = 44.dp, busy = state.busy, bottomBar = bottomBar,
        eyebrow = { Pill(if (membership != null) "APPLICANT · ${membership.text("status").uppercase()}" else "APPLICANT", Club.amberSoft, Club.amberInk) }) {
        if (state.recovery) recovery()
        RowGroup {
            GroupRow("bell", "Notices", tint = Club.amberInk, chip = Club.amberSoft, trailing = { if (noticeCount > 0) Badge { Text("$noticeCount") } }, onClick = { onPage("notices") })
            GroupRow("doc", if (applied) "View what I submitted" else "Start my application", if (applied) "Details and documents" else "", divider = false, onClick = { onModule(2) })
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ClubIcon("lock", Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Activities, gear, training and the other club services unlock when your membership is active.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        OutlinedButton(onClick = { signOut = true }, enabled = !state.busy, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Club.roseSoft)) { Text("Sign out", color = Club.roseInk, fontWeight = FontWeight.ExtraBold) }
    }
    if (signOut) ConfirmDialog("Sign out?", "Your application is saved. Sign back in any time to continue.", "Sign out",
        onConfirm = { signOut = false; onSignOut() }, onDismiss = { signOut = false }, destructive = true)
}

/** Shown once per device when an applicant's membership turns Active. */
@Composable
fun WelcomeActive(firstName: String, feeAmount: String?, onContinue: () -> Unit) {
    LightStatusBarIcons()
    Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Club.forestDeep, Club.forest))).verticalScroll(rememberScrollState()).padding(24.dp).padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ClubMark(104)
        Pill("ACTIVE MEMBER", Club.lime, Club.forestDeep)
        Text("You're in, $firstName!", style = MaterialTheme.typography.headlineLarge, color = Color.White, textAlign = TextAlign.Center)
        Text((feeAmount?.let { "Your $it payment was verified. " }.orEmpty()) + "Welcome to the De La Salle University Archery Club.",
            style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = .92f), textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .12f)).padding(horizontal = 16.dp, vertical = 6.dp)) {
            listOf("calendar" to "Register for practice sessions", "box" to "Borrow club bows and arrows", "target" to "Log rounds and get coach feedback").forEach { (icon, text) ->
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ClubIcon(icon, Modifier.size(22.dp), Club.lime); Text(text, color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        Text("Visit the range at least once a week to stay active (Bylaws 2.3).", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .85f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onContinue, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Club.forestDeep)) { Text("Go to my Home", fontWeight = FontWeight.ExtraBold) }
    }
}

/** Remembers on this device that the welcome was shown, so it appears only once per account. */
fun welcomedKey(user: String) = "welcomed:$user"
fun Context.clubPrefs() = getSharedPreferences("club", Context.MODE_PRIVATE)

/** The Treasurer records where members pay. */
@Composable
fun PaymentDetailsDialog(current: PaymentDetails, busy: Boolean, onSave: (JsonObject) -> Unit, onDismiss: () -> Unit) {
    var gName by rememberSaveable { mutableStateOf(current.gcashName) }
    var gNumber by rememberSaveable { mutableStateOf(current.gcashNumber) }
    var bank by rememberSaveable { mutableStateOf(current.bankName) }
    var bName by rememberSaveable { mutableStateOf(current.bankAccountName) }
    var bNumber by rememberSaveable { mutableStateOf(current.bankAccountNumber) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Where members pay") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Applicants see this on their pay step. Fill in GCash, a bank account, or both.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(gName, { gName = it }, label = { Text("GCash account name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(gNumber, { gNumber = it }, label = { Text("GCash number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bName, { bName = it }, label = { Text("Bank account name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bNumber, { bNumber = it }, label = { Text("Bank account number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (gNumber.isNotBlank() || bNumber.isNotBlank()), onClick = {
                onSave(buildJsonObject { put("gcash_name", gName); put("gcash_number", gNumber); put("bank_name", bank); put("bank_account_name", bName); put("bank_account_number", bNumber) })
            }) { Text("Save", fontWeight = FontWeight.ExtraBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Submitted and waiting for the Secretary: who has it, since when, and the steps still ahead (approve, then pay). */
@Composable
fun WaitingForReview(view: ApplicationView, fee: Double) {
    val submitted = view.application?.date("submitted_at")
    RaisedCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconChip("clock", Tone.INFO, 46)
                Column(Modifier.weight(1f)) {
                    Text("With the Secretary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text(submitted?.let { "Submitted ${fmtDate(it)}" } ?: "Submitted", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StepLabel("What happens next")
            waitingSteps(view, fee).forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(if (step.current) Club.lime else MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold,
                            color = if (step.current) Club.forestDeep else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(step.title, style = MaterialTheme.typography.titleSmall,
                            color = if (step.current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
