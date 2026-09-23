package ph.capstone.archeryclub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import ph.capstone.archeryclub.domain.ApplicationForm
import ph.capstone.archeryclub.domain.MemberProfile

@Composable
fun ProfileScreen(member: MemberProfile, busy: Boolean, snackbar: SnackbarHostState,
    onBack: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(member.fullName) }
    var phone by rememberSaveable { mutableStateOf(member.phone) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val errors = if (attempted) ApplicationForm(name, member.email, phone, confirmed = true).errors() else emptyMap()
    val leave: () -> Unit = {
        if (!busy) {
            if (name.trim() != member.fullName || phone.trim() != member.phone) discard = true else onBack()
        }
    }
    BackHandler(onBack = leave)
    ScreenShell("My profile", leave, snackbar) {
        PreviewNotice()
        StatusBadge(member.status)
        InfoCard("Membership details", "${member.studentNumber}\n${member.category}\n${member.email}")
        Text("You can update your name and contact number. Official membership details are maintained by the club.")
        FormField("Full name", name, { name = it.take(120) }, errors["name"], enabled = !busy)
        FormField("Contact number (optional)", phone, { phone = it.take(20) }, errors["phone"], KeyboardType.Phone, !busy)
        Button(onClick = {
            attempted = true
            if (ApplicationForm(name, member.email, phone, confirmed = true).errors().isEmpty()) onSave(name, phone)
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Saving…" else "Save profile") }
    }
    if (discard) DiscardDialog(onDismiss = { discard = false }, onDiscard = { discard = false; onBack() })
}
