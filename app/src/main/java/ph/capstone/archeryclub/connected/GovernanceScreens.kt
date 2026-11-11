package ph.capstone.archeryclub.connected

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ClubIcon
import java.time.Instant
import java.time.LocalDate

/* Running the club: elections (M9), reports and goals (M13), administration (M14) and interclub events (M15). */

private fun strings(row: JsonObject?, key: String): List<String> = (row?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

// ---------------------------------------------------------------- Elections

@Composable
private fun StepDots(steps: List<Milestone>) {
    RaisedCard {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            steps.forEach { s ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(when { s.done -> Club.forest; s.current -> Club.lime; else -> Color.Transparent })
                        .border(if (s.current) 3.dp else 2.dp, if (s.current) Club.forestDeep else if (s.done) Club.forest else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center) { if (s.done) ClubIcon("check", Modifier.size(14.dp), Color.White) }
                    Text(s.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                        fontWeight = if (s.current) FontWeight.ExtraBold else FontWeight.SemiBold, color = if (s.current) Club.forestDeep else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ElectionScreen(
    workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, isMember: Boolean, officer: Boolean, vm: SystemViewModel?,
    onBack: () -> Unit, onOpen: (String) -> Unit, onCommand: (String, JsonObject, () -> Unit) -> Unit, onStart: (String, JsonObject) -> Unit, onRecords: () -> Unit,
) {
    val elections = workspace.records("elections")
    val e = if (id.isNotBlank()) elections.find { it.text("id") == id } else featuredElection(elections, officer)
    val user = workspace.text("user_id")
    if (e == null) {
        HeroScaffold("Elections", onBack, snackbar, overlap = 44.dp, busy = busy, eyebrow = { Pill("ELECTIONS", Color.White.copy(alpha = .16f), Color.White) },
            heroExtra = { Text("Officer elections run here: candidacy, an anonymous ballot and official results.", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium) }) {
            EmptyState("No election right now", "When the election officer announces one, you'll see the timeline and candidates here.",
                if (officer) "Create an election" else null) { onStart("create_election", buildJsonObject { put("method", "digital"); put("kind", "annual") }) }
            if (officer) TextButton(onClick = onRecords, modifier = Modifier.fillMaxWidth()) { Text("Terms, residency and leadership records") }
        }
        return
    }
    val eid = e.text("id"); val status = e.text("status")
    val positions = strings(e, "positions")
    val candidates = workspace.records("candidates").filter { it.text("election_id") == eid }
    val confirmed = candidates.filter { it.text("confirmed") == "true" }
    val eligible = workspace.records("eligible_voters").filter { it.text("election_id") == eid }
    val voted = workspace.records("voter_participation").filter { it.text("election_id") == eid }
    val iVoted = voted.any { it.text("user_id") == user }
    val canVote = isMember && eligible.any { it.text("user_id") == user } && !iVoted && status == "voting" && e.text("method") == "digital"
    val choices = remember(eid) { mutableStateMapOf<String, String>() }
    var confirming by remember { mutableStateOf(false) }
    var summary by remember(eid) { mutableStateOf<JsonObject?>(null) }
    val resultsVisible = officer || status in setOf("confirmed", "requires_revote", "archived")
    LaunchedEffect(eid, status) { if (vm != null && resultsVisible && status in setOf("closed", "confirmed", "requires_revote", "archived")) vm.rpc("election_summary", buildJsonObject { put("p_id", eid) }) { summary = it as? JsonObject } }
    val when_ = when (status) {
        "announced" -> "Candidacy closes ${fmtDateTime(e.instant("candidacy_closes"))}"
        "voting" -> "Voting closes ${fmtDateTime(e.instant("voting_closes"))}"
        "closed" -> "Voting closed · results due ${fmtDay(e.instant("result_due"))}"
        "confirmed" -> "Results confirmed"; "draft" -> "Not announced yet · only officers see this"
        else -> electionStageLabel(status)
    }
    HeroScaffold(e.text("title"), onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill("ELECTION", Color.White.copy(alpha = .16f), Color.White); Pill(electionStageLabel(status).uppercase(), Club.lime, Club.forestDeep) },
        heroExtra = {
            Text(when_ + if (status == "voting" && eligible.isNotEmpty()) " · ${voted.size} of ${eligible.size} voted" else "", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium)
        },
        sticky = if (canVote) ({ StickyButton("Review and cast ballot", !busy && positions.isNotEmpty() && positions.all { !choices[it].isNullOrBlank() }, Modifier.weight(1f)) { confirming = true } }) else null) {
        StepDots(electionTimeline(status))
        if (officer && e.text("rules_validated") != "true") NoticeCard("Rules not validated", "Confirm the election rules before announcing or opening the vote.", Tone.WARNING)
        when {
            iVoted -> NoticeCard("You voted", "Thank you. The club records only that you voted, never who you chose.", Tone.POSITIVE)
            status == "voting" && e.text("method") != "digital" -> NoticeCard("Voting isn't in the app this time", if (e.text("method") == "paper") "This election uses paper ballots. Ask the election officer where to vote." else "This election is run outside the app. Results are posted here once confirmed.", Tone.INFO)
            status == "voting" && isMember && !canVote -> NoticeCard("You're not on the voter list", "Ask the election officer to confirm your eligibility.", Tone.WARNING)
        }
        if (canVote) positions.forEach { position ->
            DeskLabel("$position · pick one")
            val options = confirmed.filter { it.text("position") == position }
            if (options.isEmpty()) Text("No confirmed candidates.", style = MaterialTheme.typography.bodySmall)
            options.forEach { c ->
                val on = choices[position] == c.text("id")
                RaisedCard(radius = 18.dp) {
                    Row(Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) { choices[position] = c.text("id") }.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(on, onClick = null)
                        Column(Modifier.weight(1f)) {
                            Text(memberName(workspace, c.text("user_id")), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                            if (c.text("statement").isNotBlank()) Text("“${c.text("statement")}”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (canVote) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ClubIcon("lock", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Your ballot is anonymous. You can vote once and can't change it after.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!canVote && status in setOf("announced", "voting", "draft")) {
            DeskLabel("Candidates")
            positions.forEach { position ->
                val list = candidates.filter { it.text("position") == position && (it.text("confirmed") == "true" || officer || it.text("user_id") == user) }
                RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(position, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (list.isEmpty()) Text("No candidates yet.", style = MaterialTheme.typography.bodyMedium)
                    list.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Initials(memberName(workspace, c.text("user_id")), 36)
                            Column(Modifier.weight(1f)) {
                                Text(memberName(workspace, c.text("user_id")), style = MaterialTheme.typography.titleSmall)
                                if (c.text("statement").isNotBlank()) Text(c.text("statement"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            }
                            if (c.text("confirmed") == "true") StatusPill("Confirmed", Tone.POSITIVE)
                            else if (officer) CardButton("Review", enabled = !busy) { onStart("confirm_candidate", c) }
                            else StatusPill("Being checked", Tone.WARNING)
                        }
                    }
                } }
            }
            if (status == "announced" && isMember && candidates.none { it.text("user_id") == user }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton("Run for a position", Modifier.weight(1f), primary = true, enabled = !busy) { onStart("file_candidacy", e) }
                CardButton("Nominate someone", Modifier.weight(1f), enabled = !busy) { onStart("nominate_candidate", e) }
            }
        }
        summary?.let { s ->
            DeskLabel(if (status == "closed") "Count (officers only until confirmed)" else "Results")
            RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Turnout ${s.text("participation").ifBlank { "0" }} of ${s.text("eligible")}${s.text("rate").takeIf { it.isNotBlank() && it != "null" }?.let { " · $it%" }.orEmpty()} (needs ${s.text("threshold")}%)",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                val tallies = s.records("tallies")
                positions.forEach { position ->
                    val rows = tallies.filter { it.text("position") == position }.sortedByDescending { it.text("votes").toIntOrNull() ?: 0 }
                    if (rows.isNotEmpty()) {
                        Text(position, style = MaterialTheme.typography.titleSmall)
                        val top = rows.maxOf { it.text("votes").toIntOrNull() ?: 0 }.coerceAtLeast(1)
                        rows.forEach { t ->
                            val votes = t.text("votes").toIntOrNull() ?: 0
                            Row(verticalAlignment = Alignment.CenterVertically) { Text(memberName(workspace, t.text("user_id")), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text("$votes", fontWeight = FontWeight.ExtraBold) }
                            GoalBar(votes / top.toFloat(), false)
                        }
                    }
                }
                if (tallies.isEmpty()) Text("Vote counts appear after voting closes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        if (officer) {
            DeskLabel("Election officer")
            RowGroup {
                if (status == "draft") GroupRow("settings", "Edit details and dates") { onStart("update_election", e) }
                GroupRow("check", "Change stage", electionStageLabel(status)) { onStart("set_election_status", e) }
                if (status in setOf("draft", "announced")) GroupRow("users", "Add an eligible voter", "${eligible.size} on the list") { onStart("confirm_voter", e) }
                if (status == "closed" && e.text("method") != "digital") GroupRow("doc", "Record paper or external results") { onStart("record_external_results", e) }
                if (status == "closed") GroupRow("vote", "Confirm official results") { onStart("confirm_results", e) }
                GroupRow("modules", "Terms, residency and leadership records", divider = false, onClick = onRecords)
            }
        }
        val others = elections.filter { it.text("id") != eid && (officer || it.text("status") != "draft") }.sortedByDescending { it.text("voting_opens") }
        if (others.isNotEmpty()) {
            DeskLabel("Other elections")
            others.take(5).forEach { o -> DeskRow(o.text("title"), electionStageLabel(o.text("status")), leading = { IconChip("vote", Tone.NEUTRAL) }, onClick = { onOpen(o.text("id")) }) }
        }
    }
    if (confirming) ConfirmDialog("Cast your ballot?", positions.joinToString("\n") { p -> "$p: ${confirmed.find { it.text("id") == choices[p] }?.let { memberName(workspace, it.text("user_id")) } ?: "—"}" } +
        "\n\nYou can vote once and can't change it after.", "Cast ballot", onConfirm = {
        confirming = false
        onCommand("cast_ballot", buildJsonObject { put("election_id", eid); put("choices", buildJsonObject { choices.forEach { (k, v) -> put(k, v) } }) }) { choices.clear() }
    }, onDismiss = { confirming = false })
}

// ---------------------------------------------------------------- Reports and goals

@Composable
fun ReportsScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit, onStart: (String, JsonObject) -> Unit, onRecords: () -> Unit) {
    var period by rememberSaveable { mutableStateOf("month") }
    val today = LocalDate.now()
    val from = when (period) { "30" -> today.minusDays(30); "year" -> today.withDayOfYear(1); else -> today.withDayOfMonth(1) }
    var report by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(period) { report = null; vm?.rpc("club_report", buildJsonObject { put("p_from", from.toString()); put("p_to", today.toString()) }) { report = it as? JsonObject } }
    val context = LocalContext.current
    var csv by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching { requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(csv) } }.onFailure { vm?.error("The CSV report could not be saved.") }
    }
    ph.capstone.archeryclub.ui.ScreenShell("Reports & goals", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ChoiceChips(listOf("month" to "This month", "30" to "Last 30 days", "year" to "This year"), period) { period = it }
        val r = report
        if (r == null) { RaisedCard { Text("Loading the report…", Modifier.padding(16.dp)) }; return@ScreenShell }
        val goals = goalViews(r)
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GOALS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { onStart("save_goal", buildJsonObject { put("starts_on", today.withDayOfMonth(1).toString()); put("ends_on", today.withDayOfMonth(today.lengthOfMonth()).toString()) }) }) { Text("+ New goal", fontWeight = FontWeight.ExtraBold) }
            }
            if (goals.isEmpty()) Text("No goals for this period. Set one, like 60 active members by the end of term.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            goals.forEach { g ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row { Text(g.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${g.actual?.let(g::format) ?: "—"} / ${g.format(g.target)}", style = MaterialTheme.typography.titleSmall) }
                    GoalBar(g.progress, behind = g.actual != null && !g.onTrack)
                    Text(when { g.actual == null -> "No data yet for this measure."; g.onTrack -> "Reached"; else -> "${g.format(g.target - g.actual)} to go" } +
                        (g.ends?.let { " · ends ${fmtDate(it)}" }.orEmpty()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
        DeskLabel("Club at a glance")
        RowGroup {
            (r["membership"] as? JsonObject)?.let { m -> GroupRow("profile", "Membership", "${m.text("active")} active · ${m.text("applications")} applied", onClick = null) }
            r.records("activities").takeIf { it.isNotEmpty() }?.let { a ->
                val present = a.sumOf { it.text("present").toIntOrNull() ?: 0 }; val reg = a.sumOf { it.text("registrations").toIntOrNull() ?: 0 }; val exc = a.sumOf { it.text("excused").toIntOrNull() ?: 0 }
                GroupRow("calendar", "Attendance", "${a.size} sessions · ${attendanceRate(present, reg, exc)?.let { "$it%" } ?: "—"}", onClick = null)
            }
            (r["equipment"] as? JsonObject)?.let { q -> GroupRow("box", "Equipment", "${q.text("serviceable")}/${q.text("items")} ready · ${q.text("overdue")} overdue", onClick = null) }
            r.records("training").takeIf { it.isNotEmpty() }?.let { t -> GroupRow("target", "Training", "${t.sumOf { it.text("sessions").toIntOrNull() ?: 0 }} validated rounds", onClick = null) }
            (r["finance"] as? JsonObject)?.let { f -> GroupRow("money", "Finance", "In ${peso(f.amount("verified_payments"))} · out ${peso(f.amount("verified_expenses"))}", onClick = null) }
            GroupRow("doc", "Export CSV", "For the adviser or the org office", divider = false) { csv = clubReportCsv(r); export.launch("archery-report-$from-$today.csv") }
        }
        Text(r.text("limitation"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRecords, modifier = Modifier.fillMaxWidth()) { Text("Custom dates and all goal records") }
    }
}

// ---------------------------------------------------------------- Administration

@Composable
fun AdminScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, permissions: Set<String>, onBack: () -> Unit, onStart: (String, JsonObject) -> Unit, onRecords: () -> Unit) {
    val settings = workspace.records("settings").firstOrNull()
    val setup = clubSetup(settings, strings(settings, "application_requirements"), strings(settings, "membership_categories"))
    val terms = termViews(workspace, Instant.now())
    val log = workspace.records("audit_log").sortedByDescending { it.text("created_at") }.take(6)
    ph.capstone.archeryclub.ui.ScreenShell("Administration", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CLUB SETUP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                val done = setup.count { it.done }
                StatusPill("$done of ${setup.size} done", if (done == setup.size) Tone.POSITIVE else Tone.WARNING)
            }
            setup.forEach { item ->
                val action: (() -> Unit)? = when {
                    item.title == "Membership fee" -> if ("finance" in permissions) ({ onStart("set_membership_fee", buildJsonObject { put("amount", settings?.text("membership_fee").orEmpty()) }) }) else null
                    settings != null -> ({ onStart("configure_club", settings) })
                    else -> null
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(if (item.done) Club.forest else Color.Transparent)
                        .border(2.dp, if (item.done) Club.forest else Color(0xFFB26B00), CircleShape), contentAlignment = Alignment.Center) {
                        if (item.done) ClubIcon("check", Modifier.size(14.dp), Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else Club.amberInk)
                    }
                    if (action != null) TextButton(onClick = action, enabled = !busy) { Text(if (item.done) "Edit" else "Set up", fontWeight = FontWeight.ExtraBold) }
                }
            }
            if ("finance" !in permissions) Text("The treasurer sets the membership fee.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        DeskLabel("Officers and terms", "+ Assign role") { onStart("assign_role", buildJsonObject { put("starts_at", Instant.now().toString()) }) }
        if (terms.isEmpty()) EmptyState("No officer roles", "Assign time-limited roles so officers can use their desks.")
        terms.forEach { t ->
            val (label, tone) = when (t.state) {
                TermState.EXPIRED -> "Expired ${fmtDay(t.ends)}" to Tone.DANGER
                TermState.ENDING -> "Ends ${fmtDay(t.ends)}" to Tone.WARNING
                TermState.ACTIVE -> "Until ${fmtDay(t.ends)}" to Tone.NEUTRAL
            }
            DeskRow(t.name, t.role, leading = { Initials(t.name, 40) }, status = label to tone) {
                if (t.state != TermState.ACTIVE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardButton("Retire", Modifier.weight(1f), danger = true, enabled = !busy) { onStart("retire_role", t.assignment) }
                    CardButton("Renew term", Modifier.weight(1f), primary = true, enabled = !busy) {
                        onStart("assign_role", buildJsonObject { listOf("user_id", "role_id", "position").forEach { k -> put(k, t.assignment.text(k)) }; put("starts_at", Instant.now().toString()) })
                    }
                }
            }
        }
        DeskLabel("Accounts and history")
        RowGroup {
            GroupRow("users", "Accounts", "Enable or disable sign-in", onClick = onRecords)
            GroupRow("settings", "Roles and permissions", onClick = onRecords)
            GroupRow("doc", "Full activity log", divider = false, onClick = onRecords)
        }
        if (log.isNotEmpty()) RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("RECENT CHANGES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            log.forEach { l ->
                Row { Text(human(l.text("action")), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(l.text("actor").takeIf { it.isNotBlank() }?.let { memberName(workspace, it).substringBefore(' ') }.orEmpty() + " · " + fmtDay(l.instant("created_at")),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } }
    }
}

// ---------------------------------------------------------------- Interclub

@Composable
fun InterclubScreen(
    workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, coordinator: Boolean, interclubOfficer: Boolean,
    onBack: () -> Unit, onOpen: (String) -> Unit, onStart: (String, JsonObject) -> Unit, onRecords: () -> Unit,
) {
    val events = workspace.records("interclub_events")
    val e = if (id.isNotBlank()) events.find { it.text("event_id") == id } else featuredInterclub(events, Instant.now())
    if (e == null) {
        HeroScaffold("Interclub", onBack, snackbar, overlap = 44.dp, busy = busy, eyebrow = { Pill("INTERCLUB", Color.White.copy(alpha = .16f), Color.White) },
            heroExtra = { Text("Share an event with partner clubs, collect their delegates and post results.", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium) }) {
            EmptyState("No shared events", "Create the activity first, then share it with partner clubs.", if (interclubOfficer) "Share an event" else null) { onStart("share_interclub_event", buildJsonObject {}) }
            if (interclubOfficer) RowGroup { GroupRow("users", "Add a partner club", divider = false) { onStart("save_partner", buildJsonObject {}) } }
        }
        return
    }
    val eid = e.text("event_id")
    val partners = partnerViews(workspace, eid)
    val delegates = workspace.records("delegates").filter { it.text("event_id") == eid }
    val review = delegates.filter { it.text("status") in setOf("pending", "incomplete") }
    val concerns = workspace.records("interclub_concerns").filter { it.text("event_id") == eid && it.text("status") == "open" }
    val partnerName = workspace.records("partner_clubs").associate { it.text("id") to it.text("name") }
    HeroScaffold(e.text("shared_title"), onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill("INTERCLUB", Color.White.copy(alpha = .16f), Color.White); Pill(human(e.text("status")).uppercase(), Club.amberSoft, Club.amberInk) },
        heroExtra = { Text("${fmtDay(e.instant("shared_start"))} · ${e.text("shared_venue")} · sign-ups close ${fmtDay(e.instant("registration_closes"))}",
            color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium) }) {
        RaisedCard {
            Row(Modifier.padding(vertical = 14.dp)) {
                listOf("${partners.size}" to "Partner clubs", "${delegates.count { it.text("status") != "substituted" }}" to "Delegates", "${review.size}" to "To review").forEachIndexed { i, (v, l) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, style = MaterialTheme.typography.headlineSmall, color = if (i == 2 && review.isNotEmpty()) Club.amberInk else MaterialTheme.colorScheme.onSurface)
                        Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (partners.isNotEmpty()) {
            DeskLabel("Partner clubs")
            RowGroup { partners.forEachIndexed { i, p ->
                GroupRow("users", p.partner.text("name"), "${p.delegates} delegate${if (p.delegates == 1) "" else "s"}", divider = i < partners.lastIndex,
                    trailing = { StatusPill(p.label, p.tone) }, onClick = null)
            } }
        }
        concerns.forEach { c ->
            DeskRow("Concern from ${partnerName[c.text("partner_id")] ?: "a partner"}", c.text("concern"), leading = { IconChip("bell", Tone.WARNING) },
                trailing = if (coordinator) ({ CardButton("Reply", enabled = !busy) { onStart("resolve_concern", c) } }) else null)
        }
        if (review.isNotEmpty() && coordinator) {
            DeskLabel("Delegates to review")
            review.forEach { d ->
                DeskRow(d.text("full_name"), "${partnerName[d.text("partner_id")] ?: d.text("affiliation")} · ${d.text("category")}", leading = { Initials(d.text("full_name"), 40) },
                    status = human(d.text("status")) to delegateTone(d.text("status"))) {
                    CardButton("Review eligibility", Modifier.fillMaxWidth(), primary = true, enabled = !busy) { onStart("review_delegate", d) }
                }
            }
        }
        if (!coordinator) {
            DeskLabel("Your delegation")
            delegates.forEach { d -> DeskRow(d.text("full_name"), d.text("category"), leading = { Initials(d.text("full_name"), 40) }, status = human(d.text("status")) to delegateTone(d.text("status")),
                onClick = if (d.text("status") in setOf("pending", "incomplete")) ({ onStart("save_delegate", d) }) else null) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton("Register a delegate", Modifier.weight(1f), primary = true, enabled = !busy) { onStart("save_delegate", buildJsonObject { put("event_id", eid) }) }
                CardButton("Raise a concern", Modifier.weight(1f), enabled = !busy) { onStart("raise_concern", buildJsonObject { put("event_id", eid) }) }
            }
        }
        if (interclubOfficer) {
            DeskLabel("Coordinator")
            RowGroup {
                GroupRow("users", "Add a partner club") { onStart("save_partner", buildJsonObject {}) }
                GroupRow("calendar", "Share another event") { onStart("share_interclub_event", buildJsonObject {}) }
                GroupRow("modules", "Schedule, results and archive", divider = false, onClick = onRecords)
            }
        }
        val others = events.filter { it.text("event_id") != eid }.sortedByDescending { it.text("shared_start") }
        if (others.isNotEmpty()) {
            DeskLabel("Other events")
            others.take(5).forEach { o -> DeskRow(o.text("shared_title"), "${fmtDay(o.instant("shared_start"))} · ${human(o.text("status"))}", leading = { IconChip("calendar", Tone.NEUTRAL) }, onClick = { onOpen(o.text("event_id")) }) }
        }
    }
}
