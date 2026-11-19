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
    // Deadlines count even before the election officer changes the stage: past them, candidacy and voting are closed.
    val stageOpen = electionStageOpen(e, Instant.now())
    val positions = strings(e, "positions")
    val candidates = workspace.records("candidates").filter { it.text("election_id") == eid }
    val confirmed = candidates.filter { it.text("confirmed") == "true" }
    val eligible = workspace.records("eligible_voters").filter { it.text("election_id") == eid }
    val voted = workspace.records("voter_participation").filter { it.text("election_id") == eid }
    val iVoted = voted.any { it.text("user_id") == user }
    val canVote = isMember && eligible.any { it.text("user_id") == user } && !iVoted && status == "voting" && stageOpen && e.text("method") == "digital"
    val choices = remember(eid) { mutableStateMapOf<String, String>() }
    var confirming by remember { mutableStateOf(false) }
    var summary by remember(eid) { mutableStateOf<JsonObject?>(null) }
    val resultsVisible = officer || status in setOf("confirmed", "requires_revote", "archived")
    LaunchedEffect(eid, status) { if (vm != null && resultsVisible && status in setOf("closed", "confirmed", "requires_revote", "archived")) vm.rpc("election_summary", buildJsonObject { put("p_id", eid) }) { summary = it as? JsonObject } }
    val when_ = when (status) {
        "announced" -> if (stageOpen) "Candidacy closes ${fmtDateTime(e.instant("candidacy_closes"))}" else "Candidacy closed ${fmtDateTime(e.instant("candidacy_closes"))} · voting opens ${fmtDay(e.instant("voting_opens"))}"
        "voting" -> if (stageOpen) "Voting closes ${fmtDateTime(e.instant("voting_closes"))}" else "Voting closed ${fmtDateTime(e.instant("voting_closes"))} · results coming"
        "closed" -> "Voting closed · results due ${fmtDay(e.instant("result_due"))}"
        "confirmed" -> "Results confirmed"; "draft" -> "Not announced yet · only officers see this"
        else -> electionStageLabel(status)
    }
    HeroScaffold(e.text("title"), onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill("ELECTION", Color.White.copy(alpha = .16f), Color.White); Pill(electionStageLabel(e, Instant.now()).uppercase(), if (stageOpen || status !in setOf("announced", "voting")) Club.lime else Club.amberSoft, if (stageOpen || status !in setOf("announced", "voting")) Club.forestDeep else Club.amberInk) },
        heroExtra = {
            Text(when_ + if (status == "voting" && eligible.isNotEmpty()) " · ${voted.size} of ${eligible.size} voted" else "", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium)
        },
        sticky = if (canVote) ({ StickyButton("Review and cast ballot", !busy && positions.isNotEmpty() && positions.all { !choices[it].isNullOrBlank() }, Modifier.weight(1f)) { confirming = true } }) else null) {
        StepDots(electionTimeline(status))
        if (officer && e.text("rules_validated") != "true") NoticeCard("Rules not validated", "Confirm the election rules before announcing or opening the vote.", Tone.WARNING)
        when {
            iVoted -> NoticeCard("You voted", "Thank you. The club records only that you voted, never who you chose.", Tone.POSITIVE)
            status == "voting" && e.text("method") != "digital" -> NoticeCard("Voting isn't in the app this time", if (e.text("method") == "paper") "This election uses paper ballots. Ask the election officer where to vote." else "This election is run outside the app. Results are posted here once confirmed.", Tone.INFO)
            status == "voting" && !stageOpen -> NoticeCard("Voting has closed", "The ballot closed ${fmtDateTime(e.instant("voting_closes"))}. Results appear here once the election officer confirms them.", Tone.INFO)
            status == "announced" && !stageOpen -> NoticeCard("Candidacy has closed", "No more candidates or nominations. Voting opens ${fmtDateTime(e.instant("voting_opens"))}.", Tone.INFO)
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
            // Greyed out (not hidden) once candidacy closes, so members see the stage is over rather than missing.
            if (status == "announced" && isMember && candidates.none { it.text("user_id") == user }) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardButton("Run for a position", Modifier.weight(1f), primary = true, enabled = !busy && stageOpen) { onStart("file_candidacy", e) }
                CardButton("Nominate someone", Modifier.weight(1f), enabled = !busy && stageOpen) { onStart("nominate_candidate", e) }
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
            others.take(5).forEach { o -> DeskRow(o.text("title"), electionStageLabel(o, Instant.now()), leading = { IconChip("vote", Tone.NEUTRAL) }, onClick = { onOpen(o.text("id")) }) }
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

/** Officer terms run one academic year (Bylaws 4.5): start now, end June 30 of the academic year, in the form's local format. */
private fun termSeed(): JsonObject {
    val today = LocalDate.now()
    val endYear = if (today.monthValue > 6) today.year + 1 else today.year
    return buildJsonObject {
        put("starts_at", java.time.LocalDateTime.now().withSecond(0).withNano(0).toString())
        put("ends_at", "$endYear-06-30T23:59")
        put("reason", "Appointed per the club bylaws (Art. 4)")
    }
}

@Composable
fun AdminScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, permissions: Set<String>, onBack: () -> Unit, onStart: (String, JsonObject) -> Unit, onRoles: () -> Unit = {}, onSetup: () -> Unit = {}, onInventory: () -> Unit = {}, onAssign: (String) -> Unit = {},
                onSave: (String, JsonObject, () -> Unit) -> Unit = { _, _, _ -> }, onRecords: () -> Unit) {
    val settings = workspace.records("settings").firstOrNull()
    val officers = officerViews(workspace, Instant.now())
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<RoleLine?>(null) }
    val log = workspace.records("audit_log").sortedByDescending { it.text("created_at") }.take(6)
    ph.capstone.archeryclub.ui.ScreenShell("Administration", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Club setup has its own screen; here, just how far along it is and what is still missing.
        val lines = setupChecklist(settings)
        val done = lines.count { it.done }
        RaisedCard(onClick = onSetup) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CLUB SETUP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                StatusPill("$done of ${lines.size} done", if (done == lines.size) Tone.POSITIVE else Tone.WARNING)
            }
            lines.filter { !it.done }.take(3).forEach { l ->
                Text("• ${l.title}: ${l.detail}", style = MaterialTheme.typography.bodySmall, color = Club.amberInk, fontWeight = FontWeight.Bold)
            }
            if (done == lines.size) Text("Everything is set. Edit any part from Club setup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PillButton("Open club setup", onSetup)
        } }
        editing?.let { r -> EditRoleDialog(r, officers.find { o -> o.roles.contains(r) }?.name.orEmpty(), busy, onSave) { editing = null } }
        DeskLabel("Officers and terms", "+ Assign roles") { onAssign("") }
        if (officers.isEmpty()) EmptyState("No officer roles", "Assign time-limited roles so officers can use their desks.")
        // One row per person; tap to see each role's term and renew or retire it.
        officers.forEach { o ->
            val open = expanded == o.userId
            val (label, tone) = when (o.worst) {
                TermState.EXPIRED -> "Expired ${fmtDay(o.nearestEnd)}" to Tone.DANGER
                TermState.ENDING -> "Ends ${fmtDay(o.nearestEnd)}" to Tone.WARNING
                TermState.ACTIVE -> "${o.roleNames.size} role${if (o.roleNames.size == 1) "" else "s"}" to Tone.NEUTRAL
            }
            DeskRow(o.name, o.roleNames.joinToString(" · ") + if (o.duplicates > 0) " · ${o.duplicates} duplicate${if (o.duplicates == 1) "" else "s"}" else "",
                leading = { Initials(o.name, 40) }, status = label to tone, onClick = { expanded = if (open) null else o.userId }) {
                if (open) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    o.roles.forEach { r ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.roleName + if (r.duplicate) " (duplicate)" else "", style = MaterialTheme.typography.titleSmall,
                                    color = if (r.duplicate) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                Text(listOfNotNull(r.title.takeIf { it.isNotBlank() }?.let { "Title: $it" },
                                    when (r.state) { TermState.EXPIRED -> "expired ${fmtDay(r.ends)}"; TermState.ENDING -> "ends ${fmtDay(r.ends)}"; TermState.ACTIVE -> "until ${fmtDay(r.ends)}" })
                                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                    color = when (r.state) { TermState.EXPIRED -> Club.roseInk; TermState.ENDING -> Club.amberInk; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                            }
                            // Edit changes this appointment's title or end date; a different role is added with "+ Add role" below.
                            if (!r.duplicate) TextButton(onClick = { editing = r }, enabled = !busy) { Text("Edit", fontWeight = FontWeight.ExtraBold) }
                            TextButton(onClick = { onStart("retire_role", r.assignment) }, enabled = !busy) { Text("Retire", color = Club.roseInk, fontWeight = FontWeight.ExtraBold) }
                            if (r.state != TermState.ACTIVE && !r.duplicate) TextButton(onClick = {
                                onStart("assign_role", JsonObject(termSeed() + listOf("user_id", "role_id", "position").associateWith { k -> JsonPrimitive(r.assignment.text(k)) }))
                            }, enabled = !busy) { Text("Renew", fontWeight = FontWeight.ExtraBold) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    CardButton("+ Add role", Modifier.fillMaxWidth(), enabled = !busy) { onAssign(o.userId) }
                }
            }
        }
        DeskLabel("Accounts and history")
        RowGroup {
            GroupRow("users", "Accounts", "Enable or disable sign-in", onClick = onRecords)
            GroupRow("settings", "Roles & access", "What each role opens", onClick = onRoles)
            GroupRow("box", "Inventory set-up", "Categories, their fields and places", onClick = onInventory)
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



/** Edit one appointment in place: its position title and end date, with a reason for the record (migration 040). */
@Composable
private fun EditRoleDialog(r: RoleLine, person: String, busy: Boolean, onSave: (String, JsonObject, () -> Unit) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(r.assignment.text("position").ifBlank { r.roleName }) }
    var ends by remember { mutableStateOf(r.ends ?: Instant.now()) }
    var reason by rememberSaveable { mutableStateOf("") }
    val starts = r.assignment.instant("starts_at")
    val problem = when {
        title.isBlank() -> "Give the position a title."
        starts != null && !ends.isAfter(starts) -> "The term must end after it starts (${fmtDay(starts)})."
        reason.isBlank() -> "Say why it changed, for the record."
        else -> null
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit ${r.roleName}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(person, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title, { title = it.take(160) }, label = { Text("Position title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateTimeField("Term ends", ends, { ends = it })
            OutlinedTextField(reason, { reason = it.take(300) }, label = { Text("Reason") }, placeholder = { Text("e.g. Term extended by the board") }, modifier = Modifier.fillMaxWidth())
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } },
        confirmButton = { TextButton(enabled = problem == null && !busy, onClick = {
            onSave("update_role_term", buildJsonObject { put("p_data", buildJsonObject {
                put("id", r.assignment.text("id")); put("position", title.trim()); put("ends_at", ends.toString()); put("reason", reason.trim())
            }) }) { onDismiss() }
        }) { Text("Save", fontWeight = FontWeight.ExtraBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Give one person one or more roles at once. Each ticked role is saved as its own assignment (same term and reason),
 *  so a role can still be renewed or retired on its own later. */
@Composable
fun AssignRolesScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit, initialPerson: String = "") {
    val seed = remember { termSeed() }
    var person by rememberSaveable { mutableStateOf(initialPerson) }
    var ticked by remember { mutableStateOf(listOf<String>()) }
    var titles by remember { mutableStateOf(mapOf<String, String>()) }
    var starts by rememberSaveable { mutableStateOf(instantOf(seed.text("starts_at")) ?: Instant.now()) }
    var ends by rememberSaveable { mutableStateOf(instantOf(seed.text("ends_at")) ?: Instant.now().plusSeconds(180L * 86400)) }
    var reason by rememberSaveable { mutableStateOf(seed.text("reason")) }
    var result by remember { mutableStateOf<String?>(null) }
    val standings = memberStandings(workspace)
    val people = workspace.records("profiles").map { it.text("id") to recordTitle(it, workspace) + (standings?.let { s -> " · " + standingLabel(s[it.text("id")]) } ?: "") }
        .sortedBy { it.second.lowercase() }
    val choices = if (person.isBlank()) emptyList() else roleChoices(workspace, person, Instant.now())
    val name = people.find { it.first == person }?.second?.substringBefore(" · ").orEmpty()
    val problem = when {
        person.isBlank() -> "Pick a person."
        ticked.isEmpty() -> "Tick at least one role."
        !ends.isAfter(starts) -> "The term must end after it starts."
        reason.isBlank() -> "Give a reason for the record."
        ticked.any { titles[it]?.isBlank() == true } -> "Give each role a position title."
        else -> null
    }
    ph.capstone.archeryclub.ui.ScreenShell("Assign roles", onBack, snackbar, stickyAction = {
        if (problem != null && person.isNotBlank()) Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StickyButton(if (ticked.size > 1) "Assign ${ticked.size} roles" else "Assign role", !busy && problem == null && vm != null, Modifier.fillMaxWidth()) {
            val items = ticked.mapNotNull { id -> choices.find { it.id == id } }.map { c ->
                c.name to buildJsonObject {
                    put("user_id", person); put("role_id", c.id); put("position", (titles[c.id] ?: c.name).trim().ifBlank { c.name })
                    put("starts_at", starts.toString()); put("ends_at", ends.toString()); put("reason", reason.trim())
                }
            }
            vm?.commandEach("assign_role", items) { saved, failed ->
                result = assignedSummary(name, saved, failed)
                ticked = failed.mapNotNull { f -> choices.find { it.name == f.first }?.id }
            }
        }
    }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        result?.let { NoticeCard(if (it.contains("wasn't assigned")) "Some roles weren't assigned" else "Saved", it, if (it.contains("wasn't assigned")) Tone.WARNING else Tone.POSITIVE) }
        DeskLabel("Person")
        SelectField("Person", person, people) { person = it; ticked = emptyList(); titles = emptyMap(); result = null }
        if (standings != null && person.isNotBlank() && standings[person] != "Active")
            Text("Not an Active member, so only the Administrator role can be given.", style = MaterialTheme.typography.bodySmall, color = Club.amberInk)
        if (person.isNotBlank()) {
            DeskLabel("Roles")
            RowGroup { choices.forEachIndexed { i, c ->
                val on = c.id in ticked
                Column(Modifier.fillMaxWidth().selectable(selected = on, enabled = c.enabled && !busy, role = Role.Checkbox) {
                    ticked = if (on) ticked - c.id else ticked + c.id
                }.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Checkbox(checked = on, onCheckedChange = null, enabled = c.enabled)
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleSmall,
                                color = if (c.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (c.note.isNotBlank()) Text(c.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // The position title starts as the role's name; edit it for titles like "Vice President for External Affairs".
                    if (on) OutlinedTextField(titles[c.id] ?: c.name, { titles = titles + (c.id to it.take(160)) }, label = { Text("Position title") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (i < choices.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            } }
            DeskLabel("Term (all ticked roles)")
            DateTimeField("Starts", starts, { starts = it })
            DateTimeField("Ends", ends, { ends = it })
            OutlinedTextField(reason, { reason = it.take(500) }, label = { Text("Reason for the record") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Text("Each role is saved on its own, so you can renew or retire one later without touching the others.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
