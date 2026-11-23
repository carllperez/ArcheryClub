package ph.capstone.archeryclub.connected

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.Instant

/* Attendance desk (migration 062): who came to what. Read-only; attendance is still taken on each activity. */

private fun shareCsv(context: android.content.Context, title: String, csv: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, csv)
    }, "Export attendance"))
}

private fun statusTone(s: String) = when (s) { "present" -> Tone.POSITIVE; "excused" -> Tone.INFO; "absent" -> Tone.DANGER; else -> Tone.WARNING }

@Composable
fun AttendanceScreen(workspace: JsonObject, snackbar: SnackbarHostState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val now = Instant.now()
    var view by rememberSaveable { mutableStateOf("activities") }
    var kind by rememberSaveable { mutableStateOf("all") }
    val k = kind.takeIf { it != "all" }
    val events = attendanceEvents(workspace, k)
    val members = memberAttendance(workspace, k)
    val streaks = members.filter { it.missedInARow >= 3 }
    val noShows = events.flatMap { e -> e.noShows(now).map { e to it } }
    ScreenShell("Attendance", onBack, snackbar, actions = {
        TextButton(onClick = {
            if (view == "activities") shareCsv(context, "Attendance by activity", attendanceCsv(events))
            else shareCsv(context, "Attendance by member", memberAttendanceCsv(members))
        }, enabled = events.isNotEmpty()) { Text("Export", fontWeight = FontWeight.ExtraBold) }
    }) {
        ChoiceChips(listOf("activities" to "By activity", "members" to "By member"), view) { view = it }
        ChoiceChips(listOf("all" to "All") + ACTIVITY_KINDS, kind) { kind = it }
        // The two things worth acting on, before the lists.
        if (streaks.isNotEmpty()) NoticeCard("Missed 3 in a row · ${streaks.size}",
            streaks.take(4).joinToString(", ") { it.name } + (if (streaks.size > 4) " and ${streaks.size - 4} more" else "") +
                ". Worth a message from an officer.", Tone.WARNING)
        if (noShows.isNotEmpty()) NoticeCard("No-shows · ${noShows.size}",
            "Registered but didn't check in: " + noShows.take(3).joinToString("; ") { (e, r) -> "${r.name} (${e.title})" } +
                (if (noShows.size > 3) " and ${noShows.size - 3} more." else "."), Tone.INFO)
        when {
            events.isEmpty() -> EmptyState("No activities yet", "Activities appear here once they start. Take attendance on the activity itself (QR code or manual).")
            view == "activities" -> events.forEach { e ->
                val rate = if (e.expected == 0) null else e.present * 100 / e.expected
                val bar: (@Composable ColumnScope.() -> Unit)? = if (e.taken && e.expected > 0) { { GoalBar(e.present / e.expected.toFloat(), (rate ?: 0) < 50) } } else null
                DeskRow(e.title, "${kindLabel(e.kind)} · ${fmtDay(e.start)} · " +
                        if (!e.taken) "attendance not taken" else "${e.present} of ${e.expected} came" + rate?.let { " · $it%" }.orEmpty(),
                    leading = { IconChip(if (e.kind == "training") "target" else if (e.kind == "meeting") "users" else "calendar", if (e.taken) Tone.POSITIVE else Tone.NEUTRAL) },
                    status = if (!e.taken) "Not taken" to Tone.WARNING else null,
                    onClick = { onOpen("attendance_event:${e.id}") },
                    footer = bar)
            }
            else -> members.forEach { m ->
                DeskRow(m.name, if (m.counted == 0) "No activity with attendance since joining"
                        else "${m.attended} of ${m.counted} attended · last ${m.last?.let { fmtDay(it) } ?: "never"}",
                    leading = { Initials(m.name, 40) },
                    status = m.rate?.let { "$it%" to if (m.missedInARow >= 3) Tone.DANGER else if (it >= 75) Tone.POSITIVE else Tone.WARNING },
                    onClick = { onOpen("attendance_member:${m.userId}") })
            }
        }
        Text("Excused absences don't count against anyone. Attendance is taken on each activity, by QR code or by hand.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AttendanceEventScreen(workspace: JsonObject, eventId: String, snackbar: SnackbarHostState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val e = attendanceEvents(workspace).find { it.id == eventId }
    ScreenShell(e?.title ?: "Attendance", onBack, snackbar, actions = {
        if (e != null) TextButton(onClick = { shareCsv(context, "Attendance: ${e.title}", attendanceCsv(listOf(e))) }) { Text("Export", fontWeight = FontWeight.ExtraBold) }
    }) {
        if (e == null) { EmptyState("Activity not found", "It may have been archived."); return@ScreenShell }
        Text("${kindLabel(e.kind)} · ${fmtDateTime(e.start)} · ${e.venue}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RaisedCard { Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            listOf("${e.present}" to "Present", "${e.absent}" to "Absent", "${e.excused}" to "Excused", "${e.noRecord}" to "No record").forEach { (v, l) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(v, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
        if (!e.taken) NoticeCard("Attendance not taken", "Open the activity from the Events desk to check people in by QR code or by hand.", Tone.WARNING)
        DeskLabel("Roster · ${e.roster.size}")
        if (e.roster.isEmpty()) EmptyState("Nobody registered", "Walk-ins checked in by hand appear here too.")
        e.roster.sortedWith(compareBy({ listOf("present", "excused", "absent", "").indexOf(it.status) }, { it.name })).forEach { r ->
            DeskRow(r.name, buildList {
                    add(if (r.registered) "Registered" else "Walk-in")
                    if (r.status.isNotBlank()) add((if (r.method == "qr") "QR check-in" else "Recorded by hand") + if (r.by.isNotBlank()) " by ${r.by}" else "")
                }.joinToString(" · "),
                leading = { Initials(r.name, 40) }, status = statusLabel(r.status) to statusTone(r.status),
                onClick = { onOpen("attendance_member:${r.userId}") })
        }
    }
}

@Composable
fun AttendanceMemberScreen(workspace: JsonObject, userId: String, snackbar: SnackbarHostState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    var kind by rememberSaveable { mutableStateOf("all") }
    val m = memberAttendance(workspace, kind.takeIf { it != "all" }).find { it.userId == userId }
    ScreenShell(m?.name ?: "Attendance", onBack, snackbar) {
        if (m == null) { EmptyState("Not an Active member", "Only Active members have an attendance history here."); return@ScreenShell }
        ChoiceChips(listOf("all" to "All") + ACTIVITY_KINDS, kind) { kind = it }
        RaisedCard { Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            listOf((m.rate?.let { "$it%" } ?: "—") to "Rate", "${m.attended}/${m.counted}" to "Attended", "${m.missedInARow}" to "Missed in a row").forEach { (v, l) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(v, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold,
                        color = if (l == "Missed in a row" && m.missedInARow >= 3) Club.roseInk else MaterialTheme.colorScheme.onSurface)
                    Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
        DeskLabel("Activities since joining · ${m.timeline.size}")
        if (m.timeline.isEmpty()) EmptyState("Nothing yet", "Activities where attendance was taken appear here.")
        m.timeline.forEach { (e, s) ->
            DeskRow(e.title, "${kindLabel(e.kind)} · ${fmtDay(e.start)}", leading = { IconChip(if (e.kind == "training") "target" else "calendar", statusTone(s)) },
                status = statusLabel(s) to statusTone(s), onClick = { onOpen("attendance_event:${e.id}") })
        }
    }
}
