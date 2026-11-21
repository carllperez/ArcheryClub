package ph.capstone.archeryclub.connected

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.Instant

/* M15 Interclub pages (mockup row "Interclub: share an event with partner clubs, start to finish").
 * The event page shows the six steps and the one thing to do now; each step has its own page.
 * Partner representatives use the same event page, limited by the database to their own delegation. */

/** Each partner club keeps one colour on every page, picked from its id so it never changes. */
private val CLUB_SWATCHES = listOf(Color(0xFFDCEAF7) to Color(0xFF1D4E7A), Color(0xFFFBDCC4) to Color(0xFF8A3B12),
    Color(0xFFEDE0F7) to Color(0xFF5B2A86), Color(0xFFDCE9E6) to Color(0xFF2E5650), Color(0xFFFCEFB4) to Color(0xFF6B5300))
private fun swatch(partnerId: String) = CLUB_SWATCHES[Math.floorMod(partnerId.hashCode(), CLUB_SWATCHES.size)]

@Composable
private fun ClubTag(workspace: JsonObject, partnerId: String) {
    val name = workspace.records("partner_clubs").find { it.text("id") == partnerId }?.text("name") ?: "Partner club"
    val (bg, fg) = swatch(partnerId)
    Text(name, Modifier.clip(CircleShape).background(bg).padding(horizontal = 8.dp, vertical = 3.dp), color = fg,
        fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun ClubBadge(name: String, partnerId: String, size: Int = 38) {
    val (bg, fg) = swatch(partnerId)
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3).dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(clubInitials(name), color = fg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
    }
}

/** The six steps in the green hero: done ones ticked, the current one ringed. */
@Composable
private fun StepTracker(step: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        IC_STEPS.forEachIndexed { i, label ->
            val done = i < step; val current = i == step
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(24.dp).clip(CircleShape)
                    .background(when { done -> Club.lime; current -> Color.White; else -> Color.White.copy(alpha = .18f) })
                    .then(if (current) Modifier.border(3.dp, Club.lime.copy(alpha = .55f), CircleShape) else Modifier),
                    contentAlignment = Alignment.Center) {
                    Text(if (done) "✓" else "${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                        color = if (done || current) Club.forestDeep else Color.White.copy(alpha = .75f))
                }
                Text(label, fontSize = 10.sp, fontWeight = if (current) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1,
                    color = if (done || current) Color.White else Color.White.copy(alpha = .65f))
            }
        }
    }
}

/** A decision that needs a note (the server requires one): approve, return, check in. */
private data class Decide(val title: String, val confirm: String, val note: String, val danger: Boolean, val run: (String) -> Unit)

