package ph.capstone.archeryclub.connected

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ClubIcon
import java.time.Instant

/* The member's four tabs share one frame: a green hero at the top and the tab bar at the bottom.
 * Home lives in ClubLanding (MemberHome); Activities, Gear and Profile are here. */

private fun profileHeroName(name: String) = name.trim().ifBlank { "Club member" }

@Composable
fun MemberActivitiesPage(
    workspace: JsonObject, state: SystemState, snackbar: SnackbarHostState, bottomBar: @Composable () -> Unit,
    onRoute: (String) -> Unit, onModule: (Int) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf("open") }
    val open = memberActivities(workspace).count { !it.status.isMine }
    val mine = upcomingMine(workspace).size
    HeroScaffold("Activities", snackbar = snackbar, overlap = 0.dp, busy = state.busy, bottomBar = bottomBar,
        heroExtra = { HeroTabs(listOf("open" to "Open · $open", "mine" to "Mine · $mine", "history" to "History"), tab) { tab = it } }) {
        Spacer(Modifier.height(4.dp))
        MemberActivitiesList(workspace, tab, { tab = it }) { onRoute("activity:$it") }
        var calendar by rememberSaveable { mutableStateOf(false) }
        RowGroup {
            GroupRow("calendar", if (calendar) "Hide calendar" else "Calendar view", onClick = { calendar = !calendar })
            GroupRow("bell", "Announcements and attendance records", divider = false, onClick = { onRoute("records:3") })
        }
        if (calendar) RaisedCard { Column(Modifier.padding(12.dp)) {
            RecordCalendar(memberActivities(workspace).map { it.event }, workspace) { onRoute("activity:${it.text("id")}") }
        } }
    }
}

@Composable
fun MemberGearPage(workspace: JsonObject, state: SystemState, snackbar: SnackbarHostState, bottomBar: @Composable () -> Unit, onRoute: (String) -> Unit) {
    val user = workspace.text("user_id")
    val profile = workspace.records("profiles").find { it.text("id") == user }
    HeroScaffold("Club Gear", snackbar = snackbar, overlap = 0.dp, busy = state.busy, bottomBar = bottomBar,
        heroExtra = { Text(gearMatchLine(profile), color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodySmall) }) {
        Spacer(Modifier.height(4.dp))
        MemberEquipmentContent(workspace, { onRoute("item:$it") }) { onRoute("borrowing:$it") }
    }
}

fun gearMatchLine(profile: JsonObject?): String {
    val parts = listOfNotNull(
        profile?.text("dominant_hand")?.takeIf { it.isNotBlank() }?.let { "$it-handed" },
        profile?.text("draw_weight")?.toDoubleOrNull()?.let { "${fmtNumber(it)} lb" },
        profile?.text("arrow_length")?.toDoubleOrNull()?.let { "${fmtNumber(it)}\" arrows" },
    )
    return if (parts.isEmpty()) "Add your archery details to see what fits you" else "Matched to you: " + parts.joinToString(" · ")
}

