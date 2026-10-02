package ph.capstone.archeryclub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ClubApp(viewModel: ClubViewModel, showPreviewNotice: Boolean = true) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val actions by viewModel.actions.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(AppScreen.WELCOME) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(actions.message, actions.error) {
        val text = actions.error ?: actions.message
        if (text != null) { snackbar.showSnackbar(text); viewModel.clearMessage() }
    }
    when (screen) {
        AppScreen.APPLICATION -> {
            BackHandler { if (!actions.busy) screen = AppScreen.WELCOME }
            ApplicationScreen(
                snapshot.application, actions.busy, snackbar,
                onBack = { screen = AppScreen.WELCOME }, onSave = viewModel::saveDraft,
                onSubmit = viewModel::submit, onError = viewModel::reportError
            )
        }
        AppScreen.PROFILE -> ProfileScreen(
            snapshot.member, actions.busy, snackbar,
            onBack = { screen = AppScreen.DASHBOARD }, onSave = viewModel::updateProfile
        )
        AppScreen.ACTIVITIES -> ActivitiesScreen(
            snapshot.activities, actions.busy, snackbar,
            onBack = { screen = AppScreen.DASHBOARD }, onRegister = viewModel::setActivityRegistration, showPreviewNotice = showPreviewNotice
        )
        AppScreen.ATTENDANCE -> AttendanceScreen(
            snapshot.attendance, onBack = { screen = AppScreen.DASHBOARD }, snackbar = snackbar, showPreviewNotice = showPreviewNotice
        )
        AppScreen.TRAINING -> TrainingScreen(
            snapshot.training, onBack = { screen = AppScreen.DASHBOARD }, snackbar = snackbar, showPreviewNotice = showPreviewNotice
        )
        AppScreen.ANNOUNCEMENTS -> AnnouncementsScreen(
            snapshot.announcements, onBack = { screen = AppScreen.DASHBOARD }, snackbar = snackbar, showPreviewNotice = showPreviewNotice
        )
        AppScreen.DASHBOARD -> {
            LaunchedEffect(Unit) {
                viewModel.refresh()
            }
            BackHandler { if (!actions.busy) screen = AppScreen.WELCOME }
            MemberDashboard(
                snapshot.member, snapshot.activities, snapshot.attendance, snapshot.training, snapshot.announcements, actions.busy, snackbar,
                onBack = { if (!actions.busy) screen = AppScreen.WELCOME },
                onProfile = { screen = AppScreen.PROFILE }, onRenew = viewModel::renew,
                onActivities = { screen = AppScreen.ACTIVITIES }, onAttendance = { screen = AppScreen.ATTENDANCE },
                onTraining = { screen = AppScreen.TRAINING }, onAnnouncements = { screen = AppScreen.ANNOUNCEMENTS },
                showPreviewNotice = showPreviewNotice
            )
        }
        else -> WelcomeScreen(onApplicant = { screen = AppScreen.APPLICATION }, onMember = { screen = AppScreen.DASHBOARD })
    }
}

@Composable
private fun WelcomeScreen(onApplicant: () -> Unit, onMember: () -> Unit) {
    ScreenShell("Your club.\nOne place.") {
        TargetMark()
        Text("A home for your archery journey.", style = MaterialTheme.typography.titleLarge)
        Text("Applications, membership, activities, attendance, training, and personal records in one member experience.")
        PreviewNotice()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New to the club?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Try the application form and see how submission status works.")
                Button(onClick = onApplicant, modifier = Modifier.fillMaxWidth()) { Text("Explore applicant preview") }
            }
        }
        OutlinedButton(onClick = onMember, modifier = Modifier.fillMaxWidth()) { Text("Explore sample member dashboard") }
        Text("Development milestones 01–04 · Applicant, membership, activities & member records",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
