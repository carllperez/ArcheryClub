package ph.capstone.archeryclub.connected

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.Instant

/* Partner club representatives (migration 044): they join with an invite code from the host club's Interclub officer,
 * never through the DLSU membership application, and only ever see the events they were invited to. */

/** On the applicant's Home: the way out of the DLSU application for someone who is here for a partner club. */
@Composable
fun PartnerChoiceCard(onPartner: () -> Unit) = RaisedCard(onClick = onPartner) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconChip("users", Tone.INFO)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Here for another university's club?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
            Text("Partner club representatives don't apply for membership. Enter the invite code the host club sent you.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", fontSize = 22.sp)
    }
}

/** "I'm a partner club representative": the code plus the few details a host club needs, no DLSU ID or college. */
@Composable
fun PartnerClaimScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit, onJoined: () -> Unit) {
    val profile = workspace.records("profiles").find { it.text("id") == workspace.text("user_id") }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(profile?.text("full_name").orEmpty()) }
    var school by rememberSaveable { mutableStateOf(profile?.text("university").orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(profile?.text("phone").orEmpty()) }
    var tried by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    val errors = partnerClaimErrors(code, name, school, phone)
    fun shown(k: String) = if (tried) errors[k] else null
    ScreenShell("Partner club representative", onBack, snackbar) {
        Text("The host club's Interclub officer sends each partner club an invite code. It works once, for one event.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(code, { code = inviteCodeInput(it); serverError = null }, label = { Text("Invite code") }, singleLine = true,
            placeholder = { Text("e.g. 7KQ2-9F01") }, isError = shown("code") != null || serverError != null,
            supportingText = { Text(serverError ?: shown("code") ?: "8 letters or numbers, from the host club") },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it.take(160) }, label = { Text("Full name") }, singleLine = true, isError = shown("full_name") != null,
            supportingText = { shown("full_name")?.let { Text(it) } }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(school, { school = it.take(160) }, label = { Text("University or school") }, singleLine = true, isError = shown("university") != null,
            placeholder = { Text("e.g. University of the Philippines Diliman") },
            supportingText = { shown("university")?.let { Text(it) } }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = mobileInput(it) }, label = { Text("Mobile number") }, singleLine = true, isError = shown("phone") != null,
            supportingText = { Text(shown("phone") ?: MOBILE_HELP) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        CardButton(if (busy) "Checking…" else "Join as representative", Modifier.fillMaxWidth(), primary = true, enabled = !busy && vm != null) {
            tried = true
            if (errors.isEmpty()) vm?.saveForResult("claim_partner_invite", buildJsonObject { put("p_data", buildJsonObject {
                put("code", code); put("full_name", name.trim()); put("university", school.trim()); put("phone", phone.trim())
            }) }) { result ->
                val r = result as? JsonObject
                if (r?.text("ok") == "true") onJoined() else serverError = r?.text("message")?.ifBlank { null } ?: "That invite code isn't valid."
            }
        }
        Text("Joining as a representative doesn't make you a DLSU Archery Club member. You'll only see the events you're invited to.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A partner representative's Home: the events they were invited to, and nothing from the club's own records. */
@Composable
fun PartnerHome(workspace: JsonObject, fullName: String, busy: Boolean, snackbar: SnackbarHostState, bottomBar: @Composable () -> Unit,
                onRoute: (String) -> Unit, updates: List<JsonObject> = emptyList(), onAcknowledge: (JsonObject) -> Unit = {}, onPartner: () -> Unit) {
    val me = workspace.text("user_id")
    val events = partnerEvents(workspace)
    val clubs = workspace.records("partner_clubs").associate { it.text("id") to it.text("name") }
    val myClub = workspace.records("event_access").firstOrNull { it.text("user_id") == me && it.text("revoked_at").isBlank() }
        ?.let { clubs[it.text("partner_id")] }.orEmpty()
    HeroScaffold(if (fullName.isBlank() || fullName == "Club account") "Welcome" else "Welcome, ${firstName(fullName)}", snackbar = snackbar, busy = busy, bottomBar = bottomBar,
        eyebrow = { Pill(("PARTNER REPRESENTATIVE" + if (myClub.isNotBlank()) " · ${myClub.uppercase()}" else ""), Club.lime, Club.forestDeep) },
        heroExtra = { Text("DLSU Archery Club events you're representing your club at.", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium) }) {
        // Replies from DLSU (a resolved concern, migration 045) and other notices, on top so they are not missed.
        if (updates.isNotEmpty()) {
            DeskLabel("Updates from DLSU")
            updates.forEach { n -> NoticeItem(n, busy, onOpen = { onAcknowledge(n); onRoute("interclub:${n.text("record_id")}") }, onAcknowledge = { onAcknowledge(n) }) }
            DeskLabel("Your events")
        }
        if (events.isEmpty()) EmptyState("No events yet", "Your invitation may have been withdrawn. Ask the host club for a new code.", "Enter another code", onPartner)
        events.forEach { e ->
            val mine = icDelegates(workspace, e.text("event_id")).size
            DeskRow(e.text("shared_title"), "${fmtDay(e.instant("shared_start"))} · ${e.text("shared_venue")}",
                leading = { IconChip("calendar", Tone.POSITIVE) },
                status = when (e.text("status")) { "preparation" -> "Sign-ups open" to Tone.INFO; "active" -> "Event day" to Tone.WARNING; "completed" -> "Finished" to Tone.NEUTRAL; else -> "Archived" to Tone.NEUTRAL },
                onClick = { onRoute("interclub:${e.text("event_id")}") }) {
                Text("$mine delegate${if (mine == 1) "" else "s"} registered" +
                    if (e.text("status") == "preparation") " · sign-ups close ${fmtDay(e.instant("registration_closes"))}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (events.isNotEmpty()) TextButton(onClick = onPartner, modifier = Modifier.fillMaxWidth()) { Text("Have a code for another event?") }
    }
}

/** Event page, Interclub officer: invite a partner club by code, and see each code's state. */
@Composable
fun PartnerInvites(workspace: JsonObject, event: JsonObject, busy: Boolean, vm: SystemViewModel?, canInvite: Boolean,
                   openNew: Boolean = false, onOpened: () -> Unit = {}) {
    val eid = event.text("event_id")
    val invites = inviteViews(workspace, eid, Instant.now()) { memberName(workspace, it) }
    var creating by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<Pair<String, String>?>(null) } // code, club name
    var cancelling by remember { mutableStateOf<InviteView?>(null) }
    // "THIS STEP · Invite" and the Invite link open the new-code dialog here.
    LaunchedEffect(openNew) { if (openNew && canInvite) { creating = true; onOpened() } }
    DeskLabel("Invite codes", if (canInvite) "New code" else null) { creating = true }
    if (invites.isEmpty()) Text(if (canInvite) "Create a code for each partner club and send it through the club's official channel."
        else "No invite codes yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    invites.forEach { v ->
        DeskRow(v.partnerName, when (v.state) {
            InviteState.USED -> "Used by ${v.claimedBy}"
            InviteState.WAITING -> "Code ${v.code} · expires ${fmtDay(v.expires)}"
            else -> "Code ${v.code}"
        }, leading = { IconChip("users", if (v.state == InviteState.USED) Tone.POSITIVE else Tone.NEUTRAL) }, status = v.state.label to v.state.tone) {
            if (v.state == InviteState.WAITING && canInvite) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton("Show code", Modifier.weight(1f), enabled = !busy) { created = v.code to v.partnerName }
                CardButton("Cancel", Modifier.weight(1f), danger = true, enabled = !busy) { cancelling = v }
            }
        }
    }
    if (creating) {
        val clubs = workspace.records("partner_clubs").sortedBy { it.text("name") }
        var pick by remember { mutableStateOf(clubs.firstOrNull()?.text("id").orEmpty()) }
        AlertDialog(onDismissRequest = { creating = false }, title = { Text("Invite a partner club") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (clubs.isEmpty()) Text("Add the partner club first (Interclub → Add a partner club).")
                else {
                    Text("Which club is this code for? Only that club's representative can use it.", style = MaterialTheme.typography.bodySmall)
                    clubs.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(pick == c.text("id"), { pick = c.text("id") }); Text(c.text("name"))
                    } }
                }
            } },
            confirmButton = { TextButton(enabled = pick.isNotBlank() && !busy && vm != null, onClick = {
                vm?.saveForResult("create_partner_invite", buildJsonObject { put("p_data", buildJsonObject { put("event_id", eid); put("partner_id", pick) }) }) { r ->
                    (r as? JsonObject)?.text("code")?.takeIf { it.isNotBlank() }?.let { created = it to (clubs.find { c -> c.text("id") == pick }?.text("name").orEmpty()) }
                    creating = false
                }
            }) { Text("Create code", fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
    }
    created?.let { (code, club) ->
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        val message = inviteMessage(event.text("shared_title"), club, code, fmtDay(event.instant("registration_closes")))
        AlertDialog(onDismissRequest = { created = null }, title = { Text("Code for $club") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp, color = Club.forestDeep)
                Text("Send it through $club's official channel. It works once and expires when partner sign-ups close.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            confirmButton = { TextButton(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message), "Send invite code"))
            }) { Text("Share", fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(message)); created = null }) { Text("Copy and close") } })
    }
    cancelling?.let { v ->
        AlertDialog(onDismissRequest = { cancelling = null }, title = { Text("Cancel code ${v.code}?") },
            text = { Text("${v.partnerName} won't be able to use it. You can create a new code afterwards.") },
            confirmButton = { TextButton(enabled = !busy && vm != null, onClick = {
                vm?.save("revoke_partner_invite", buildJsonObject { put("p_data", buildJsonObject { put("id", v.id) }) }) { cancelling = null }
            }) { Text("Cancel code", fontWeight = FontWeight.ExtraBold, color = Club.roseInk) } },
            dismissButton = { TextButton(onClick = { cancelling = null }) { Text("Keep") } })
    }
}

/** A partner representative's Profile tab, in the same green-hero style as their Home (22 Nov: the plain member
 *  list looked unfinished and offered "My application" and "All modules", which don't apply to them). */
@Composable
fun PartnerProfilePage(workspace: JsonObject, fullName: String, noticeCount: Int, state: SystemState, snackbar: SnackbarHostState,
                       bottomBar: @Composable () -> Unit, onPage: (String) -> Unit, onRoute: (String) -> Unit, onSignOut: () -> Unit,
                       recovery: @Composable () -> Unit) {
    val me = workspace.text("user_id")
    val profile = workspace.records("profiles").find { it.text("id") == me }
    val access = workspace.records("event_access").firstOrNull { it.text("user_id") == me && it.text("revoked_at").isBlank() }
    val club = workspace.records("partner_clubs").find { it.text("id") == access?.text("partner_id") }
    val events = partnerEvents(workspace)
    var signOut by remember { mutableStateOf(false) }
    HeroScaffold(fullName, snackbar = snackbar, busy = state.busy, bottomBar = bottomBar,
        eyebrow = { Pill("PARTNER REPRESENTATIVE", Club.lime, Club.forestDeep) },
        heroExtra = { Text("Guest account for DLSU Archery Club interclub events", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium) }) {
        if (state.recovery) recovery()
        // Who they speak for.
        if (club != null) RaisedCard {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ClubBadge(club.text("name"), club.text("id"), 48)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("REPRESENTING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                    Text(club.text("name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    profile?.text("university")?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        DeskLabel("Your club's events")
        RowGroup {
            GroupRow("calendar", "Your events", "${events.size} shared with your club", onClick = { onPage("home") })
            GroupRow("bell", "Notices", tint = Club.amberInk, chip = Club.amberSoft,
                trailing = { if (noticeCount > 0) Badge { Text("$noticeCount") } }, onClick = { onPage("notices") })
            GroupRow("plus", "Have a code for another event?", "Enter the invite code the host club sent", divider = false, onClick = { onRoute("partner_claim") })
        }
        OutlinedButton(onClick = { signOut = true }, enabled = !state.busy, shape = CircleShape, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Club.roseSoft)) { Text("Sign out", color = Club.roseInk, fontWeight = FontWeight.ExtraBold) }
    }
    if (signOut) ConfirmDialog("Sign out?", "Sign back in with the same email to see your club's events.", "Sign out",
        onConfirm = { signOut = false; onSignOut() }, onDismiss = { signOut = false }, destructive = true)
}
