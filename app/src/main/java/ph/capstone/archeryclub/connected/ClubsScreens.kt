package ph.capstone.archeryclub.connected

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.ScreenShell

/* Many clubs, one app (migration 050). Each account belongs to one club; the platform administrator creates clubs. */

/** First screen for a new account when there are several clubs: pick the club at your school, once. */
@Composable
fun ChooseClubScreen(workspace: JsonObject, state: SystemState, vm: SystemViewModel, snackbar: SnackbarHostState) {
    var picked by remember { mutableStateOf<JsonObject?>(null) }
    HeroScaffold("Choose your club", snackbar = snackbar, busy = state.busy,
        heroExtra = { Text("Each account belongs to one archery club. Pick the club at your school.", color = Color.White.copy(alpha = .9f),
            style = MaterialTheme.typography.bodyMedium) }) {
        allClubs(workspace).forEach { c ->
            RaisedCard(onClick = { if (!state.busy) picked = c }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Initials(c.text("short_name").ifBlank { c.text("name") }, 44)
                    Column(Modifier.weight(1f)) {
                        Text(c.text("name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                        if (c.text("school").isNotBlank()) Text(c.text("school"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        if (allClubs(workspace).isEmpty()) EmptyState("No clubs yet", "Ask your club's officers to check that their club is set up.")
        TextButton(onClick = { vm.auth("signout", "", "") }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
    picked?.let { c ->
        ConfirmDialog("Join ${c.text("name")}?", "Your account will belong to this club: its members, activities and officers. You can't switch to another club later.",
            "Join", onConfirm = {
                picked = null
                vm.save("join_club", buildJsonObject { put("p_data", buildJsonObject { put("club_id", c.text("id")) }) })
            }, onDismiss = { picked = null })
    }
}

/** Platform administrator: every club, and a form to create one with its first administrator. */
@Composable
fun PlatformClubsScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, vm: SystemViewModel?) {
    var name by rememberSaveable { mutableStateOf("") }
    var short by rememberSaveable { mutableStateOf("") }
    var school by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var theme by rememberSaveable { mutableStateOf("green") }
    var attempted by remember { mutableStateOf(false) }
    val errors = buildMap {
        if (name.trim().length < 2) put("name", "Enter the club's name.")
        if (!email.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) put("email", "Enter the first administrator's email.")
    }
    ScreenShell("Clubs", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        DeskLabel("${allClubs(workspace).size} club${if (allClubs(workspace).size == 1) "" else "s"}")
        allClubs(workspace).forEach { c ->
            DeskRow(c.text("name"), listOf(c.text("school"), c.text("short_name")).filter { it.isNotBlank() }.joinToString(" · "),
                leading = { Initials(c.text("short_name").ifBlank { c.text("name") }, 36) },
                status = (if (c.text("id") == myClub(workspace)?.text("id")) "Your club · " else "") + c.text("theme").replaceFirstChar { it.uppercase() } to Tone.NEUTRAL)
        }
        DeskLabel("Create a club")
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it.take(160) }, label = { Text("Club name") }, placeholder = { Text("e.g. UP Archery Club") }, singleLine = true,
                isError = attempted && "name" in errors, supportingText = { if (attempted) errors["name"]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(short, { short = it.take(40) }, label = { Text("Short name (optional)") }, placeholder = { Text("e.g. UPAC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(school, { school = it.take(160) }, label = { Text("School") }, placeholder = { Text("e.g. University of the Philippines") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it.trim().take(254) }, label = { Text("First administrator's email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                isError = attempted && "email" in errors,
                supportingText = { Text(if (attempted) errors["email"] ?: "" else "They need an account in the app already, not part of another club.") },
                modifier = Modifier.fillMaxWidth())
            Text("Colour", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("green" to "Green", "red" to "Red", "blue" to "Blue"), theme) { theme = it }
            Text("The club starts with blank rules. Its administrator sets the requirements, fees and categories in Club setup, and assigns its own officers.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CardButton("Create club", Modifier.fillMaxWidth(), primary = true, enabled = !busy) {
                attempted = true
                if (errors.isEmpty()) vm?.save("create_club", buildJsonObject { put("p_data", buildJsonObject {
                    put("name", name.trim()); put("short_name", short.trim()); put("school", school.trim()); put("admin_email", email.trim()); put("theme", theme)
                }) }) { name = ""; short = ""; school = ""; email = ""; theme = "green"; attempted = false }
            }
        } }
    }
}
