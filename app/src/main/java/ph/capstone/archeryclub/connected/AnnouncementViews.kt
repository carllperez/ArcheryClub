package ph.capstone.archeryclub.connected

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club

/** Announcements card for Home and Activities. Unread ones are bold; read ones are plain and gray.
 *  Tapping one opens its message (on Activities it also marks it read; on Home, Mark read does). */
@Composable
fun AnnouncementsCard(workspace: JsonObject, vm: SystemViewModel?, busy: Boolean, limit: Int = 3, onSeeAll: (() -> Unit)? = null,
                      onViewAll: (() -> Unit)? = null) {
    val every = announcementViews(workspace)
    // On Home (onViewAll set) only unread ones show; read ones live in the Announcements tab.
    val all = if (onViewAll != null) every.filter { !it.read } else every
    if (onViewAll != null && all.isEmpty()) {
        Text("ANNOUNCEMENTS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RaisedCard(radius = 18.dp) { Column {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("✓", color = Club.forest, fontWeight = FontWeight.ExtraBold)
                Text("You're all caught up", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            TextButton(onClick = onViewAll, modifier = Modifier.fillMaxWidth()) { Text("View all announcements", fontWeight = FontWeight.ExtraBold) }
        } }
        return
    }
    if (all.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf("") }
    val unread = all.filter { !it.read }
    val shown = if (showAll) all else all.take(limit)
    fun mark(ids: List<String>) {
        if (vm != null && ids.isNotEmpty()) vm.save("mark_announcements_read", buildJsonObject { put("p_ids", JsonArray(ids.map(::JsonPrimitive))) })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (unread.isEmpty()) "ANNOUNCEMENTS" else "ANNOUNCEMENTS · ${unread.size} NEW", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (unread.size > 1) TextButton(onClick = { mark(unread.map { it.id }) }, enabled = !busy) { Text("Mark all read", fontWeight = FontWeight.ExtraBold) }
    }
    RaisedCard(radius = 18.dp) {
        Column(Modifier.animateContentSize()) {
            shown.forEachIndexed { i, a ->
                val expanded = open == a.id
                Column(Modifier.fillMaxWidth().background(if (a.read) Color(0xFFF4F6F3) else Color.Transparent)
                    .clickable { open = if (expanded) "" else a.id; if (onViewAll == null && !a.read && !expanded) mark(listOf(a.id)) }
                    .padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (a.read) FontWeight.Normal else FontWeight.ExtraBold,
                                color = if (a.read) Color(0xFF8A948D) else MaterialTheme.colorScheme.onSurface, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(fmtDay(a.at), a.by, if (a.read) "Read" else "").filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A948D))
                        }
                        if (!a.read) Text("Mark read", Modifier.clip(RoundedCornerShape(999.dp)).clickable(enabled = !busy) { mark(listOf(a.id)) }
                            .padding(horizontal = 6.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = Club.forest)
                    }
                    if (expanded) Text(a.message, style = MaterialTheme.typography.bodyMedium)
                }
                if (i < shown.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
            if (onViewAll != null) TextButton(onClick = onViewAll, modifier = Modifier.fillMaxWidth()) { Text("View all announcements", fontWeight = FontWeight.ExtraBold) }
            else if (all.size > limit) TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAll) "Show fewer" else "See all ${all.size}", fontWeight = FontWeight.ExtraBold) }
            else if (onSeeAll != null) TextButton(onClick = onSeeAll, modifier = Modifier.fillMaxWidth()) { Text("Announcements and attendance records") }
        }
    }
}

/** "You're approved" on a new member's Home; Dismiss marks the notice as read so it doesn't come back. */
@Composable
fun ApprovalCard(notice: JsonObject, busy: Boolean, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Club.mint, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("🎉", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text("Congrats, you're approved!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Club.forestDeep)
                Text("You're now an active member of the club.", style = MaterialTheme.typography.bodySmall, color = Club.forest)
            }
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Dismiss", fontWeight = FontWeight.ExtraBold, color = Club.forestDeep) }
        }
    }
}

/** All announcements, opened from the Home card: Unread and Read. Tap one to read all of it; Mark read moves it to Read. */
@Composable
fun AnnouncementsPage(workspace: JsonObject, vm: SystemViewModel?, state: SystemState, snackbar: SnackbarHostState, bottomBar: @Composable () -> Unit, onBack: (() -> Unit)? = null) {
    val all = announcementViews(workspace)
    val unread = all.filter { !it.read }; val read = all.filter { it.read }
    var tab by rememberSaveable { mutableStateOf("unread") }
    var open by rememberSaveable { mutableStateOf("") }
    fun mark(ids: List<String>) {
        if (vm != null && ids.isNotEmpty()) vm.save("mark_announcements_read", buildJsonObject { put("p_ids", JsonArray(ids.map(::JsonPrimitive))) })
    }
    val shown = if (tab == "unread") unread else read
    // The phone's back gesture returns to Home too, since this page isn't a tab.
    if (onBack != null) androidx.activity.compose.BackHandler(onBack = onBack)
    HeroScaffold("Announcements", onBack, snackbar = snackbar, overlap = 0.dp, busy = state.busy, bottomBar = bottomBar,
        heroExtra = { HeroTabs(listOf("unread" to "Unread · ${unread.size}", "read" to "Read · ${read.size}"), tab) { tab = it } }) {
        Spacer(Modifier.height(4.dp))
        if (tab == "unread" && unread.size > 1) TextButton(onClick = { mark(unread.map { it.id }) }, enabled = !state.busy) { Text("Mark all read", fontWeight = FontWeight.ExtraBold) }
        if (shown.isEmpty()) EmptyState(if (tab == "unread") "You're all caught up" else "Nothing read yet",
            if (tab == "unread") "New announcements from the club appear here." else "Announcements you've opened stay here.")
        shown.forEach { a ->
            val expanded = open == a.id
            RaisedCard(radius = 18.dp, onClick = { open = if (expanded) "" else a.id }) {
                Column(Modifier.fillMaxWidth().animateContentSize().background(if (a.read) Color(0xFFF4F6F3) else Color.Transparent).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (a.read) FontWeight.Normal else FontWeight.ExtraBold,
                                color = if (a.read) Color(0xFF6B746E) else MaterialTheme.colorScheme.onSurface)
                            Text(listOf(fmtDay(a.at), a.by).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A948D))
                        }
                    }
                    Text(a.message, style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    if (!a.read) TextButton(onClick = { mark(listOf(a.id)) }, enabled = !state.busy, modifier = Modifier.align(Alignment.End)) {
                        Text("Mark read", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}
