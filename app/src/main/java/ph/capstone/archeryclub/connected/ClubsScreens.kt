package ph.capstone.archeryclub.connected

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.ScreenShell

/* Many clubs, one app (migration 050). Each account belongs to one club; the platform administrator creates clubs. */

/** An account whose email matches no club (migration 051: the email domain decides the club). A school email can
 *  ask the platform administrator for a club (migration 052); a personal email is told to use the school one. */
@Composable
fun ChooseClubScreen(workspace: JsonObject, state: SystemState, vm: SystemViewModel, snackbar: SnackbarHostState, onInvite: () -> Unit) {
    var picked by remember { mutableStateOf<JsonObject?>(null) }
    val me = workspace.records("profiles").find { it.text("id") == workspace.text("user_id") }
    val email = me?.text("email").orEmpty()
    val domain = emailDomain(email)
    val request = myClubRequest(workspace)
    var asking by rememberSaveable { mutableStateOf(false) }
    val domains = allClubs(workspace).mapNotNull { c -> c.text("email_domain").takeIf { it.isNotBlank() }?.let { "@$it (${c.text("short_name").ifBlank { c.text("name") }})" } }
    val open = allClubs(workspace).filter { it.text("email_domain").isBlank() }
    val personal = isPersonalEmail(email) || domain.isBlank()
    HeroScaffold(if (personal) "Use your school email" else "Your school has no club yet", snackbar = snackbar, busy = state.busy,
        heroExtra = { Text(if (personal) "Each club is only for its own school's email." else "You can ask for one: the club would be for every @$domain account.",
            color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium) }) {
        when {
            personal -> NoticeCard("${email.ifBlank { "This email" }} isn't a school email",
                "Sign out and create an account with your school email" + (if (domains.isNotEmpty()) ": " + domains.joinToString(", ") + "." else ".") +
                    " Partner club representatives can still join an event with the invite code the host club sent.", Tone.INFO)
            request?.text("status") == "pending" -> NoticeCard("Request sent: ${request.text("name")}",
                "The platform administrator will approve it or say why not. You'll get a notice, and once approved, this app opens your new club with you as its administrator.",
                Tone.WARNING)
            request?.text("status") == "declined" && !asking -> {
                NoticeCard("Your request wasn't approved", request.text("remark").ifBlank { "No reason was given." }, Tone.DANGER)
                PrimaryAction("Ask again", !state.busy) { asking = true }
            }
            asking || request == null -> ClubRequestForm(domain, state.busy, vm) { asking = false }
        }
        open.forEach { c ->
            RaisedCard(onClick = { if (!state.busy) picked = c }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Initials(c.text("short_name").ifBlank { c.text("name") }, 44)
                    Column(Modifier.weight(1f)) {
                        Text(c.text("name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        if (c.text("school").isNotBlank()) Text(c.text("school"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        SecondaryAction("Enter an interclub invite code", !state.busy, onInvite)
        TextButton(onClick = { vm.auth("signout", "", "") }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
    picked?.let { c ->
        ConfirmDialog("Join ${c.text("name")}?", "Your account will belong to this club. You can't switch to another club later.",
            "Join", onConfirm = {
                picked = null
                vm.save("join_club", buildJsonObject { put("p_data", buildJsonObject { put("club_id", c.text("id")) }) })
            }, onDismiss = { picked = null })
    }
}

/** What a student fills in to ask for their school's club. The domain comes from their own email, not from a field. */
@Composable
private fun ClubRequestForm(domain: String, busy: Boolean, vm: SystemViewModel, onSent: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var short by rememberSaveable { mutableStateOf("") }
    var school by rememberSaveable { mutableStateOf("") }
    var theme by rememberSaveable { mutableStateOf("green") }
    var note by rememberSaveable { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val errors = buildMap {
        if (name.trim().length < 2) put("name", "Enter the club's name.")
        if (school.trim().length < 2) put("school", "Enter your school's name.")
    }
    RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("REQUEST A CLUB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("For @$domain", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        OutlinedTextField(name, { name = it.take(160) }, label = { Text("Club name") }, placeholder = { Text("e.g. FEU Archery Club") }, singleLine = true,
            isError = attempted && "name" in errors, supportingText = { if (attempted) errors["name"]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(short, { short = it.take(40) }, label = { Text("Short name (optional)") }, placeholder = { Text("e.g. FEU") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(school, { school = it.take(160) }, label = { Text("School") }, placeholder = { Text("e.g. Far Eastern University") }, singleLine = true,
            isError = attempted && "school" in errors, supportingText = { if (attempted) errors["school"]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        ChoiceChips(listOf("green" to "Green", "red" to "Red", "blue" to "Blue"), theme) { theme = it }
        OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note for the platform administrator (optional)") },
            placeholder = { Text("e.g. We have 15 members and an adviser") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Text("If approved, you become the club's first administrator and set its rules. Every @$domain account then joins it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CardButton("Send request", Modifier.fillMaxWidth(), primary = true, enabled = !busy) {
            attempted = true
            if (errors.isEmpty()) vm.save("request_club", buildJsonObject { put("p_data", buildJsonObject {
                put("name", name.trim()); put("short_name", short.trim()); put("school", school.trim()); put("theme", theme); put("note", note.trim())
            }) }) { onSent() }
        }
    } }
}

/** Platform administrator: every club, and a form to create one with its first administrator. */
@Composable
fun PlatformClubsScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, vm: SystemViewModel?) {
    var name by rememberSaveable { mutableStateOf("") }
    var short by rememberSaveable { mutableStateOf("") }
    var school by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var theme by rememberSaveable { mutableStateOf("green") }
    var attempted by remember { mutableStateOf(false) }
    val errors = buildMap {
        if (name.trim().length < 2) put("name", "Enter the club's name.")
        if (!email.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) put("email", "Enter the first administrator's email.")
        val d = domain.trim().trimStart('@').lowercase()
        if (!d.matches(Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$"))) put("domain", "Enter the school's email domain, like up.edu.ph.")
        else if (!email.trim().lowercase().substringAfter('@').let { it == d || it.endsWith(".$d") }) put("email", "The first administrator needs an @$d email.")
    }
    var approving by remember { mutableStateOf<JsonObject?>(null) }
    var declining by remember { mutableStateOf<JsonObject?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    ScreenShell("Clubs", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Students whose school has no club yet (migration 052). Approving creates the club with them as administrator.
        val requests = pendingClubRequests(workspace)
        if (requests.isNotEmpty()) {
            DeskLabel("Club requests · ${requests.size}")
            requests.forEach { r ->
                DeskRow(r.text("name"), listOf("@" + r.text("email_domain"), r.text("school")).joinToString(" · "),
                    leading = { Initials(r.text("short_name").ifBlank { r.text("name") }, 36) }, status = "Waiting" to Tone.WARNING) {
                    Text("Asked by ${r.text("requester")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.text("note").isNotBlank()) Text("\u201C${r.text("note")}\u201D", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CardButton("Approve", Modifier.weight(1f), primary = true, enabled = !busy) { approving = r }
                        CardButton("Decline", Modifier.weight(1f), danger = true, enabled = !busy) { reason = ""; declining = r }
                    }
                }
            }
        }
        approving?.let { r ->
            ConfirmDialog("Create ${r.text("name")}?", "${r.text("requester")} becomes its only administrator and its Membership officer, so they can accept members. Anyone else who signs up with @${r.text("email_domain")} lands in this club as an ordinary applicant, and its officers approve them.",
                "Approve", onConfirm = {
                    approving = null
                    vm?.save("decide_club_request", buildJsonObject { put("p_data", buildJsonObject { put("id", r.text("id")); put("decision", "approve") }) })
                }, onDismiss = { approving = null })
        }
        declining?.let { r ->
            ConfirmDialog("Decline ${r.text("name")}?", "Say why, so the student knows what to do next.", "Decline", destructive = true,
                confirmEnabled = reason.isNotBlank(),
                extra = { OutlinedTextField(reason, { reason = it.take(500) }, label = { Text("Reason") }, minLines = 2, modifier = Modifier.fillMaxWidth()) },
                onConfirm = {
                    declining = null
                    vm?.save("decide_club_request", buildJsonObject { put("p_data", buildJsonObject { put("id", r.text("id")); put("decision", "decline"); put("remark", reason.trim()) }) })
                }, onDismiss = { declining = null })
        }
        DeskLabel("${allClubs(workspace).size} club${if (allClubs(workspace).size == 1) "" else "s"}")
        allClubs(workspace).forEach { c ->
            DeskRow(c.text("name"), listOf(c.text("email_domain").takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(), c.text("school")).filter { it.isNotBlank() }.joinToString(" · "),
                leading = { Initials(c.text("short_name").ifBlank { c.text("name") }, 36) },
                status = (if (c.text("id") == myClub(workspace)?.text("id")) "Your club · " else "") + c.text("theme").replaceFirstChar { it.uppercase() } to Tone.NEUTRAL)
        }
        DeskLabel("Create a club")
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it.take(160) }, label = { Text("Club name") }, placeholder = { Text("e.g. UP Archery Club") }, singleLine = true,
                isError = attempted && "name" in errors, supportingText = { if (attempted) errors["name"]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(short, { short = it.take(40) }, label = { Text("Short name (optional)") }, placeholder = { Text("e.g. UPAC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(school, { school = it.take(160) }, label = { Text("School") }, placeholder = { Text("e.g. University of the Philippines") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(domain, { domain = it.trim().take(80) }, label = { Text("School email domain") }, placeholder = { Text("e.g. up.edu.ph") }, singleLine = true,
                prefix = { Text("@") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = attempted && "domain" in errors,
                supportingText = { Text(if (attempted) errors["domain"] ?: "" else "Only accounts with this email join the club. Nobody else can.") },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it.trim().take(254) }, label = { Text("First administrator's email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                isError = attempted && "email" in errors,
                supportingText = { Text(if (attempted) errors["email"] ?: "" else "They need an account in the app already, not part of another club.") },
                modifier = Modifier.fillMaxWidth())
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("green" to "Green", "red" to "Red", "blue" to "Blue"), theme) { theme = it }
            Text("The club starts with blank rules. Its administrator sets the requirements, fees and categories in Club setup, and assigns its own officers.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CardButton("Create club", Modifier.fillMaxWidth(), primary = true, enabled = !busy) {
                attempted = true
                if (errors.isEmpty()) vm?.save("create_club", buildJsonObject { put("p_data", buildJsonObject {
                    put("name", name.trim()); put("short_name", short.trim()); put("school", school.trim()); put("admin_email", email.trim()); put("theme", theme); put("email_domain", domain.trim().trimStart('@').lowercase())
                }) }) { name = ""; short = ""; school = ""; email = ""; domain = ""; theme = "green"; attempted = false }
            }
        } }
    }
}

/** Membership officer or Administrator: single-use codes for people without a school email (migration 053). */
@Composable
fun JoinCodesScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, vm: SystemViewModel?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var made by remember { mutableStateOf<Pair<String, String>?>(null) }
    var cancelling by remember { mutableStateOf<JsonObject?>(null) }
    val club = clubName(workspace)
    val errors = buildMap {
        if (name.trim().length < 2) put("name", "Enter the person's name.")
        if (!email.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) put("email", "Enter the email they will sign up with.")
        if (reason.trim().length < 2) put("reason", "Say why, like \"Alumni, batch 2019\".")
    }
    val codes = workspace.records("join_codes")
    val now = java.time.Instant.now()
    ScreenShell("Join codes", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        NoticeCard("For people without a school email", "Alumni, coaches or faculty who only have a personal email can join $club with a code you make for their email. They still apply and are reviewed as usual.", Tone.INFO)
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("NEW CODE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it.take(160) }, label = { Text("Their name") }, singleLine = true,
                isError = attempted && "name" in errors, supportingText = { if (attempted) errors["name"]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it.trim().take(254) }, label = { Text("Their email") }, placeholder = { Text("e.g. ana.cruz@gmail.com") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                isError = attempted && "email" in errors, supportingText = { Text(if (attempted) errors["email"] ?: "" else "The code only works with this email.") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(reason, { reason = it.take(300) }, label = { Text("Reason") }, placeholder = { Text("e.g. Alumni, batch 2019") }, singleLine = true,
                isError = attempted && "reason" in errors, supportingText = { if (attempted) errors["reason"]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
            CardButton("Make code", Modifier.fillMaxWidth(), primary = true, enabled = !busy) {
                attempted = true
                if (errors.isEmpty()) vm?.saveForResult("create_join_code", buildJsonObject { put("p_data", buildJsonObject {
                    put("full_name", name.trim()); put("email", email.trim().lowercase()); put("reason", reason.trim()) }) }) { r ->
                    (r as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull?.let { made = it to email.trim().lowercase() }
                    name = ""; email = ""; reason = ""; attempted = false
                }
            }
        } }
        DeskLabel("Codes · ${codes.size}")
        if (codes.isEmpty()) EmptyState("No codes yet", "Codes you make appear here, with who used them.")
        codes.forEach { j ->
            val status = when {
                j.text("used_by").isNotBlank() -> "Used" to Tone.POSITIVE
                j.text("revoked_at").isNotBlank() -> "Cancelled" to Tone.NEUTRAL
                (j.instant("expires_at") ?: now).isBefore(now) -> "Expired" to Tone.NEUTRAL
                else -> "Waiting" to Tone.WARNING
            }
            DeskRow(j.text("full_name"), "${j.text("email")} · ${j.text("code")}", leading = { Initials(j.text("full_name"), 36) }, status = status) {
                Text(j.text("reason"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status.first == "Waiting") CardButton("Cancel code", Modifier.fillMaxWidth(), danger = true, enabled = !busy) { cancelling = j }
            }
        }
    }
    made?.let { (code, forEmail) ->
        val message = "You're invited to join $club in the Archery Club app.\n1. Install the app and tap Create an account.\n" +
            "2. Sign up with $forEmail.\n3. Enter this join code: $code\nThen send your membership application."
        ConfirmDialog("Code for $forEmail", "Send them this code. It works once, only with that email, for 14 days.", "Share",
            extra = { Text(code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(code)) }) { Text("Copy code") } },
            onConfirm = {
                made = null
                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, message) }, "Send the join code"))
            }, onDismiss = { made = null })
    }
    cancelling?.let { j ->
        ConfirmDialog("Cancel ${j.text("code")}?", "${j.text("full_name")} won't be able to use it.", "Cancel code", destructive = true,
            onConfirm = { cancelling = null; vm?.save("revoke_join_code", buildJsonObject { put("p_data", buildJsonObject { put("id", j.text("id")) }) }) },
            onDismiss = { cancelling = null })
    }
}


/** Administration → Club features (migration 055): switch whole features off for everyone in this club. */
@Composable
fun ClubFeaturesScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, vm: SystemViewModel?) {
    val saved = disabledFeatures(workspace)
    var picked by rememberSaveable { mutableStateOf(saved.joinToString(",")) }
    val off = picked.split(',').filter { it.isNotBlank() }.toSet()
    var confirming by remember { mutableStateOf(false) }
    val turningOff = CLUB_FEATURES.filter { it.key in off && it.key !in saved }
    fun save() = vm?.save("set_club_features", buildJsonObject { put("p_disabled", JsonArray(off.sorted().map(::JsonPrimitive))) }) { confirming = false; onBack() }
    ScreenShell("Club features", onBack, snackbar, stickyAction = {
        Button(onClick = { if (turningOff.isEmpty()) save() else confirming = true }, enabled = !busy && vm != null && off != saved,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ph.capstone.archeryclub.ui.Club.forestDeep)) {
            Text("Save changes", fontWeight = FontWeight.ExtraBold)
        }
    }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Turn off what ${clubName(workspace)} doesn't use. It disappears for every member and officer here, and the server refuses it too. Other clubs aren't affected, and records are kept for when you turn it back on.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RowGroup {
            CLUB_FEATURES.forEachIndexed { i, f ->
                val on = f.key !in off
                val toggle = { picked = (if (on) off + f.key else off - f.key).joinToString(",") }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(f.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                        Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(on, { toggle() }, enabled = !busy, colors = SwitchDefaults.colors(checkedTrackColor = ph.capstone.archeryclub.ui.Club.forest))
                }
                if (i < CLUB_FEATURES.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        }
        InfoCard("Always on", "Membership and applications, payments, and Administration can't be turned off: without them nobody could join, pay or manage the club.")
    }
    if (confirming) ConfirmDialog("Turn off ${turningOff.joinToString(" and ") { it.title.lowercase() }}?",
        "Members and officers of ${clubName(workspace)} won't see it any more, and anything in progress there pauses until you turn it back on.",
        "Turn off", onConfirm = { save() }, onDismiss = { confirming = false }, destructive = true)
}
