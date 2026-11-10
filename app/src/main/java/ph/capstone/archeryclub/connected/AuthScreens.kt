package ph.capstone.archeryclub.connected

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.capstone.archeryclub.BuildConfig
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ClubIcon

/* Signed-out screens: sign in, create account, and the loading/connection splash. */

/** White target badge used as the app mark on green screens. */
@Composable
fun ClubMark(size: Int = 72, pulse: Boolean = false) {
    val scale = if (pulse) {
        val t = rememberInfiniteTransition(label = "mark")
        t.animateFloat(1f, 1.06f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    Box(Modifier.size(size.dp).scale(scale).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        ClubIcon("target", Modifier.size((size * .56f).dp), Club.forestDeep)
    }
}

/** Full-screen green splash while the club workspace loads, or when it can't be reached. */
@Composable
fun ClubSplash(busy: Boolean, message: String?, onRetry: () -> Unit, onSignOut: () -> Unit) {
    LightStatusBarIcons()
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Club.forestDeep, Club.forest))), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ClubMark(84, pulse = busy)
            Text(if (busy) "Loading your club" else "Can't reach your club", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            if (busy) LinearProgressIndicator(Modifier.width(140.dp).clip(CircleShape), color = Club.lime, trackColor = Color.White.copy(alpha = .2f))
            else {
                Text(message ?: "Check your connection and retry. If access has changed, sign in again or contact your club administrator.",
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .9f), textAlign = TextAlign.Center)
                Button(onClick = onRetry, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Club.forestDeep),
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again", fontWeight = FontWeight.ExtraBold) }
                TextButton(onClick = onSignOut) { Text("Sign out", color = Color.White) }
            }
        }
    }
}

@Composable
fun ClubAuthScreen(state: SystemState, vm: SystemViewModel, snackbar: SnackbarHostState) {
    val canSignUp = BuildConfig.EMAIL_ENABLED || BuildConfig.SIGNUP_ENABLED
    var creating by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showCodes by remember { mutableStateOf(false) }
    var attempted by remember(creating) { mutableStateOf(false) }
    val errors = if (creating) signUpErrors(email, password, confirm) else emptyMap()
    fun err(key: String) = if (attempted) errors[key] else null
    androidx.activity.compose.BackHandler(creating) { creating = false }
    LightStatusBarIcons()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).imePadding()) {
            HeroBand(bottomPadding = 104.dp) {
                Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClubMark()
                    Text("DLSU ARCHERY CLUB", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.6.sp, color = Color.White)
                    AnimatedContent(creating, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "auth-title") { c ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (c) "Create your account" else "Welcome back", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                            Text(if (c) "Step 1 of 2 · then your membership application" else "Your club, in one place.",
                                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .9f), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            Column(Modifier.offset(y = (-80).dp).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RaisedCard(radius = 24.dp) {
                    Column(Modifier.padding(20.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AuthField("Email", email, { email = it }, KeyboardType.Email, err("email"),
                            if (creating) "Use your DLSU email so officers can recognise you." else "")
                        AuthField("Password", password, { password = it }, KeyboardType.Password, err("password"),
                            if (creating) "At least 8 characters, with letters and a number." else "", secret = true)
                        AnimatedVisibility(creating) {
                            AuthField("Confirm password", confirm, { confirm = it }, KeyboardType.Password, err("confirm"), "", secret = true)
                        }
                        state.error?.let { NoticeCard("Couldn't continue", it, Tone.DANGER) }
                        state.message?.let { NoticeCard("Check this", it, Tone.INFO) }
                        if (creating) PrimaryAction(if (state.busy) "Creating…" else "Create account", !state.busy) {
                            attempted = true; if (errors.isEmpty()) vm.auth("signup", email, password)
                        } else PrimaryAction(if (state.busy) "Signing in…" else "Sign in", !state.busy && email.isNotBlank() && password.isNotBlank()) {
                            vm.auth("signin", email, password)
                        }
                    }
                }
                if (creating) {
                    Text("An account doesn't make you a member yet. Club officers review every application.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { creating = false }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("I already have an account · Sign in") }
                } else {
                    if (canSignUp) SecondaryAction("New applicant? Create an account", !state.busy) { creating = true; vm.clearMessage() }
                    if (BuildConfig.EMAIL_ENABLED) {
                        TextButton(onClick = { vm.auth("recover", email, "") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Forgot password") }
                        TextButton(onClick = { showCodes = !showCodes }, modifier = Modifier.fillMaxWidth()) { Text(if (showCodes) "Hide codes" else "I have a code from an email") }
                        AnimatedVisibility(showCodes) {
                            RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                AuthField("Verification, recovery or invitation code", code, { code = it }, KeyboardType.Text, null, "")
                                SecondaryAction("Verify email code", !state.busy) { vm.auth("verify", email, "", code) }
                                SecondaryAction("Accept officer invitation", !state.busy) { vm.auth("verify_invite", email, "", code) }
                            } }
                        }
                    } else if (!canSignUp) Text("Use an existing club account. Sign-up, password recovery and invitations are not enabled yet.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AuthField(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType, error: String?, hint: String, secret: Boolean = false) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, isError = error != null,
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) ({ TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") } }) else null,
        supportingText = (error ?: hint.takeIf { it.isNotBlank() })?.let { message -> @Composable { Text(message) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
}

/** Set a new password after following a recovery link. */
@Composable
fun PasswordChangeCard(vm: SystemViewModel, busy: Boolean) {
    var password by remember { mutableStateOf("") }
    RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Set a new password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        AuthField("New password", password, { password = it }, KeyboardType.Password, null, "At least 8 characters.", secret = true)
        PrimaryAction("Save new password", !busy && password.length >= 8) { vm.auth("password", "", password) }
    } }
}
