package ph.capstone.archeryclub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ph.capstone.archeryclub.domain.*

@Composable
fun ActivitiesScreen(
    activities: List<ClubActivity>, busy: Boolean, snackbar: SnackbarHostState,
    onBack: () -> Unit, onRegister: (String, Boolean) -> Unit, showPreviewNotice: Boolean = true,
) {
    ScreenShell("Club activities", onBack, snackbar) {
        if (showPreviewNotice) PreviewNotice()
        Text("Announcements, schedules, deadlines, and participation", style = MaterialTheme.typography.bodyLarge)
        if (activities.isEmpty()) InfoCard("No activities", "There are no published activities yet.")
        activities.forEach { activity -> ActivityCard(activity, busy, onRegister) }
    }
}

@Composable
private fun ActivityCard(activity: ClubActivity, busy: Boolean, onRegister: (String, Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(activity.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (activity.registered) StatusBadge("Registered")
            }
            Text("${activity.date} · ${activity.time}", fontWeight = FontWeight.Medium)
            Text(activity.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(activity.description)
            Text("Registration deadline: ${activity.registrationDeadline}", style = MaterialTheme.typography.bodySmall)
            if (!activity.eligible) {
                StatusBadge("Not eligible")
            } else {
                if (activity.registered) {
                    OutlinedButton(onClick = { onRegister(activity.id, false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel registration") }
                } else {
                    Button(onClick = { onRegister(activity.id, true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Register for activity") }
                }
            }
        }
    }
}

@Composable
fun AttendanceScreen(
    attendance: List<AttendanceRecord>, onBack: () -> Unit, snackbar: SnackbarHostState, showPreviewNotice: Boolean = true,
) {
    ScreenShell("My attendance", onBack, snackbar) {
        if (showPreviewNotice) PreviewNotice()
        val present = attendance.count { it.status == AttendanceStatus.PRESENT }
        val counted = attendance.count { it.status != AttendanceStatus.EXCUSED }
        val percentage = if (counted == 0) 0 else (present * 100 / counted)
        val total = attendance.size
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Attendance", "$percentage%", Modifier.weight(1f))
            MetricCard("Present", "$present / $total", Modifier.weight(1f))
        }
        Text("Official attendance records are read-only.", style = MaterialTheme.typography.bodySmall)
        attendance.forEach { record ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(record.activityTitle, fontWeight = FontWeight.SemiBold)
                        Text(record.date, style = MaterialTheme.typography.bodySmall)
                        record.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    StatusBadge(record.status.label)
                }
            }
        }
        if (attendance.isEmpty()) InfoCard("No attendance yet", "Your official attendance history will appear here after the club records an activity.")
    }
}

@Composable
fun TrainingScreen(training: List<TrainingRecord>, onBack: () -> Unit, snackbar: SnackbarHostState, showPreviewNotice: Boolean = true) {
    ScreenShell("Training records", onBack, snackbar) {
        if (showPreviewNotice) PreviewNotice()
        val scores = training.mapNotNull { it.score }
        val average = if (scores.isEmpty()) 0 else scores.average().toInt()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Sessions", training.size.toString(), Modifier.weight(1f))
            MetricCard("Avg. score", if (scores.isEmpty()) "—" else "$average", Modifier.weight(1f))
        }
        Text("Coach feedback and official results are read-only.", style = MaterialTheme.typography.bodySmall)
        training.forEach { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(record.trainingType, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(record.date, style = MaterialTheme.typography.bodySmall)
                        }
                        record.score?.let { StatusBadge("Score $it") }
                    }
                    Text("Focus: ${record.focus}")
                    if (record.coach.isNotBlank()) Text("Coach: ${record.coach}", style = MaterialTheme.typography.bodySmall)
                    if (record.feedback.isNotBlank()) InfoCard("Coach feedback", record.feedback)
                }
            }
        }
        if (training.isEmpty()) InfoCard("No training records", "Official training sessions and coach feedback will appear here when available.")
    }
}

@Composable
fun MemberDashboard(
    member: MemberProfile, activities: List<ClubActivity>, attendance: List<AttendanceRecord>, training: List<TrainingRecord>,
    busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit,
    onProfile: () -> Unit, onRenew: () -> Unit, onActivities: () -> Unit, onAttendance: () -> Unit, onTraining: () -> Unit,
    showPreviewNotice: Boolean = true,
) {
    var confirmRenewal by rememberSaveable { mutableStateOf(false) }
    val present = attendance.count { it.status == AttendanceStatus.PRESENT }
    val counted = attendance.count { it.status != AttendanceStatus.EXCUSED }
    val attendancePercent = if (counted == 0) 0 else present * 100 / counted
    val upcoming = activities.filter { !it.registered }.take(2)
    ScreenShell("Hello, ${member.fullName.substringBefore(' ')}.", onBack, snackbar) {
        if (showPreviewNotice) PreviewNotice()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("YOUR MEMBERSHIP", style = MaterialTheme.typography.labelMedium)
                Text(member.status, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${member.category} · ${member.studentNumber}")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Attendance", "$attendancePercent%", Modifier.weight(1f))
            MetricCard("Training", training.size.toString(), Modifier.weight(1f))
            MetricCard("Activities", activities.count { it.registered }.toString(), Modifier.weight(1f))
        }
        Text("Your records", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onActivities, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("View activities") }
        OutlinedButton(onClick = onAttendance, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("View attendance") }
        OutlinedButton(onClick = onTraining, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("View training records") }
        if (upcoming.isNotEmpty()) {
            Text("Upcoming activities", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            upcoming.forEach { InfoCard(it.title, "${it.date} · ${it.time}\n${it.location}") }
        }
        Text("Account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onProfile, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("View & edit my profile") }
        if (member.renewalPending) {
            InfoCard("Renewal pending", "Your request is waiting for club review. Your membership status has not changed.")
        } else {
            Button(onClick = { confirmRenewal = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Request renewal") }
        }
    }
    if (confirmRenewal) AlertDialog(onDismissRequest = { confirmRenewal = false },
        title = { Text("Request membership renewal?") },
        text = { Text(if (showPreviewNotice) "This saves a sample request on this device. It does not change your membership status." else "This sends a renewal request to the club. Your membership status will remain unchanged until club review.") },
        confirmButton = { TextButton(onClick = { confirmRenewal = false; onRenew() }) { Text("Save request") } },
        dismissButton = { TextButton(onClick = { confirmRenewal = false }) { Text("Cancel") } })
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}
