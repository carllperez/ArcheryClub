package ph.capstone.archeryclub.connected

import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
    route == "training_desk" -> "target"; route == "finance_desk" -> "money"; route == "elections" -> "vote"; route == "reports" -> "chart"
    route == "interclub" -> "users"; route == "admin" -> "settings"
    route == "applications" -> "doc"; route == "name_corrections" -> "profile"; route == "module:7" || route == "records:7" -> "users"
    route == "equipment" -> "box"; route == "module:8" || route == "records:8" || route == "officer_activities" -> "calendar"
    route == "module:11" -> "target"; route == "module:12" -> "money"; route == "module:14" -> "settings"
    route == "module:9" -> "vote"; route == "module:15" -> "users"; else -> "bell"
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfficerHome(
    workspace: JsonObject, permissions: Set<String>, fullName: String, state: SystemState, snackbar: SnackbarHostState,
    bottomBar: @Composable () -> Unit, onRoute: (String) -> Unit, onAnnouncement: () -> Unit, onRefresh: () -> Unit, recovery: @Composable () -> Unit,
    isMember: Boolean = false, onPage: (String) -> Unit = {}, onModule: (Int) -> Unit = {}, vm: SystemViewModel? = null,
) {
    val now = Instant.now()
    val desks = deskTiles(workspace, permissions, now)
    val focus = officerFocus(workspace, permissions, now)
    val total = desks.sumOf { it.waiting }
    val roles = myRoleLines(workspace, now)
    val pill = roles.firstOrNull()?.let { r ->
        r.roleName.substringBefore(" / ").uppercase() + if (roles.size > 1) " +${roles.size - 1}" else ""
    } ?: roleLabel(permissions)
    val events = workspace.records("events").filter { it.text("published") == "true" && it.text("archived") != "true" }.map { activityView(it, workspace, now) }
    val upcoming = events.filter { it.end?.isAfter(now) == true }.sortedBy { it.start }
    HeroScaffold(hello(LocalTime.now().hour, fullName), snackbar = snackbar, overlap = 44.dp, busy = state.busy, bottomBar = bottomBar,
        eyebrow = { Pill(pill, Club.lime, Club.forestDeep) },
        heroExtra = {
            Text(if (total == 0) "Nothing waiting on your desks" else "$total thing${if (total == 1) "" else "s"} need${if (total == 1) "s" else ""} you today",
                style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.White.copy(alpha = .9f))
        }) {
        state.refreshError?.let { NoticeCard("Refresh needed", it, Tone.WARNING); SecondaryAction("Try again", !state.busy, onRefresh) }
        if (state.recovery) recovery()

        // Officers who are members: each desk's main job is a card at the top (the first desk always, the others when
        // something waits), then the same member tiles a member sees. Officers who aren't members keep the desk grid.
        if (isMember) {
            val main = desks.firstOrNull()
            // Swipe through the desk jobs and, last, the officer's own next session, like the member's Home card.
            val jobs = desks.filter { it == main || it.waiting > 0 }
            val mine = landingEvents(workspace, registeredOnly = true).firstOrNull()
            val sessionCard = mine?.let { e -> activityView(e, workspace, now).let { v -> SessionCardData(e.text("title"), v.start, e.text("venue"), v.status.label) } }
            val pager = rememberPagerState { jobs.size + 1 }
            HorizontalPager(pager, pageSpacing = 12.dp, verticalAlignment = Alignment.Top) { i ->
                if (i < jobs.size) DeskJobCard(jobs[i], officerFocus(workspace, setOf(jobs[i].permission), now)) { onRoute(it) }
                else NextSessionCard(sessionCard, onOpen = { mine?.let { onRoute("activity:${it.text("id")}") } }, onBrowse = { onPage("activities") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(jobs.size + 1) { i ->
                    Box(Modifier.padding(horizontal = 3.dp).size(if (pager.currentPage == i) 18.dp else 7.dp, 7.dp).clip(CircleShape)
                        .background(if (pager.currentPage == i) Club.forest else Club.mint))
                }
            }
            AnnouncementsCard(workspace, vm, state.busy)
            val quiet = desks.filter { it != main && it.waiting == 0 }
            if (quiet.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Other desks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                quiet.forEach { d -> AssistChip(onClick = { onRoute(d.route) }, label = { Text(d.title, fontWeight = FontWeight.Bold) }) }
            }
        } else focus?.let { f ->
            RaisedCard(onClick = { onRoute(f.route) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(f.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PillButton(f.action, { onRoute(f.route) })
                }
            }
        }

        // The session that matters most right now, for whoever runs activities.
        val session = upcoming.firstOrNull { it.start?.isBefore(now) == true } ?: upcoming.firstOrNull()
        if (session != null && "activities" in permissions) {
            val live = session.start?.isBefore(now) == true
            RaisedCard(onClick = { onRoute("activity:${session.id}") }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (live) "HAPPENING NOW" else "NEXT SESSION", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Pill(if (live) "Live" else countdownLabel(session.start), Club.amberSoft, Club.amberInk)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        DateBlock(session.start)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(session.event.text("title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${fmtTime(session.start)} · ${session.event.text("venue")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (session.capacity > 0) SlotBar(session.registered, session.capacity)
                            Text(if (live) "${session.present} of ${session.registered} checked in" else "${session.registered} of ${session.capacity} registered",
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    PillButton(if (live) "Take attendance" else "Manage participants", { onRoute("activity:${session.id}|roster:${session.id}") })
                }
            }
        }

        if (!isMember) {
            SectionHeader("Your desks")
            if (desks.isEmpty()) EmptyState("No desks yet", "An administrator can choose what your role opens in Roles & access.")
            desks.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { d -> DeskTileCard(d, Modifier.weight(1f).fillMaxHeight()) { onRoute(d.route) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if ("activities" in permissions) SecondaryAction("Post an announcement", !state.busy, onAnnouncement)

        // Officers are members too: the same tiles a member sees for activities and gear, then training and dues.
        if (isMember) {
            SectionHeader("Your club")
            val user = workspace.text("user_id")
            val open = memberActivities(workspace).count { !it.status.isMine }
            val borrowed = workspace.records("borrowings").count { it.text("user_id") == user && it.text("status") in setOf("pending", "approved", "released") }
            TileRow {
                HomeTile("target", "$open", if (open == 1) "Open Activity" else "Open Activities", Modifier.weight(1f).fillMaxHeight()) { onPage("activities") }
                HomeTile("box", "$borrowed", if (borrowed == 1) "Borrow Request" else "Borrow Requests", Modifier.weight(1f).fillMaxHeight()) { onPage("gear") }
            }
            HomeRow("arrows", "Borrow Club Gear", "Bows, arrows and accessories") { onPage("gear") }
            landingEvents(workspace, registeredOnly = true).firstOrNull()?.let { e ->
                val v = activityView(e, workspace, now)
                HomeRow("calendar", e.text("title"), "${fmtDay(v.start)} · ${fmtTime(v.start)} · ${v.status.label}") { onRoute("activity:${e.text("id")}") }
            } ?: HomeRow("calendar", "Activities", "Register for a session") { onPage("activities") }
            HomeRow("target", "My training", scoreCards(workspace, user).firstOrNull()?.let { "Last round ${it.scoreLabel} · ${it.state.label}" } ?: "Log rounds, get coach feedback") { onModule(4) }
            val owed = amountOwed(feeViews(workspace, user, java.time.LocalDate.now()))
            if (owed > 0) HomeRow("money", "Payments", "${peso(owed)} due") { onModule(12) }
            featuredElection(workspace.records("elections"), false)?.takeIf { it.text("status") in setOf("announced", "voting") }?.let { e ->
                HomeRow("vote", e.text("title"), if (e.text("status") == "voting") "Voting open" else "Candidacy open") { onModule(9) }
            }
        }
    }
}

/** One desk's main job: what waits there and the button to do it, or "Nothing waiting" with a way into the desk. */
@Composable
private fun DeskJobCard(d: DeskTile, focus: OfficerFocus?, onRoute: (String) -> Unit) {
    val route = focus?.route ?: d.route
    RaisedCard(onClick = { onRoute(route) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(if (d.urgent) Club.roseSoft else Club.mint), contentAlignment = Alignment.Center) {
                    ClubIcon(queueIcon(d.route), Modifier.size(17.dp), if (d.urgent) Club.roseInk else Club.forest)
                }
                Text((focus?.eyebrow ?: d.title).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (d.waiting > 0) Box(Modifier.size(24.dp).clip(CircleShape).background(Club.roseInk), contentAlignment = Alignment.Center) {
                    Text("${d.waiting}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = androidx.compose.ui.graphics.Color.White)
                }
            }
            Text(focus?.title ?: "Nothing waiting on ${d.title}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(focus?.detail ?: d.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PillButton(focus?.action ?: "Open ${d.title}", { onRoute(route) })
        }
    }
}

@Composable
private fun DeskTileCard(d: DeskTile, modifier: Modifier, onClick: () -> Unit) {
    RaisedCard(modifier, radius = 18.dp, onClick = onClick) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(if (d.urgent) Club.roseSoft else Club.mint), contentAlignment = Alignment.Center) {
                    ClubIcon(queueIcon(d.route), Modifier.size(19.dp), if (d.urgent) Club.roseInk else Club.forest)
                }
                Spacer(Modifier.weight(1f))
                if (d.waiting > 0) Box(Modifier.size(24.dp).clip(CircleShape).background(Club.roseInk), contentAlignment = Alignment.Center) {
                    Text("${d.waiting}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = androidx.compose.ui.graphics.Color.White)
                }
            }
            Text(d.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
            Text(d.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
