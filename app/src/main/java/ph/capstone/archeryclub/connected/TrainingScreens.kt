package ph.capstone.archeryclub.connected

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.LocalDate

/* Training (M4 + M11): a member's scorecards, one scorecard, and the coach's validation desk. */

private fun versioned(row: JsonObject) = buildJsonObject {
    put("id", row.text("id")); row.text("version").toLongOrNull()?.let { put("version", it) }
}

/** Starting values for a new round, so a member usually only types the score. */
fun newRoundSeed(workspace: JsonObject, last: ScoreCard?): JsonObject = buildJsonObject {
    put("training_date", LocalDate.now().toString())
    val prev = last?.row
    put("distance", prev?.text("distance")?.ifBlank { null } ?: "18")
    put("target_face", prev?.text("target_face")?.ifBlank { null } ?: "80 cm")
    put("scoring_format", prev?.text("scoring_format")?.ifBlank { null } ?: "WA 360")
    put("maximum_score", prev?.text("maximum_score")?.ifBlank { null } ?: "360")
    val profile = workspace.records("profiles").find { it.text("id") == workspace.text("user_id") }
    put("category", prev?.text("category")?.ifBlank { null } ?: profile?.text("bow_type")?.ifBlank { null }?.let(::human) ?: "Recurve")
    prev?.text("level")?.takeIf { it.isNotBlank() }?.let { put("level", it) }
}

