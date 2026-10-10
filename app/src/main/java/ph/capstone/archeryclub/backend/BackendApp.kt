package ph.capstone.archeryclub.backend

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ph.capstone.archeryclub.ui.ClubApp
import ph.capstone.archeryclub.ui.ClubViewModel
import ph.capstone.archeryclub.ui.InfoCard
import ph.capstone.archeryclub.ui.ScreenShell
import ph.capstone.archeryclub.ui.StatusBadge

@Composable
fun BackendApp(activity: ComponentActivity) {
    val repository = remember { SupabaseClubRepository() }
    var signedIn by remember { mutableStateOf(repository.isSignedIn) }
    if (signedIn) {
        val viewModel = remember(repository) {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ClubViewModel(repository) as T
            }
            ViewModelProvider(activity, factory)[ClubViewModel::class.java]
        }
        LaunchedEffect(Unit) { runCatching { repository.refresh() } }
        ClubApp(viewModel, showPreviewNotice = false)
    } else {
        BackendLoginScreen(repository, onSignedIn = { signedIn = true })
    }
}

@Composable
private fun BackendLoginScreen(repository: SupabaseClubRepository, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var studentNumber by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var signUpMode by remember { mutableStateOf(false) }

    ScreenShell("Member sign in") {
        StatusBadge("SUPABASE BACKEND")
        Text("Activities, attendance, training and dashboard data are loaded from the club database.")
        error?.let { InfoCard("Sign-in error", it) }
        message?.let { InfoCard("Message", it) }
        if (signUpMode) {
            OutlinedTextField(fullName, { fullName = it }, Modifier.fillMaxWidth(), label = { Text("Full name") })
            OutlinedTextField(studentNumber, { studentNumber = it }, Modifier.fillMaxWidth(), label = { Text("Student number") })
            OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Phone") })
        }
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") })
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !creating && email.isNotBlank() && password.length >= 6,
                onClick = {
                    creating = true; error = null; message = null
                    scope.launch {
                        runCatching {
                            if (signUpMode) repository.signUp(email, password, fullName, studentNumber, phone)
                            else repository.signIn(email, password)
                        }.onSuccess {
                            if (repository.isSignedIn) {
                                onSignedIn()
                            } else {
                                message = "Account created. Check your email if confirmation is enabled, then sign in."
                            }
                        }.onFailure { error = it.message ?: "Request failed." }
                        creating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (signUpMode) "Create account" else "Sign in") }
            OutlinedButton(
                enabled = !creating,
                onClick = { signUpMode = !signUpMode; error = null; message = null },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (signUpMode) "I already have an account" else "Create a member account") }
        }
    }
}
