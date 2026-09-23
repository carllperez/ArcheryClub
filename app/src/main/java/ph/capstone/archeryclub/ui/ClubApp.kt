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
import ph.capstone.archeryclub.domain.MemberProfile

@Composable
fun ClubApp(viewModel: ClubViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val actions by viewModel.actions.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("welcome") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(actions.message, actions.error) {
        val text = actions.error ?: actions.message
        if (text != null) { snackbar.showSnackbar(text); viewModel.clearMessage() }
    }
    when (screen) {
        "application" -> ApplicationScreen(snapshot.application, actions.busy, snackbar,
            onBack = { screen = "welcome" }, onSave = viewModel::saveDraft,
            onSubmit = viewModel::submit, onError = viewModel::reportError)
        "profile" -> ProfileScreen(snapshot.member, actions.busy, snackbar,
            onBack = { screen = "dashboard" }, onSave = viewModel::updateProfile)
        "dashboard" -> {
            BackHandler { if (!actions.busy) screen = "welcome" }
            Dashboard(snapshot.member, actions.busy, snackbar,
                onBack = { if (!actions.busy) screen = "welcome" },
                onProfile = { screen = "profile" }, onRenew = viewModel::renew)
        }
        else -> WelcomeScreen(onApplicant = { screen = "application" }, onMember = { screen = "dashboard" })
    }
}

@Composable
private fun WelcomeScreen(onApplicant: () -> Unit, onMember: () -> Unit) {
    ScreenShell("Your club.\nOne place.") {
        TargetMark()
        Text("A home for your archery journey.", style = MaterialTheme.typography.titleLarge)
        Text("Start an application, keep your profile up to date, and follow your membership.")
        PreviewNotice()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New to the club?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("Try the application form and see how submission status works.")
                Button(onClick = onApplicant, modifier = Modifier.fillMaxWidth()) { Text("Explore applicant preview") }
            }
        }
        OutlinedButton(onClick = onMember, modifier = Modifier.fillMaxWidth()) { Text("Explore sample member") }
        Text("Development milestone 01 · Applicant & membership foundation",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Dashboard(member: MemberProfile, busy: Boolean, snackbar: SnackbarHostState,
    onBack: () -> Unit, onProfile: () -> Unit, onRenew: () -> Unit) {
    var confirmRenewal by rememberSaveable { mutableStateOf(false) }
    ScreenShell("Hello, ${member.fullName.substringBefore(' ')}.", onBack, snackbar) {
        PreviewNotice()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("YOUR MEMBERSHIP", style = MaterialTheme.typography.labelMedium)
                Text(member.status, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${member.category} · ${member.studentNumber}")
                Text("Sample membership record", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Your next steps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onProfile, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("View & edit my profile") }
        if (member.renewalPending) {
            InfoCard("Renewal pending", "Your sample request is waiting for review. Requesting renewal does not change your membership status.")
        } else {
            InfoCard("Membership renewal", "Submit a request when it is time to renew. The club confirms your membership standing.")
            Button(onClick = { confirmRenewal = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Request renewal") }
        }
        InfoCard("Your records will appear here", "Activities, attendance, training, and operational requests will be connected in later development milestones. No official records are connected yet.")
    }
    if (confirmRenewal) AlertDialog(onDismissRequest = { confirmRenewal = false },
        title = { Text("Request membership renewal?") },
        text = { Text("This saves a sample request on this device. No request will be sent to a club.") },
        confirmButton = { TextButton(onClick = { confirmRenewal = false; onRenew() }) { Text("Save preview request") } },
        dismissButton = { TextButton(onClick = { confirmRenewal = false }) { Text("Cancel") } })
}