@Composable
fun TrainingScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, onOpen: (String) -> Unit, onLog: (JsonObject) -> Unit) {
    val cards = scoreCards(workspace, workspace.text("user_id"))
    val stats = trainingStats(cards, LocalDate.now())
    val trend = trendPercents(cards)
    var filter by rememberSaveable { mutableStateOf("all") }
    val shown = filterCards(cards, filter)
    HeroScaffold("My scorecards", onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill("TRAINING", Color.White.copy(alpha = .16f), Color.White) },
        heroExtra = {
            HeroStats(listOf((stats.best?.let { plainNumber(it.score) } ?: "—") to (stats.best?.row?.text("distance")?.let { "Best at ${plainNumber(it.toDoubleOrNull() ?: 0.0)} m" } ?: "Best round"),
                "${stats.roundsThisMonth}" to "Rounds this month",
                (stats.monthDelta?.let { (if (it >= 0) "+" else "") + it + "%" } ?: "—") to "vs last month"))
        },
        sticky = { StickyButton("Log a round", !busy, Modifier.weight(1f)) { onLog(newRoundSeed(workspace, cards.firstOrNull())) } }) {
        RaisedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (trend.size >= 2) "LAST ${trend.size} ROUNDS" else "YOUR TREND", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("% of max score", style = MaterialTheme.typography.labelMedium, color = Club.forest)
                }
                if (trend.size >= 2) {
                    TrendLine(trend)
                    Row { Text("${trend.first()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); Text("Latest · ${trend.last()}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
                } else Text("Log two rounds and your progress line appears here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val fix = cards.count { it.state == ScoreState.RETURNED }
        ChoiceChips(listOf("all" to "All", "validated" to "Validated", "coach" to "With coach", "personal" to "Personal") +
            (if (fix > 0) listOf("fix" to "Needs fix · $fix") else emptyList()), filter) { filter = it }
        if (cards.isEmpty()) EmptyState("No rounds yet", "Log a practice round to start your record. Send it to a coach to make it official.")
        else if (shown.isEmpty()) EmptyState("Nothing here", "No rounds match this filter.")
        shown.forEach { c ->
            DeskRow(c.scoreLabel, listOf(c.conditions, c.row.text("event_id").let { e -> workspace.records("events").find { it.text("id") == e }?.text("title").orEmpty() })
                .filter { it.isNotBlank() }.joinToString(" · "), leading = { DayChip(c.date, c.state.tone) }, status = c.state.label to c.state.tone, onClick = { onOpen(c.id) })
        }
        Text("Personal rounds are visible only to you until you send them to a coach.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScorecardScreen(
    workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, coach: Boolean, vm: SystemViewModel?,
    onBack: () -> Unit, onCommand: (String, JsonObject, () -> Unit) -> Unit, onStart: (String, JsonObject) -> Unit, onOpenDocument: (String) -> Unit,
) {
    val card = scoreCards(workspace).find { it.id == id }
    if (card == null) { ScreenShell("Scorecard", onBack, snackbar) { EmptyState("Scorecard not found", "It may have been removed. Go back and refresh.") }; return }
    val user = workspace.text("user_id"); val own = card.userId == user; val row = card.row
    val editable = own && card.state in setOf(ScoreState.PERSONAL, ScoreState.RETURNED)
    var confirmSend by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm?.upload(context, it, 11, id, "Supporting document") } }
    val sticky: (@Composable RowScope.() -> Unit)? = when {
        editable -> ({
            CardButton("Edit", Modifier.weight(1f), enabled = !busy) { onStart("save_personal_training", row) }
            StickyButton(if (card.state == ScoreState.RETURNED) "Fix and resend" else "Send to coach", !busy, Modifier.weight(1.4f)) { confirmSend = true }
        })
        own && card.state == ScoreState.VALIDATED -> ({ CardButton("Ask for a correction", Modifier.weight(1f), enabled = !busy) { onStart("request_training_correction", row) } })
        coach && !own && card.state == ScoreState.PENDING -> ({
            CardButton("Return", Modifier.weight(1f), danger = true, enabled = !busy) { onStart("validate_training", JsonObject(row + ("validation" to JsonPrimitive("returned_for_correction")))) }
            StickyButton("Validate", !busy, Modifier.weight(1.3f)) { onStart("validate_training", JsonObject(row + ("validation" to JsonPrimitive("validated")))) }
        })
        coach && !own -> ({ CardButton("Edit official record", Modifier.weight(1f), enabled = !busy) { onStart("save_training", row) } })
        else -> null
    }
    HeroScaffold(card.scoreLabel, onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill(if (own) "MY ROUND" else memberName(workspace, card.userId).uppercase(), Color.White.copy(alpha = .16f), Color.White); Pill(card.state.label, Club.lime, Club.forestDeep) },
        heroExtra = { Text("${fmtDate(card.date)} · ${card.conditions}", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium) },
        sticky = sticky) {
        when (card.state) {
            ScoreState.RETURNED -> NoticeCard("The coach returned this round", row.text("feedback").ifBlank { "Check the score and details, then send it back." }, Tone.DANGER)
            ScoreState.PENDING -> NoticeCard(if (own) "With your coach" else "Waiting for validation", if (own) "You'll get a notice when it's validated or returned." else "Check the score against the scoresheet or video before validating.", Tone.WARNING)
            ScoreState.PERSONAL -> if (own) NoticeCard("Only you can see this round", "Send it to a coach to make it part of your official record.", Tone.INFO)
            ScoreState.VALIDATED -> NoticeCard("Official record", "Validated${row.text("validated_at").takeIf { it.isNotBlank() }?.let { " on " + fmtDate(row.date("validated_at")) }.orEmpty()}. Ask for a correction if something is wrong.", Tone.POSITIVE)
        }
        RaisedCard {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(card.percent, plainNumber(card.score), "of ${plainNumber(card.max)}")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(workspace.records("events").find { it.text("id") == row.text("event_id") }?.text("title") ?: "Practice round",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text("${card.percent}% of the maximum", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(listOf(row.text("category"), row.text("level")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        RaisedCard {
            Column(Modifier.padding(16.dp)) {
                KeyValue("Date", fmtDate(card.date)); KeyValue("Distance", row.text("distance").takeIf { it.isNotBlank() }?.let { "${plainNumber(it.toDoubleOrNull() ?: 0.0)} m" }.orEmpty())
                KeyValue("Target face", row.text("target_face")); KeyValue("Format", row.text("scoring_format"))
                KeyValue("Grouping", row.text("grouping")); KeyValue("Notes", row.text("observations"))
                KeyValue("Recorded by", when (row.text("source")) { "personal" -> if (own) "You" else "The archer"; "csv" -> "Coach (CSV import)"; else -> "Coach" })
            }
        }
        if (row.text("feedback").isNotBlank() && card.state != ScoreState.RETURNED) RaisedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val coachName = row.text("validated_by").takeIf { it.isNotBlank() }?.let { memberName(workspace, it) } ?: "Coach"
                    Initials(coachName); Column { Text("Coach feedback", style = MaterialTheme.typography.titleSmall); Text(coachName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(row.text("feedback"), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val canAttach = canAttachDocument(11, id, workspace)
        val hasFiles = workspace.records("documents").any { it.text("record_id") == id }
        if (hasFiles || canAttach) {
            DeskLabel("Video and files")
            AttachedFiles(workspace, id, onOpenDocument)
            if (canAttach && vm != null) RaisedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Add a short form clip (MP4, up to 10 seconds) or a photo of your scoresheet. Only you and coaches can see them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TrainingVideoCapture(id, vm, busy)
                    CardButton("Upload a file", Modifier.fillMaxWidth(), enabled = !busy) { picker.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "video/mp4")) }
                }
            }
        }
    }
    if (confirmSend) ConfirmDialog("Send to your coach?", "A coach checks the score and validates it or returns it with a note. You can't edit it while it's with them.",
        "Send", onConfirm = { confirmSend = false; onCommand("submit_training", versioned(row)) {} }, onDismiss = { confirmSend = false })
}

@Composable
fun TrainingDeskScreen(
    workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit,
    onOpen: (String) -> Unit, onStart: (String, JsonObject) -> Unit, onRecords: () -> Unit,
) {
    val queue = coachQueue(workspace)
    val corrections = trainingCorrections(workspace)
    val today = LocalDate.now()
    val validatedThisMonth = scoreCards(workspace).count { it.state == ScoreState.VALIDATED && it.row.date("validated_at")?.let { d -> d.year == today.year && d.month == today.month } == true }
    var importing by rememberSaveable { mutableStateOf(false) }
    HeroScaffold("Scorecards", onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill("COACH DESK", Club.lime, Club.forestDeep) },
        heroExtra = { HeroStats(listOf("${queue.size}" to "To validate", "${corrections.size}" to "Corrections", "$validatedThisMonth" to "Validated this month")) }) {
        // Two ways to add rounds: one at a time, or a whole session from a spreadsheet.
        // A white card, so both buttons read the same whether they sit over the green hero or the page.
        RaisedCard {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onStart("save_training", buildJsonObject { put("training_date", today.toString()) }) }, enabled = !busy,
                    modifier = Modifier.weight(1f).height(48.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("Add a round", fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
                OutlinedButton(onClick = { importing = !importing }, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Club.forest),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Club.forestDeep)) {
                    Text(if (importing) "Close import" else "Import CSV", fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
            }
        }
        if (importing && vm != null) CoachImportCard(workspace, busy, vm) { importing = false }
        DeskLabel("Waiting for you")
        if (queue.isEmpty()) EmptyState("All caught up", "Rounds that archers send for checking appear here.")
        queue.forEach { c ->
            val name = memberName(workspace, c.userId)
            val hasVideo = workspace.records("documents").any { it.text("record_id") == c.id && it.text("name").endsWith(".mp4", true) }
            DeskRow("$name · ${c.scoreLabel}", "${fmtDate(c.date)} · ${c.conditions}", leading = { Initials(name, 40) },
                status = if (hasVideo) "Video" to Tone.NEUTRAL else null, onClick = { onOpen(c.id) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardButton("Return", Modifier.weight(1f), danger = true, enabled = !busy) { onStart("validate_training", JsonObject(c.row + ("validation" to JsonPrimitive("returned_for_correction")))) }
                    CardButton("Validate", Modifier.weight(1f), primary = true, enabled = !busy) { onStart("validate_training", JsonObject(c.row + ("validation" to JsonPrimitive("validated")))) }
                }
            }
        }
        if (corrections.isNotEmpty()) {
            DeskLabel("Correction requests")
            corrections.forEach { r ->
                val record = scoreCards(workspace).find { it.id == r.text("record_id") }
                DeskRow(memberName(workspace, r.text("user_id").ifBlank { record?.userId.orEmpty() }), r.text("request").ifBlank { "Asks to correct ${record?.scoreLabel ?: "a validated round"}" },
                    leading = { IconChip("doc", Tone.WARNING) }, status = "Review" to Tone.WARNING, onClick = { onStart("review_training_correction", r) })
            }
        }
        val recent = scoreCards(workspace).filter { it.state == ScoreState.VALIDATED }.take(5)
        if (recent.isNotEmpty()) {
            DeskLabel("Recently validated", "All records", onRecords)
            recent.forEach { c -> DeskRow("${memberName(workspace, c.userId)} · ${c.scoreLabel}", "${fmtDate(c.date)} · ${c.conditions}",
                leading = { DayChip(c.date, Tone.POSITIVE) }, onClick = { onOpen(c.id) }) }
        } else TextButton(onClick = onRecords, modifier = Modifier.fillMaxWidth()) { Text("All training records") }
    }
}


/** Import a whole session from a spreadsheet: plain column names, members by name, a preview, then one tap. */
@Composable
private fun CoachImportCard(workspace: JsonObject, busy: Boolean, vm: SystemViewModel, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var result by remember { mutableStateOf<CoachImport?>(null) }
    var fileName by remember { mutableStateOf("") }
    // An arrow-by-arrow export from a scoring app has no member column: the coach picks the archer.
    var arrowText by remember { mutableStateOf<String?>(null) }
    var archer by remember { mutableStateOf("") }
    val active = workspace.records("member_directory").filter { it.text("status") == "Active" }.sortedBy { it.text("full_name") }
    val example = workspace.records("member_directory").firstOrNull { it.text("status") == "Active" }?.text("full_name").orEmpty().ifBlank { "Juan Dela Cruz" }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) fileName = it.getString(0) }
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { r ->
                val buffer = CharArray(1_000_001); var count = 0
                while (count < buffer.size) { val n = r.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                require(count <= 1_000_000) { "Choose a CSV smaller than 1 MB." }
                String(buffer, 0, count)
            } ?: error("This file could not be read.")
            text
        }.onSuccess { text ->
            if (isArrowExport(text)) { arrowText = text; archer = ""; result = null }
            else { arrowText = null; result = coachImport(text, workspace) }
        }.onFailure { vm.error(it.message ?: "Check the CSV and try again.") }
    }
    RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Import a session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        Text("Make a spreadsheet with one row per archer, save it as CSV, then choose it here. Use the member's name as it appears in the app.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Columns: member, date, distance, target_face, bow, round, score, max_score. Optional: notes, feedback.",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text("An arrow-by-arrow export from a scoring app works too: choose it, then pick the archer. Each session becomes one round, with the end totals in its notes.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(coachCsvTemplate(example, LocalDate.now()))); vm.notify("Template copied. Paste it into a spreadsheet.") },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)) { Text("Copy template") }
            Button(onClick = { picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream")) }, enabled = !busy,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp), colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text("Choose CSV") }
        }
        arrowText?.let { text ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            if (fileName.isNotBlank()) Text(fileName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Scoring-app export. Whose arrows are these?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
            SelectField("Archer", archer, listOf("" to "Choose a member") + active.map { it.text("user_id") to it.text("full_name") }) { id ->
                archer = id
                result = if (id.isBlank()) null else arrowImport(text, id, active.first { it.text("user_id") == id }.text("full_name"))
            }
        }
        result?.let { r ->
            if (arrowText == null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            if (fileName.isNotBlank() && arrowText == null) Text(fileName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.problems.isNotEmpty()) NoticeCard("Fix ${r.problems.size} thing${if (r.problems.size == 1) "" else "s"} in the file", r.problems.take(5).joinToString("\n") + if (r.problems.size > 5) "\n…and ${r.problems.size - 5} more" else "", Tone.DANGER)
            if (r.rows.isNotEmpty()) {
                Text("${r.rows.size} round${if (r.rows.size == 1) "" else "s"} ready", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                r.preview.take(8).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                if (r.preview.size > 8) Text("…and ${r.preview.size - 8} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Imported rounds count as checked by you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { vm.command("import_training", buildJsonObject { put("rows", JsonArray(r.rows)) }) { result = null; arrowText = null; onDone() } },
                enabled = !busy && r.rows.isNotEmpty() && r.problems.isEmpty(), modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp), colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) {
                Text(if (r.problems.isEmpty()) "Import ${r.rows.size} round${if (r.rows.size == 1) "" else "s"}" else "Fix the file, then choose it again", fontWeight = FontWeight.ExtraBold)
            }
        }
    } }
}