@Composable
private fun DecideDialog(d: Decide, onDismiss: () -> Unit) {
    var note by rememberSaveable(d.title) { mutableStateOf(d.note) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(d.title) },
        text = { OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note for the record") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = note.isNotBlank(), onClick = { d.run(note.trim()); onDismiss() }) {
            Text(d.confirm, fontWeight = FontWeight.ExtraBold, color = if (d.danger) Club.roseInk else Club.forestDeep) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private fun delegateLabel(status: String) = when (status) {
    "pending" -> "To review"; "incomplete" -> "Returned"; "eligible" -> "Approved"; "checked_in" -> "Here"; "rejected" -> "Not eligible"; else -> human(status)
}

private fun delegateCommand(d: JsonObject, status: String, note: String) = buildJsonObject {
    put("event_id", d.text("event_id")); put("id", d.text("id")); d["version"]?.let { put("version", it) }; put("status", status); put("remark", note)
}

// ---------------------------------------------------------------- Event page

@Composable
fun InterclubScreen(
    workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, coordinator: Boolean, interclubOfficer: Boolean,
    onBack: () -> Unit, onOpen: (String) -> Unit, onPush: (String) -> Unit, onStart: (String, JsonObject) -> Unit,
    onCommand: (String, JsonObject, () -> Unit) -> Unit, onRecords: () -> Unit, vm: SystemViewModel? = null,
) {
    val events = workspace.records("interclub_events")
    val e = if (id.isNotBlank()) events.find { it.text("event_id") == id } else featuredInterclub(events.filter { it.text("status") != "archived" }, Instant.now())
    if (e == null) {
        HeroScaffold("Interclub", onBack, snackbar, overlap = 44.dp, busy = busy, eyebrow = { Pill("INTERCLUB", Color.White.copy(alpha = .16f), Color.White) },
            heroExtra = { Text("Share a club activity with partner clubs, collect their delegates and publish verified results.", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium) }) {
            EmptyState("No shared events yet", "Post the activity first (Events desk), then share it here with partner clubs.")
            // A filled button: the outlined one in the empty card read as disabled (21 Nov testing).
            if (interclubOfficer) CardButton("Share an activity", Modifier.fillMaxWidth(), primary = true, enabled = !busy) { onStart("share_interclub_event", buildJsonObject {}) }
            // The clubs already added, so the officer can see them before sharing an event (they weren't listed anywhere).
            val clubs = workspace.records("partner_clubs").sortedBy { it.text("name") }
            if (interclubOfficer) {
                DeskLabel("Partner clubs · ${clubs.size}")
                if (clubs.isEmpty()) Text("No partner clubs yet. Add one, then share an activity with it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else RowGroup { clubs.forEachIndexed { i, c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ClubBadge(c.text("name"), c.text("id"))
                        Column(Modifier.weight(1f)) {
                            Text(c.text("name"), style = MaterialTheme.typography.titleSmall)
                            if (c.text("official_channel").isNotBlank()) Text(c.text("official_channel"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (i < clubs.lastIndex) HorizontalDivider(color = Club.line.copy(alpha = .6f))
                } }
            }
            if (interclubOfficer) RowGroup {
                GroupRow("users", "Add a partner club") { onStart("save_partner", buildJsonObject {}) }
                GroupRow("calendar", "Past events", "${events.count { it.text("status") == "archived" }} archived", divider = false) { onPush("ic_past:") }
            }
        }
        return
    }
    val eid = e.text("event_id"); val status = e.text("status"); val now = Instant.now()
    val step = interclubStep(e, workspace, now)
    val next = interclubNext(e, workspace, now)
    val partners = partnerViews(workspace, eid)
    val delegates = icDelegates(workspace, eid)
    val toReview = delegates.count { it.text("status") == "pending" }
    val concerns = workspace.records("interclub_concerns").filter { it.text("event_id") == eid && it.text("status") == "open" }
    val partnerName = workspace.records("partner_clubs").associate { it.text("id") to it.text("name") }
    // update_shared_event needs every field, so the stage change re-sends the event as it is.
    fun moveTo(stage: String) = onCommand("update_shared_event", buildJsonObject {
        put("event_id", eid); e["version"]?.let { put("version", it) }; put("status", stage)
        listOf("shared_details", "shared_venue", "shared_start", "shared_end").forEach { put(it, e.text(it)) }
    }) {}
    var confirmStage by remember { mutableStateOf<String?>(null) }
    var newInvite by remember { mutableStateOf(false) }
    HeroScaffold(e.text("shared_title"), onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill("INTERCLUB", Color.White.copy(alpha = .16f), Color.White)
            Pill(when (status) { "preparation" -> "PREPARATION"; "active" -> "EVENT DAY"; "completed" -> "FINISHED"; else -> "ARCHIVED" }, Club.amberSoft, Club.amberInk) },
        heroExtra = {
            Text("${fmtDay(e.instant("shared_start"))} · ${e.text("shared_venue")}${if (status == "preparation") " · sign-ups close " + fmtDay(e.instant("registration_closes")) else ""}",
                color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium)
            StepTracker(step)
        }) {
        // A partner rep sees their own delegation's numbers; the officer sees the event's.
        val targeted = icAssignments(workspace, eid).map { it.text("delegate_id") }.toSet()
        val stats = if (coordinator) listOf("${partners.size}" to "Partner clubs", "${delegates.size}" to "Delegates", "$toReview" to "To review")
            else listOf("${delegates.size}" to "Delegates", "${delegates.count { it.text("status") in setOf("eligible", "checked_in") }}" to "Approved",
                "${delegates.count { it.text("id") in targeted }}" to "With a target")
        RaisedCard {
            Row(Modifier.padding(vertical = 14.dp)) {
                stats.forEachIndexed { i, (v, l) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, style = MaterialTheme.typography.headlineSmall, color = if (coordinator && i == 2 && toReview > 0) Club.amberInk else MaterialTheme.colorScheme.onSurface)
                        Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        // The current step's job, on top, so whoever opens the event knows the one thing to do.
        // An open concern from a partner club comes before the step's own job (21 Nov testing: the card kept saying
        // "Waiting for delegations" while a concern sat further down the page).
        val firstConcern = concerns.firstOrNull()
        if (coordinator && firstConcern != null) Surface(onClick = { onStart("resolve_concern", firstConcern) },
            shape = RoundedCornerShape(16.dp), color = Club.forestDeep, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("THIS STEP · CONCERNS", color = Club.lime, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                    Text(if (concerns.size == 1) "Reply to ${partnerName[firstConcern.text("partner_id")] ?: "a partner club"}"
                        else "Reply to ${concerns.size} concerns", color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                    Text(firstConcern.text("concern"), color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("›", color = Color.White, fontSize = 22.sp)
            }
        }
        else if (coordinator && next != null) Surface(onClick = {
            when { next.route != null -> onPush(next.route); next.step == IC_INVITE -> newInvite = true
                next.step == IC_ARCHIVE -> onStart("archive_interclub", e) }
        }, shape = RoundedCornerShape(16.dp), color = Club.forestDeep, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("THIS STEP · ${IC_STEPS[next.step].uppercase()}", color = Club.lime, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                    Text(next.title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                    Text(next.detail, color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
                }
                Text("›", color = Color.White, fontSize = 22.sp)
            }
        }
        // Moving the event on: preparation → event day → finished. Archiving has its own form (the lessons to keep).
        if (coordinator) when (status) {
            "preparation" -> if (step >= IC_SCHEDULE) CardButton("Start the event day", Modifier.fillMaxWidth(), primary = true, enabled = !busy) { confirmStage = "active" }
            // Not offered from the first minute of event day: once results are being recorded, or the scheduled end has passed.
            "active" -> if (step >= IC_RESULTS || e.instant("shared_end")?.isBefore(now) == true)
                CardButton("Finish the event", Modifier.fillMaxWidth(), enabled = !busy) { confirmStage = "completed" }
        }
        if (coordinator) concerns.forEach { c ->
            NoticeCard("Concern from ${partnerName[c.text("partner_id")] ?: "a partner"}", c.text("concern"), Tone.WARNING)
            CardButton("Reply to this concern", Modifier.fillMaxWidth(), enabled = !busy) { onStart("resolve_concern", c) }
        }
        // A partner rep's page is their delegation, their concerns and the published results; the officer's step pages
        // (review, schedule, check-in, results) only ever told them "only the coordinators can do this".
        if (!coordinator) PartnerDelegation(workspace, eid, busy, onStart)
        // Inviting stops when partner sign-ups close (the server refuses new codes then; 21 Nov testing still showed "Invite").
        val signupsOpen = status == "preparation" && (e.instant("registration_closes")?.let { now.isBefore(it) } ?: true)
        if (coordinator) DeskLabel("Partner clubs", if (interclubOfficer && signupsOpen) "Invite" else null) { newInvite = true }
        if (coordinator && partners.isEmpty()) EmptyState("No partner clubs yet", "Create an invite code for a club's representative. They only ever see this event.")
        else if (coordinator) RowGroup { partners.forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ClubBadge(p.partner.text("name"), p.partner.text("id"))
                Column(Modifier.weight(1f)) {
                    Text(p.partner.text("name"), style = MaterialTheme.typography.titleSmall)
                    Text("${p.delegates} delegate${if (p.delegates == 1) "" else "s"}${if (p.toReview > 0) " · ${p.toReview} to review" else ""}${if (p.incomplete > 0) " · ${p.incomplete} returned" else ""}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(p.label, p.tone)
            }
            if (i < partners.lastIndex) HorizontalDivider(color = Club.line.copy(alpha = .6f))
        } }
        // Invite codes replace inviting by email: holding the code, not the email address, makes someone the rep.
        if (coordinator) PartnerInvites(workspace, e, busy, vm, interclubOfficer && signupsOpen, newInvite) { newInvite = false }
        // Each step's page.
        if (coordinator) DeskLabel("Steps")
        if (coordinator) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("Delegates", "$toReview to review", "ic_delegates:$eid"), Triple("Schedule", "${icAssignments(workspace, eid).size} assigned", "ic_schedule:$eid")).forEach { (t, s, r) ->
                StepTile(t, s, Modifier.weight(1f)) { onPush(r) }
            }
        }
        if (coordinator) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val here = delegates.count { it.text("status") == "checked_in" }
            val verified = icResults(workspace, eid).count { it.text("verified_by").isNotBlank() }
            listOf(Triple("Check-in", "$here here", "ic_checkin:$eid"), Triple("Results", "$verified verified", "ic_results:$eid")).forEach { (t, s, r) ->
                StepTile(t, s, Modifier.weight(1f)) { onPush(r) }
            }
        }
        if (interclubOfficer) {
            DeskLabel("Interclub officer")
            RowGroup {
                GroupRow("users", "Add a partner club") { onStart("save_partner", buildJsonObject {}) }
                GroupRow("calendar", "Share another activity") { onStart("share_interclub_event", buildJsonObject {}) }
                GroupRow("check", "Past events", "${events.count { it.text("status") == "archived" }} archived") { onPush("ic_past:") }
                GroupRow("modules", "All interclub records", divider = false, onClick = onRecords)
            }
        }
        val others = events.filter { it.text("event_id") != eid && it.text("status") != "archived" }.sortedBy { it.text("shared_start") }
        if (others.isNotEmpty()) {
            DeskLabel("Other shared events")
            others.take(5).forEach { o -> DeskRow(o.text("shared_title"), "${fmtDay(o.instant("shared_start"))} · ${human(o.text("status"))}", leading = { IconChip("calendar", Tone.NEUTRAL) }, onClick = { onOpen(o.text("event_id")) }) }
        }
    }
    confirmStage?.let { stage ->
        AlertDialog(onDismissRequest = { confirmStage = null },
            title = { Text(if (stage == "active") "Start the event day?" else "Finish the event?") },
            text = { Text(if (stage == "active") "Check-in opens and delegations can no longer be changed, only substituted." else "Check-in and assignments close. Record and verify the remaining results, then archive.") },
            confirmButton = { TextButton(onClick = { moveTo(stage); confirmStage = null }) { Text(if (stage == "active") "Start" else "Finish", fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { confirmStage = null }) { Text("Cancel") } })
    }
}

@Composable
private fun StepTile(title: String, detail: String, modifier: Modifier, onClick: () -> Unit) =
    RaisedCard(modifier, radius = 18.dp, onClick = onClick) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

/** A partner representative's own delegation: the database only returns their club's delegates. */
@Composable
private fun PartnerDelegation(workspace: JsonObject, eid: String, busy: Boolean, onStart: (String, JsonObject) -> Unit) {
    val e = workspace.records("interclub_events").find { it.text("event_id") == eid } ?: return
    val mine = icDelegates(workspace, eid)
    val partnerId = workspace.records("event_access").find { it.text("event_id") == eid && it.text("user_id") == workspace.text("user_id") }?.text("partner_id").orEmpty()
    val assignments = icAssignments(workspace, eid).associateBy { it.text("delegate_id") }
    val open = e.text("status") == "preparation" && (e.instant("registration_closes")?.let { Instant.now().isBefore(it) } ?: true)
    DeskLabel("Your delegation · ${mine.size}")
    if (mine.isEmpty()) EmptyState("No delegates yet", if (open) "Add each archer from your club with their division and requirements." else "Sign-ups for this event have closed.")
    mine.forEach { d ->
        val a = assignments[d.text("id")]
        DeskRow(d.text("full_name"), listOfNotNull(d.text("category"), a?.let { "${it.text("target_group")} · ${fmtTime(it.instant("starts_at"))}" }).joinToString(" · "),
            leading = { Initials(d.text("full_name"), 36) }, status = delegateLabel(d.text("status")) to delegateTone(d.text("status")),
            onClick = if (open && d.text("status") in setOf("pending", "incomplete")) ({ onStart("save_delegate", JsonObject(d + ("partner_id" to JsonPrimitive(partnerId)))) }) else null) {
            if (d.text("status") == "incomplete" && d.text("remark").isNotBlank()) Text("DLSU: ${d.text("remark")}", style = MaterialTheme.typography.bodySmall, color = Club.roseInk)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (open) CardButton("Add a delegate", Modifier.weight(1f), primary = true, enabled = !busy) {
            onStart("save_delegate", buildJsonObject { put("event_id", eid); put("partner_id", partnerId) }) }
        CardButton("Raise a concern", Modifier.weight(1f), enabled = !busy) { onStart("raise_concern", buildJsonObject { put("event_id", eid) }) }
    }
    // Concerns this rep raised: waiting ones, and answered ones with DLSU's reply (migration 045 also sends a notice).
    val myConcerns = workspace.records("interclub_concerns").filter { it.text("event_id") == eid && it.text("submitted_by") == workspace.text("user_id") }
        .sortedByDescending { it.text("created_at") }
    if (myConcerns.isNotEmpty()) {
        DeskLabel("Your concerns")
        myConcerns.forEach { c ->
            if (c.text("status") == "open") NoticeCard("Waiting for DLSU's reply", c.text("concern"), Tone.WARNING)
            else NoticeCard("DLSU replied", "${c.text("resolution")}\n\nYou asked: ${c.text("concern")}", Tone.POSITIVE)
        }
    }
    val published = icResults(workspace, eid).filter { it.text("verified_by").isNotBlank() }
    if (published.isNotEmpty()) {
        DeskLabel("Published results")
        val byId = workspace.records("delegates").associateBy { it.text("id") }
        RowGroup { icRanked(published).forEachIndexed { i, r ->
            GroupRow("check", byId[r.text("delegate_id")]?.text("full_name") ?: "Delegate", r.text("result"), divider = i < published.lastIndex, onClick = null)
        } }
    }
}

// ---------------------------------------------------------------- Delegates

@Composable
fun InterclubDelegatesScreen(workspace: JsonObject, eid: String, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
                             onStart: (String, JsonObject) -> Unit, onCommand: (String, JsonObject, () -> Unit) -> Unit) =
    ScreenShell("Delegates", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val all = icDelegates(workspace, eid)
        val groups = linkedMapOf("review" to all.filter { it.text("status") == "pending" }, "approved" to all.filter { it.text("status") in setOf("eligible", "checked_in") },
            "other" to all.filter { it.text("status") in setOf("incomplete", "rejected") })
        var tab by rememberSaveable { mutableStateOf("review") }
        var decide by remember { mutableStateOf<Decide?>(null) }
        ChoiceChips(listOf("review" to "To review · ${groups["review"]!!.size}", "approved" to "Approved · ${groups["approved"]!!.size}", "other" to "Returned · ${groups["other"]!!.size}"), tab) { tab = it }
        val list = groups[tab].orEmpty()
        if (list.isEmpty()) EmptyState(when (tab) { "review" -> "Nothing to review"; "approved" -> "No approved delegates yet"; else -> "Nothing returned" },
            if (tab == "review") "New delegates from partner clubs appear here." else "")
        list.sortedBy { it.text("full_name") }.forEach { d ->
            RaisedCard(radius = 18.dp) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Initials(d.text("full_name"), 40)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(d.text("full_name"), style = MaterialTheme.typography.titleSmall)
                            Text(listOf(d.text("category"), d.text("affiliation")).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ClubTag(workspace, d.text("partner_id"))
                        }
                    }
                    if (d.text("requirements").isNotBlank()) Text("Requirements: ${d.text("requirements")}", style = MaterialTheme.typography.bodySmall)
                    listOf("Needs" to d.text("logistical_needs"), "Equipment" to d.text("equipment_needs")).filter { it.second.isNotBlank() }.forEach { (k, v) ->
                        Text("$k: $v", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (d.text("remark").isNotBlank() && tab != "review") Text("Note: ${d.text("remark")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (tab) {
                        // Approve on its own row: three buttons side by side were cut to "Not" and "Appro" on a small phone.
                        "review" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CardButton("Approve", Modifier.fillMaxWidth(), primary = true, enabled = !busy) {
                                decide = Decide("Approve ${d.text("full_name")}?", "Approve", "Requirements checked", false) { onCommand("review_delegate", delegateCommand(d, "eligible", it)) {} } }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CardButton("Return to fix", Modifier.weight(1f), enabled = !busy) {
                                    decide = Decide("What's missing?", "Return to fix", "", false) { onCommand("review_delegate", delegateCommand(d, "incomplete", it)) {} } }
                                CardButton("Not eligible", Modifier.weight(1f), danger = true, enabled = !busy) {
                                    decide = Decide("Mark ${d.text("full_name")} not eligible?", "Not eligible", "", true) { onCommand("review_delegate", delegateCommand(d, "rejected", it)) {} } }
                            }
                        }
                        "approved" -> if (d.text("status") in setOf("eligible", "checked_in"))
                            CardButton("Substitute", Modifier.fillMaxWidth(), enabled = !busy) { onStart("substitute_delegate", d) }
                    }
                }
            }
        }
        decide?.let { DecideDialog(it) { decide = null } }
    }

// ---------------------------------------------------------------- Schedule

@Composable
fun InterclubScheduleScreen(workspace: JsonObject, eid: String, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, onStart: (String, JsonObject) -> Unit) =
    ScreenShell("Schedule and targets", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val e = workspace.records("interclub_events").find { it.text("event_id") == eid }
        val approved = icApproved(workspace, eid)
        val byId = workspace.records("delegates").associateBy { it.text("id") }
        val assignments = icAssignments(workspace, eid)
        val assigned = assignments.map { it.text("delegate_id") }.toSet()
        val waiting = approved.filter { it.text("id") !in assigned }
        val editable = e?.text("status") in setOf("preparation", "active")
        if (waiting.isNotEmpty()) {
            NoticeCard("${waiting.size} archer${if (waiting.size == 1) "" else "s"} without a target", "Give each approved delegate a flight and target. Two archers can't share a target at the same time.", Tone.WARNING)
            waiting.sortedBy { it.text("full_name") }.forEach { d ->
                // The club tag goes under the name: beside it, it squeezed names to one word per line (21 Nov testing).
                DeskRow(d.text("full_name"), d.text("category"), leading = { Initials(d.text("full_name"), 36) }) {
                    ClubTag(workspace, d.text("partner_id"))
                    if (editable) CardButton("Assign a target", Modifier.fillMaxWidth(), primary = true, enabled = !busy) {
                        onStart("assign_delegate", buildJsonObject { put("event_id", eid); put("delegate_id", d.text("id"))
                            e?.let { put("starts_at", it.text("shared_start")); put("ends_at", it.text("shared_end")) } }) }
                }
            }
        }
        if (assignments.isEmpty() && waiting.isEmpty()) EmptyState("Nothing to schedule yet", "Approved delegates appear here to be given a flight and target.")
        // Grouped by start time (a flight), then target.
        assignments.groupBy { it.text("starts_at") }.toSortedMap().forEach { (start, rows) ->
            DeskLabel("${fmtTime(instantOf(start))} flight")
            RowGroup { rows.sortedBy { it.text("target_group") }.forEachIndexed { i, a ->
                val d = byId[a.text("delegate_id")]
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(a.text("target_group"), Modifier.width(64.dp), style = MaterialTheme.typography.titleSmall, color = Club.forest)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(d?.text("full_name") ?: "Delegate", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        if (a.text("responsibility").isNotBlank()) Text(a.text("responsibility"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        d?.let { ClubTag(workspace, it.text("partner_id")) }
                    }
                }
                if (i < rows.lastIndex) HorizontalDivider(color = Club.line.copy(alpha = .6f))
            } }
        }
        Text("Partner representatives see the targets of their own archers. Clashes are refused when you assign.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

// ---------------------------------------------------------------- Check-in

@Composable
fun InterclubCheckinScreen(workspace: JsonObject, eid: String, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
                           onCommand: (String, JsonObject, () -> Unit) -> Unit) =
    ScreenShell("Check-in", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val e = workspace.records("interclub_events").find { it.text("event_id") == eid }
        val approved = icApproved(workspace, eid)
        val here = approved.filter { it.text("status") == "checked_in" }
        val notHere = approved.filter { it.text("status") == "eligible" }
        val assignments = icAssignments(workspace, eid).associateBy { it.text("delegate_id") }
        var tab by rememberSaveable { mutableStateOf("waiting") }
        RaisedCard {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${here.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text(" / ${approved.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("  checked in", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (e?.text("status") != "active") NoticeCard("Check-in opens on the event day", "Start the event day from the event page first.", Tone.INFO)
        ChoiceChips(listOf("waiting" to "Not here · ${notHere.size}", "here" to "Here · ${here.size}"), tab) { tab = it }
        (if (tab == "waiting") notHere else here).sortedBy { it.text("full_name") }.forEach { d ->
            val a = assignments[d.text("id")]
            DeskRow(d.text("full_name"), listOfNotNull(a?.let { "${it.text("target_group")} · ${fmtTime(it.instant("starts_at"))}" }, d.text("category")).joinToString(" · "),
                leading = { Initials(d.text("full_name"), 36) },
                trailing = { if (tab == "waiting") CardButton("Check in", enabled = !busy && e?.text("status") == "active") {
                    onCommand("review_delegate", delegateCommand(d, "checked_in", "Checked in at the range")) {} } else ClubTag(workspace, d.text("partner_id")) })
        }
    }

// ---------------------------------------------------------------- Results

@Composable
fun InterclubResultsScreen(workspace: JsonObject, eid: String, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
                           onStart: (String, JsonObject) -> Unit, onCommand: (String, JsonObject, () -> Unit) -> Unit) =
    ScreenShell("Results", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val results = icResults(workspace, eid)
        val byId = workspace.records("delegates").associateBy { it.text("id") }
        val recorded = results.map { it.text("delegate_id") }.toSet()
        val unverified = results.count { it.text("verified_by").isBlank() }
        val cats = results.mapNotNull { byId[it.text("delegate_id")]?.text("category") }.distinct().sorted()
        var cat by rememberSaveable { mutableStateOf("") }
        var confirmVerify by remember { mutableStateOf<JsonObject?>(null) }
        val shown = if (cat.isBlank()) results else results.filter { byId[it.text("delegate_id")]?.text("category") == cat }
        val (bannerTitle, bannerTone) = resultsBanner(results)
        NoticeCard(bannerTitle, "A result shows to partner clubs only after someone other than the person who recorded it verifies it against the signed scoresheet.", bannerTone)
        if (cats.size > 1) ChoiceChips(listOf("" to "All") + cats.map { it to it }, cat) { cat = it }
        if (shown.isNotEmpty()) RowGroup { icRanked(shown).forEachIndexed { i, r ->
            val d = byId[r.text("delegate_id")]; val verified = r.text("verified_by").isNotBlank()
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val medal = when (i) { 0 -> Color(0xFFE8C547); 1 -> Color(0xFFC9CFD3); 2 -> Color(0xFFD9A066); else -> Club.mint }
                Box(Modifier.size(28.dp).clip(CircleShape).background(if (icScore(r.text("result")) != null) medal else Club.mint), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }
                // The result sits under the name: beside it, a long result ("612 / 720 (72 arrows, 70m)") squeezed the
                // name to one letter per line (21 Nov testing).
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(d?.text("full_name") ?: "Delegate", style = MaterialTheme.typography.titleSmall)
                    Text(r.text("result"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    d?.let { ClubTag(workspace, it.text("partner_id")) }
                }
                when {
                    verified -> Text("✓ Verified", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Club.forest)
                    // The recorder can't verify their own entry (the server refuses), so say who does instead of a button.
                    recordedByMe(r, workspace) -> Text("Another officer\nverifies", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Club.amberInk, textAlign = TextAlign.End)
                    // Verifying publishes the result to the partner clubs, so it asks first (21 Nov testing).
                    else -> TextButton(onClick = { confirmVerify = r }, enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Verify", fontWeight = FontWeight.ExtraBold) }
                }
            }
            if (i < shown.lastIndex) HorizontalDivider(color = Club.line.copy(alpha = .6f))
        } }
        val toRecord = icDelegates(workspace, eid).filter { it.text("status") == "checked_in" && it.text("id") !in recorded }
        if (toRecord.isNotEmpty()) {
            DeskLabel("Not recorded yet · ${toRecord.size}")
            toRecord.sortedBy { it.text("full_name") }.forEach { d ->
                DeskRow(d.text("full_name"), d.text("category"), leading = { Initials(d.text("full_name"), 36) },
                    trailing = { CardButton("Record", enabled = !busy) { onStart("record_result", buildJsonObject { put("event_id", eid); put("delegate_id", d.text("id")) }) } })
            }
        }
        if (results.isEmpty() && toRecord.isEmpty()) EmptyState("No results yet", "Archers who checked in appear here to have their score recorded.")
        confirmVerify?.let { r ->
            val who = byId[r.text("delegate_id")]?.text("full_name") ?: "this archer"
            ConfirmDialog("Verify $who's result?", "${r.text("result")}\n\nCheck it against the signed scoresheet. Once verified, the partner clubs see it.",
                "Verify", onConfirm = { confirmVerify = null; onCommand("verify_result", buildJsonObject { put("event_id", eid); put("id", r.text("id")) }) {} },
                onDismiss = { confirmVerify = null })
        }
    }

// ---------------------------------------------------------------- Past events

@Composable
fun InterclubPastScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, onStart: (String, JsonObject) -> Unit) =
    ScreenShell("Past events", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val events = workspace.records("interclub_events")
        val archives = workspace.records("interclub_archives").associateBy { it.text("event_id") }
        val finished = events.filter { it.text("status") == "completed" }
        finished.forEach { e ->
            DeskRow(e.text("shared_title"), "${fmtDay(e.instant("shared_start"))} · finished, not archived yet", leading = { IconChip("clock", Tone.WARNING) }) {
                CardButton("Archive with lessons learned", Modifier.fillMaxWidth(), primary = true, enabled = !busy) { onStart("archive_interclub", e) }
            }
        }
        val archived = events.filter { it.text("status") == "archived" }.sortedByDescending { it.text("shared_start") }
        if (archived.isEmpty() && finished.isEmpty()) EmptyState("No past events yet", "When an event is archived, its files and lessons stay here for the next officers.")
        archived.forEach { e ->
            val a = archives[e.text("event_id")]
            RaisedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.text("shared_title"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        StatusPill("Archived", Tone.NEUTRAL)
                    }
                    Text("${fmtDay(e.instant("shared_start"))} · ${e.text("shared_venue")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (a != null) listOf("Summary" to "summary", "Procedures" to "procedures", "Agreements" to "agreements", "Minutes" to "minutes",
                        "Issues" to "issues", "Evaluation" to "evaluation", "For next time" to "recommendations").forEach { (label, key) ->
                        if (a.text(key).isNotBlank()) Column {
                            HorizontalDivider(color = Club.line.copy(alpha = .6f), modifier = Modifier.padding(vertical = 4.dp))
                            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(a.text(key), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Text("Archived events can't be edited, so the next officers find the same records after turnover.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