@Composable
fun MemberProfilePage(
    workspace: JsonObject, profile: JsonObject?, fullName: String, noticeCount: Int, state: SystemState, snackbar: SnackbarHostState,
    bottomBar: @Composable () -> Unit, onPage: (String) -> Unit, onModule: (Int) -> Unit, onRoute: (String) -> Unit,
    onScan: () -> Unit, onRefresh: () -> Unit, onSignOut: () -> Unit, recovery: @Composable () -> Unit,
) {
    val user = workspace.text("user_id")
    val membership = workspace.records("memberships").find { it.text("user_id") == user }
    val summary = participationSummary(workspace, Instant.now())
    val loans = workspace.records("borrowings").count { it.text("user_id") == user && it.text("status") in setOf("released", "returned") }
    val complete = profileCompleteness(profile)
    val name = profileHeroName(fullName)
    val initials = name.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
    var signOut by remember { mutableStateOf(false) }
    LightStatusBarIcons()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) }, bottomBar = bottomBar) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScrollable()) {
            HeroBand(bottomPadding = 70.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(84.dp).clip(CircleShape).background(Club.mint).border(3.dp, Club.lime, CircleShape), contentAlignment = Alignment.Center) {
                        Text(initials, style = MaterialTheme.typography.headlineMedium, color = Club.forestDeep)
                    }
                    Text(name, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill((membership?.text("status") ?: "Member").uppercase() + " MEMBER".takeIf { membership?.text("status") == "Active" }.orEmpty(), Club.mint, Club.forestDeep)
                        membership?.text("category")?.takeIf { it.isNotBlank() }?.let { Pill(it.uppercase(), Color.White.copy(alpha = .18f), Color.White) }
                    }
                }
            }
            Column(Modifier.offset(y = (-48).dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RaisedCard {
                    Row(Modifier.padding(vertical = 14.dp)) {
                        listOf((summary.rate?.let { "$it%" } ?: "—") to "Attendance", "${summary.attended}" to "Sessions", "$loans" to "Gear loans").forEachIndexed { i, (v, l) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(v, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                                Text(l, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (i < 2) VerticalDivider(Modifier.height(40.dp).align(Alignment.CenterVertically), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                state.refreshError?.let { NoticeCard("Refresh needed", it, Tone.WARNING) }
                if (state.recovery) recovery()
                val myRoles = myRoleLines(workspace, Instant.now())
                if (myRoles.isNotEmpty()) RaisedCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (myRoles.size == 1) "YOUR ROLE" else "YOUR ROLES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val roleRows = workspace.records("roles").associateBy { it.text("id") }
                        myRoles.forEach { r ->
                            val desks = (roleRows[r.assignment.text("role_id")]?.get("permissions") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(r.roleName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                                    Text(r.ends?.let { "Until ${fmtDate(it.atZone(java.time.ZoneId.systemDefault()).toLocalDate())}" }.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                                        color = if (r.state == TermState.ENDING) Club.amberInk else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (desks.isEmpty()) "Opens no desks" else "Opens: " + deskOrder(desks).joinToString(", ") { deskTitle(it) },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                RaisedCard(onClick = { onRoute("profile") }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Archery profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            CompletionPill(complete)
                        }
                        SlotBar(complete, 100)
                        Text(gearMatchLine(profile).removePrefix("Matched to you: "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                RowGroup {
                    val owed = amountOwed(feeViews(workspace, workspace.text("user_id"), java.time.LocalDate.now()))
                    GroupRow("money", "Payments & fees", if (owed > 0) "${peso(owed)} due" else membership?.text("status").orEmpty(), onClick = { onModule(12) })
                    GroupRow("target", "My training", "Scorecards and coach feedback", onClick = { onModule(4) })
                    GroupRow("calendar", "Participation history", onClick = { onPage("activities") })
                    GroupRow("vote", "Elections", onClick = { onModule(9) })
                    GroupRow("bell", "Notices", tint = Club.amberInk, chip = Club.amberSoft,
                        trailing = { if (noticeCount > 0) Badge { Text("$noticeCount") } }, onClick = { onPage("notices") })
                    GroupRow("qr", "Scan club code", "Attendance and gear", onClick = onScan)
                    GroupRow("modules", if (myRoles.isNotEmpty()) "All club records" else "All club services", divider = false, onClick = { onPage("modules") })
                }
                SecondaryAction("Refresh records", !state.busy, onClick = onRefresh)
                OutlinedButton(onClick = { signOut = true }, enabled = !state.busy, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Club.roseSoft)) { Text("Sign out", color = Club.roseInk) }
            }
        }
    }
    if (signOut) ConfirmDialog("Sign out?", "You'll need your email and password to sign back in.", "Sign out",
        onConfirm = { signOut = false; onSignOut() }, onDismiss = { signOut = false }, destructive = true)
}

/** One notice as a card: module label, message, and its two actions. */
@Composable
fun NoticeItem(notice: JsonObject, busy: Boolean, onOpen: () -> Unit, onAcknowledge: () -> Unit) {
    val (label, icon, chip, ink) = when (notice.text("module")) {
        "3", "8" -> NoticeStyle("ACTIVITIES", "calendar", Club.mint, Club.forest)
        "5", "10" -> NoticeStyle("GEAR", "clock", Club.amberSoft, Club.amberInk)
        "1", "2", "7" -> NoticeStyle("MEMBERSHIP", "profile", Club.mint, Club.forest)
        "9" -> NoticeStyle("ELECTIONS", "vote", Club.roseSoft, Club.roseInk)
        "11", "4" -> NoticeStyle("TRAINING", "target", Club.mint, Club.forest)
        "12" -> NoticeStyle("FEES", "money", Club.amberSoft, Club.amberInk)
        else -> NoticeStyle("CLUB", "bell", Club.mint, Club.forest)
    }
    RaisedCard(radius = 18.dp) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(chip), contentAlignment = Alignment.Center) { ClubIcon(icon, Modifier.size(20.dp), ink) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = ink, modifier = Modifier.weight(1f))
                    Text(fmtDay(notice.instant("notified_at")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(notice.text("message"), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Button(onClick = onOpen, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 16.dp), modifier = Modifier.heightIn(min = 40.dp)) { Text("View") }
                    OutlinedButton(onClick = onAcknowledge, enabled = !busy, shape = CircleShape, contentPadding = PaddingValues(horizontal = 16.dp),
                        modifier = Modifier.heightIn(min = 40.dp)) { Text("Mark read") }
                }
            }
        }
    }
}

private data class NoticeStyle(val label: String, val icon: String, val chip: Color, val ink: Color)

@Composable
private fun Modifier.verticalScrollable() = this.verticalScroll(androidx.compose.foundation.rememberScrollState())

/** Home for someone who is not an Active member yet: where their application stands and the one next step. */
@Composable
fun ApplicantHome(
    workspace: JsonObject, fullName: String, noticeCount: Int, state: SystemState, snackbar: SnackbarHostState, bottomBar: @Composable () -> Unit,
    onPage: (String) -> Unit, onModule: (Int) -> Unit, onCommand: (String, JsonObject) -> Unit, recovery: @Composable () -> Unit,
    vm: SystemViewModel? = null,
) {
    val user = workspace.text("user_id")
    val membership = workspace.records("memberships").find { it.text("user_id") == user }
    val view = applicationView(workspace)
    val pendingPayment = membership?.text("status") == "Pending payment"
    // No name yet (a brand-new account) gives a plain welcome instead of "Good evening, there".
    val known = fullName.takeUnless { it == "Club account" }.orEmpty()
    HeroScaffold(if (firstName(known).isBlank()) "Welcome to the club" else hello(java.time.LocalTime.now().hour, known), snackbar = snackbar, overlap = 44.dp, busy = state.busy, bottomBar = bottomBar,
        eyebrow = {
            val fee = if (pendingPayment) membershipFeeDue(workspace, user, java.time.LocalDate.now()) else null
            when (fee?.let(::payStep)) {
                PayStep.PAY -> Pill("APPROVED · PAY TO ACTIVATE", Club.lime, Club.forestDeep)
                PayStep.CHECKING -> Pill("PAYMENT SENT", Color.White, Club.forestDeep)
                PayStep.RETURNED -> Pill("PAYMENT NEEDS FIXING", Club.roseSoft, Club.roseInk)
                null -> when {
                    membership != null -> Pill(membership.text("status").uppercase(), Club.mint, Club.forestDeep)
                    view.stage in setOf(ApplicationStage.SUBMITTED, ApplicationStage.UNDER_REVIEW) -> Pill("BEING CHECKED", Club.mint, Club.forestDeep)
                    else -> Unit }
            }
        },
        heroExtra = { ProgressSegments(applicationMilestones(view, membership?.text("status").orEmpty())) }) {
        state.refreshError?.let { NoticeCard("Refresh needed", it, Tone.WARNING) }
        if (state.recovery) recovery()
        val feeDue = if (pendingPayment) membershipFeeDue(workspace, user, java.time.LocalDate.now()) else null
        // The pay step happens right here on Home: no separate Pay button or Payments screen for applicants.
        val waiting = !pendingPayment && membership == null && view.stage in setOf(ApplicationStage.SUBMITTED, ApplicationStage.UNDER_REVIEW)
        if (feeDue != null && vm != null) MembershipFeeStep(workspace, feeDue, state.busy, vm)
        else if (waiting) WaitingForReview(view, workspace.records("settings").firstOrNull()?.amount("membership_fee") ?: 0.0)
        else RaisedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (pendingPayment) "PAYMENT" else "YOUR APPLICATION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val (title, body) = when {
                    pendingPayment -> "Approved — one step left" to "Pay the membership fee. Your membership becomes Active once the club verifies the payment."
                    membership != null -> membership.text("status") to "Review your membership details and renewal status."
                    view.stage == ApplicationStage.NOT_STARTED -> "Ready when you are" to "Fill in your details, upload the club's required documents, then submit for screening."
                    view.stage == ApplicationStage.NEEDS_CHANGES -> "Changes requested" to view.remark.ifBlank { "Update your details or documents, then submit again." }
                    view.stage == ApplicationStage.REJECTED -> "Not approved" to view.remark.ifBlank { "You can start a new application." }
                    else -> view.stage.label to "The club is checking your application. You'll be notified when there's a decision."
                }
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    pendingPayment -> PrimaryAction("Pay membership fee", !state.busy) { onModule(12) }
                    membership != null -> PrimaryAction("View membership") { onModule(1) }
                    view.stage == ApplicationStage.REJECTED -> PrimaryAction("Start a new application", !state.busy) { onCommand("restart_application", buildJsonObject {}) }
                    else -> PrimaryAction(if (view.stage == ApplicationStage.NOT_STARTED) "Start application" else "Open my application") { onModule(2) }
                }
            }
        }
        // Notices live in Profile (its tab shows the count), so Home keeps only the application.
        if (view.stage != ApplicationStage.NOT_STARTED && (pendingPayment || !view.stage.editable))
            TextButton(onClick = { onModule(2) }, modifier = Modifier.fillMaxWidth()) { Text("View what I submitted") }
    }
}
