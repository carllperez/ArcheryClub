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

/** Announcements card for Home and Activities. Unread ones have a red dot and bold title; read ones stay, in gray.
 *  Tapping one opens its message and marks it read. */
@Composable
fun AnnouncementsCard(workspace: JsonObject, vm: SystemViewModel?, busy: Boolean, limit: Int = 3, onSeeAll: (() -> Unit)? = null) {
    val all = announcementViews(workspace)
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
                    .clickable { open = if (expanded) "" else a.id; if (!a.read && !expanded) mark(listOf(a.id)) }
                    .padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (a.read) Color(0xFFC9D1CB) else Club.roseInk))
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (a.read) FontWeight.SemiBold else FontWeight.ExtraBold,
                                color = if (a.read) Color(0xFF8A948D) else MaterialTheme.colorScheme.onSurface, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(fmtDay(a.at), a.by, if (a.read) "Read" else "").filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A948D))
                        }
                        if (!a.read) Text("Mark read", Modifier.clip(RoundedCornerShape(999.dp)).clickable(enabled = !busy) { mark(listOf(a.id)) }
                            .padding(horizontal = 6.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = Club.forest)
                    }
                    if (expanded) Text(a.message, Modifier.padding(start = 19.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (i < shown.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
            if (all.size > limit) TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
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

/** The Announcements tab: Unread and Read. Tap one to read all of it; Mark read moves it to Read. */
@Composable
fun AnnouncementsPage(workspace: JsonObject, vm: SystemViewModel?, state: SystemState, snackbar: SnackbarHostState, bottomBar: @Composable () -> Unit) {
    val all = announcementViews(workspace)
    val unread = all.filter { !it.read }; val read = all.filter { it.read }
    var tab by rememberSaveable { mutableStateOf("unread") }
    var open by rememberSaveable { mutableStateOf("") }
    fun mark(ids: List<String>) {
        if (vm != null && ids.isNotEmpty()) vm.save("mark_announcements_read", buildJsonObject { put("p_ids", JsonArray(ids.map(::JsonPrimitive))) })
    }
    val shown = if (tab == "unread") unread else read
    HeroScaffold("Announcements", snackbar = snackbar, overlap = 0.dp, busy = state.busy, bottomBar = bottomBar,
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
                        Box(Modifier.size(9.dp).clip(CircleShape).background(if (a.read) Color(0xFFC9D1CB) else Club.roseInk))
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (a.read) FontWeight.SemiBold else FontWeight.ExtraBold,
                                color = if (a.read) Color(0xFF6B746E) else MaterialTheme.colorScheme.onSurface)
                            Text(listOf(fmtDay(a.at), a.by).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A948D))
                        }
                    }
                    Text(a.message, Modifier.padding(start = 19.dp), style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    if (!a.read) TextButton(onClick = { mark(listOf(a.id)) }, enabled = !state.busy, modifier = Modifier.align(Alignment.End)) {
                        Text("Mark read", fontWeight = FontWeight.ExtraBold) }
                }
            }
        }
    }
}
