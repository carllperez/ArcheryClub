package ph.capstone.archeryclub.connected

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ClubIcon
import java.time.Instant
import java.time.LocalTime

/* Officer home: what needs me today, what is happening now, and the club at a glance. */

private fun queueIcon(route: String) = when {
    route == "applications" -> "doc"; route == "name_corrections" -> "profile"; route == "module:7" -> "users"
    route == "equipment" -> "box"; route == "module:8" || route == "officer_activities" -> "calendar"
    route == "module:11" -> "target"; route == "module:12" -> "money"; route == "module:14" -> "settings"; else -> "bell"
}

fun roleLabel(permissions: Set<String>) = when {
    "president" in permissions -> "PRESIDENT"
    "administration" in permissions -> "ADMIN"
    "membership" in permissions -> "MEMBERSHIP OFFICER"
    "equipment" in permissions -> "EQUIPMENT OFFICER"
    "activities" in permissions -> "ACTIVITIES OFFICER"
    "training" in permissions -> "TRAINING OFFICER"
    "finance" in permissions -> "TREASURER"
    else -> "OFFICER"
}

@Composable
fun OfficerHome(
    workspace: JsonObject, permissions: Set<String>, fullName: String, state: SystemState, snackbar: SnackbarHostState,
    bottomBar: @Composable () -> Unit, onRoute: (String) -> Unit, onAnnouncement: () -> Unit, onRefresh: () -> Unit, recovery: @Composable () -> Unit,
) {
    val now = Instant.now()
    val queues = officerQueues(workspace, permissions, now).filter { it.count > 0 }
        .sortedByDescending { if (it.tone == Tone.DANGER) 1000 + it.count else it.count }
    val total = queues.sumOf { it.count }
    val members = workspace.records("member_directory").count { it.text("status") == "Active" }
    val events = workspace.records("events").filter { it.text("published") == "true" && it.text("archived") != "true" }.map { activityView(it, workspace, now) }
    val upcoming = events.filter { it.end?.isAfter(now) == true }.sortedBy { it.start }
    val past30 = events.filter { it.end?.isBefore(now) == true && it.end.isAfter(now.minusSeconds(30 * 86400L)) }
    val rate30 = attendanceRate(past30.sumOf { it.present }, past30.sumOf { it.registered }, past30.sumOf { it.excused })
    val week = upcoming.count { it.start?.isBefore(now.plusSeconds(7 * 86400L)) == true }
    HeroScaffold("${greetingFor(LocalTime.now().hour)}, ${firstName(fullName)}", snackbar = snackbar, overlap = 44.dp, busy = state.busy, bottomBar = bottomBar,
        eyebrow = { Pill(roleLabel(permissions), Club.lime, Club.forestDeep) },
        heroExtra = {
            Text(if (total == 0) "You're all caught up" else "$total thing${if (total == 1) "" else "s"} need you today",
                style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .9f))
            HeroStats(listOf("$members" to "Active members", "$week" to "This week", (rate30?.let { "$it%" } ?: "—") to "Attendance 30d"))
        }) {
        state.refreshError?.let { NoticeCard("Refresh needed", it, Tone.WARNING); SecondaryAction("Try again", !state.busy, onRefresh) }
        if (state.recovery) recovery()

        // The session that matters most right now: in progress, else the next one.
        val focus = upcoming.firstOrNull { it.start?.isBefore(now) == true } ?: upcoming.firstOrNull()
        if (focus != null && "activities" in permissions) {
            val live = focus.start?.isBefore(now) == true
            RaisedCard(onClick = { onRoute("activity:${focus.id}") }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (live) "HAPPENING NOW" else "NEXT SESSION", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Pill(if (live) "Live" else countdownLabel(focus.start), Club.amberSoft, Club.amberInk)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        DateBlock(focus.start)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(focus.event.text("title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${fmtTime(focus.start)} · ${focus.event.text("venue")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (focus.capacity > 0) SlotBar(focus.registered, focus.capacity)
                            Text(if (live) "${focus.present} of ${focus.registered} checked in" else "${focus.registered} of ${focus.capacity} registered",
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    PillButton(if (live) "Take attendance" else "Manage participants", { onRoute("activity:${focus.id}|roster:${focus.id}") })
                }
            }
        }

        if (queues.isEmpty()) EmptyState("You're all caught up", "Requests from members appear here as soon as they're submitted.")
        else {
            SectionHeader("Needs your action")
            queues.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { q -> QueueTile(q, Modifier.weight(1f).fillMaxHeight()) { onRoute(q.route) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if ("activities" in permissions) {
            SectionHeader("Upcoming activities", "See all") { onRoute("officer_activities") }
            if (upcoming.isEmpty()) EmptyState("No published activities", "Create one and publish it so members can register.", "Create activity") { onRoute("officer_activities") }
            upcoming.drop(if (focus != null) 1 else 0).take(3).forEach { ActivityCard(it, { onRoute("activity:${it.id}") }, officer = true) }
            SecondaryAction("Post an announcement", !state.busy, onAnnouncement)
        }
    }
}

@Composable
private fun QueueTile(q: QueueItem, modifier: Modifier, onClick: () -> Unit) {
    val danger = q.tone == Tone.DANGER
    RaisedCard(modifier, radius = 18.dp, onClick = onClick) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(if (danger) Club.roseSoft else Club.mint), contentAlignment = Alignment.Center) {
                    ClubIcon(queueIcon(q.route), Modifier.size(19.dp), if (danger) Club.roseInk else Club.forest)
                }
                Spacer(Modifier.weight(1f))
                Text("${q.count}", style = MaterialTheme.typography.headlineSmall, color = if (danger) Club.roseInk else MaterialTheme.colorScheme.onSurface)
            }
            Text(q.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
