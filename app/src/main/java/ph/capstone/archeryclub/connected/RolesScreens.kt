package ph.capstone.archeryclub.connected

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ClubIcon
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.Instant

/* Administration -> Roles & access. Each club decides which desks a role opens.
 * The server stores the ticked desks on the role and checks them on every action (migration 026). */

@Composable
private fun DeskChip(text: String) {
    Text(text, Modifier.clip(RoundedCornerShape(999.dp)).background(Club.mint).padding(horizontal = 9.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = Club.forestDeep)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RolesScreen(workspace: JsonObject, busy: Boolean, snackbar: SnackbarHostState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val roles = roleViews(workspace, Instant.now())
    ScreenShell("Roles & access", onBack, snackbar) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Each role unlocks the desks you tick. Every officer is also a member, so they keep their own Home, training and payments.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DeskLabel("Roles", "+ New role") { onOpen("new") }
        RowGroup {
            roles.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                            Text(when (r.holders.size) { 0 -> "Nobody holds this role"; 1 -> r.holders.first(); else -> "${r.holders.size} people" } +
                                if (r.changedFromDefault) " · changed by the club" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (r.permissions.isEmpty()) Text("No desks: member pages only", style = MaterialTheme.typography.bodySmall, color = Club.amberInk)
                        else FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            r.permissions.forEach { DeskChip(deskTitle(it)) }
                        }
                    }
                    ClubIcon("arrow", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (i < roles.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        }
    }
}

@Composable
fun RoleEditScreen(workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit) {
    val roles = roleViews(workspace, Instant.now())
    val role = roles.find { it.id == id }
    val isNew = role == null
    if (vm == null || (role == null && id != "new")) { ScreenShell("Role", onBack, snackbar) { EmptyState("This page is unavailable", "Go back and try again.") }; return }
    var name by rememberSaveable(id) { mutableStateOf(role?.name.orEmpty()) }
    // Saved as one comma-separated string so it survives rotation.
    var picked by rememberSaveable(id) { mutableStateOf(role?.permissions.orEmpty().joinToString(",")) }
    val chosen = picked.split(',').filter { it.isNotBlank() }.toSet()
    var confirming by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    val change = roleChange(role?.permissions.orEmpty(), chosen)
    val renamed = name.trim() != role?.name.orEmpty()
    val nameTaken = roles.any { it.id != id && it.name.equals(name.trim(), ignoreCase = true) }
    val warnings = roleWarnings(roles, id, chosen)
    val canSave = !busy && name.trim().length in 2..60 && !nameTaken && (isNew || renamed || !change.none)

    fun save() {
        val roleId = role?.id ?: roleIdFrom(name, roles.map { it.id }.toSet())
        vm.save("save_role", buildJsonObject {
            put("p_role_id", roleId); put("p_name", name.trim()); put("p_new", isNew)
            put("p_permissions", JsonArray(deskOrder(chosen).map(::JsonPrimitive)))
        }) { confirming = false; onBack() }
    }

    ScreenShell(role?.name ?: "New role", onBack, snackbar,
        actions = { if (role?.changedFromDefault == true) TextButton(onClick = { resetting = true }, enabled = !busy) { Text("Reset", fontWeight = FontWeight.ExtraBold) } },
        stickyAction = {
            Button(onClick = { if (isNew || (role!!.holders.isEmpty() && warnings.isEmpty())) save() else confirming = true }, enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) {
                Text(if (isNew) "Create role" else "Save changes", fontWeight = FontWeight.ExtraBold)
            }
        }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RaisedCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Role name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = nameTaken, supportingText = { if (nameTaken) Text("Another role already has this name.") })
            if (role != null) Text(if (role.holders.isEmpty()) "Nobody holds this role right now." else "Held by ${role.holders.joinToString(", ")}. Changes reach them on their next refresh.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        DeskLabel("What this role can open")
        RowGroup {
            DESKS.forEachIndexed { i, d ->
                val on = d.permission in chosen
                val toggle = { picked = (if (on) chosen - d.permission else chosen + d.permission).joinToString(",") }
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { toggle() }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                        Text(d.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(on, { toggle() }, enabled = !busy, colors = SwitchDefaults.colors(checkedTrackColor = Club.forest))
                }
                if (i < DESKS.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = Club.mint, modifier = Modifier.fillMaxWidth()) {
            Text(if (chosen.isEmpty()) "No desks ticked: holders only see member pages." else "They'll see on Home: " + deskOrder(chosen).joinToString(" · ") { deskTitle(it) },
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = Club.forestDeep)
        }
        warnings.forEach { NoticeCard(if (it.severity == Severity.BLOCK) "Can't save this" else "Check before saving", it.text, if (it.severity == Severity.BLOCK) Tone.DANGER else Tone.WARNING) }
    }

    if (confirming && role != null) {
        val blocked = warnings.any { it.severity == Severity.BLOCK }
        AlertDialog(onDismissRequest = { confirming = false },
            title = { Text("Save changes to ${role.name}?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (role.holders.isEmpty()) "Nobody holds this role yet." else "${role.holders.joinToString(" and ")} ${if (role.holders.size == 1) "holds" else "hold"} this role. They'll see the change the next time the app refreshes.",
                    style = MaterialTheme.typography.bodyMedium)
                if (renamed) Text("Renamed to ${name.trim()}", style = MaterialTheme.typography.bodyMedium)
                if (change.added.isNotEmpty()) Text("+ Adds " + change.added.joinToString(", ") { deskTitle(it) }, color = Club.forest, fontWeight = FontWeight.Bold)
                if (change.removed.isNotEmpty()) Text("− Removes " + change.removed.joinToString(", ") { deskTitle(it) }, color = Club.roseInk, fontWeight = FontWeight.Bold)
                warnings.forEach { w -> Text(w.text, color = if (w.severity == Severity.BLOCK) Club.roseInk else Club.amberInk, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold) }
            } },
            confirmButton = { if (!blocked) TextButton(onClick = { save() }, enabled = !busy) { Text(if (warnings.isEmpty()) "Save" else "Save anyway", fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Go back") } })
    }
    if (resetting && role?.defaults != null) {
        AlertDialog(onDismissRequest = { resetting = false },
            title = { Text("Reset ${role.name}?") },
            text = { Text("Goes back to the built-in desks: " + role.defaults.joinToString(", ") { deskTitle(it) }.ifBlank { "none" } + ". The name stays the same.") },
            confirmButton = { TextButton(onClick = { vm.save("reset_role", buildJsonObject { put("p_role_id", role.id) }) { resetting = false; onBack() } }, enabled = !busy) {
                Text("Reset", fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text("Cancel") } })
    }
}
