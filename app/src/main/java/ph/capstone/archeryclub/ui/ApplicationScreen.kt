package ph.capstone.archeryclub.ui

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.capstone.archeryclub.domain.*

@Composable
fun ApplicationScreen(record: ApplicationRecord, busy: Boolean, snackbar: SnackbarHostState,
    onBack: () -> Unit, onSave: (ApplicationForm) -> Unit, onSubmit: (ApplicationForm) -> Unit,
    onError: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(record.form.fullName) }
    var email by rememberSaveable { mutableStateOf(record.form.email) }
    var phone by rememberSaveable { mutableStateOf(record.form.phone) }
    var studentNumber by rememberSaveable { mutableStateOf(record.form.studentNumber) }
    var experience by rememberSaveable { mutableStateOf(record.form.experience) }
    var confirmed by rememberSaveable { mutableStateOf(record.form.confirmed) }
    var attachmentName by rememberSaveable { mutableStateOf(record.form.attachment?.name) }
    var attachmentUri by rememberSaveable { mutableStateOf(record.form.attachment?.uri) }
    var attachmentSize by rememberSaveable { mutableLongStateOf(record.form.attachment?.sizeBytes ?: 0) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var confirmSubmit by rememberSaveable { mutableStateOf(false) }
    var readingFile by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = ApplicationForm(name, email, phone, studentNumber, experience,
        attachmentUri?.let { Attachment(attachmentName ?: "Document", it, attachmentSize) }, confirmed)
    val errors = if (attempted) form.errors() else emptyMap()
    val dirty = form.normalized() != record.form
    val working = busy || readingFile
    val leave: () -> Unit = {
        if (!working) { if (record.status.editable && dirty) discard = true else onBack() }
    }
    BackHandler(onBack = leave)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            readingFile = true
            scope.launch {
                try {
                    val attachment = withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val type = resolver.getType(uri)
                        require(type in setOf("application/pdf", "image/jpeg", "image/png")) { "Choose a PDF, JPG or PNG file." }
                        val meta = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            check(cursor.moveToFirst()) { "This document could not be read." }
                            cursor.getString(0) to if (cursor.isNull(1)) -1L else cursor.getLong(1)
                        } ?: error("This document could not be read.")
                        require(meta.second in 1..10_485_760L) { "Choose a non-empty file up to 10 MB with a known file size." }
                        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        Attachment(meta.first, uri.toString(), meta.second)
                    }
                    attachmentName = attachment.name
                    attachmentUri = attachment.uri
                    attachmentSize = attachment.sizeBytes
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    onError(exception.message ?: "Unable to select this document.")
                } finally { readingFile = false }
            }
        }
    }

    ScreenShell("My application", leave, snackbar) {
        PreviewNotice()
        StatusBadge(record.status.label)
        if (!record.status.editable) {
            val description = when (record.status) {
                ApplicationStatus.APPROVED -> "The recorded decision is approved. Official membership access must be assigned by the club service."
                ApplicationStatus.REJECTED -> "The recorded decision is rejected. Contact the club for clarification."
                else -> "Your preview application has been submitted locally. It remains locked while awaiting a club decision. This preview is not connected to a reviewer."
            }
            InfoCard("Application status", description)
            InfoCard("Submitted details", "${record.form.fullName}\n${record.form.email}\n${record.form.studentNumber.ifBlank { "No student number provided" }}")
            record.form.attachment?.let { InfoCard("Selected document", "${it.name}\nLocal selection only; not uploaded.") }
        } else {
            record.correctionNote?.let { InfoCard("A correction is needed", it) }
            Text("Tell us about yourself", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Use sample information while testing. The club’s final required fields and documents still need to be confirmed.",
                style = MaterialTheme.typography.bodyMedium)
            FormField("Full name", name, { name = it.take(120) }, errors["name"], enabled = !working)
            FormField("Email address", email, { email = it.take(254) }, errors["email"], KeyboardType.Email, !working)
            FormField("Contact number (optional)", phone, { phone = it.take(20) }, errors["phone"], KeyboardType.Phone, !working)
            FormField("Student number (optional)", studentNumber, { studentNumber = it.take(40) }, enabled = !working)
            OutlinedTextField(value = experience, onValueChange = { experience = it.take(1000) },
                label = { Text("Archery experience (optional)") }, modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 6, enabled = !working)
            Text("Supporting document", style = MaterialTheme.typography.titleMedium)
            Text("Optional in this preview · PDF, JPG or PNG · Up to 10 MB. Selecting a file does not upload it.",
                style = MaterialTheme.typography.bodySmall)
            attachmentName?.let {
                InfoCard("Selected on this device", it)
                TextButton(onClick = { attachmentName = null; attachmentUri = null; attachmentSize = 0 }, enabled = !working) { Text("Remove selection") }
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf", "image/jpeg", "image/png")) },
                enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(if (readingFile) "Reading document…" else "Select a document") }
            Row(Modifier.fillMaxWidth()) {
                Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, enabled = !working)
                Text("I have reviewed the information in this sample application.", Modifier.weight(1f).padding(top = 12.dp))
            }
            errors["confirmed"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                attempted = true
                if (form.errors().isEmpty()) confirmSubmit = true
                else onError("Review the highlighted fields before submitting.")
            }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Saving…" else "Submit preview application") }
            OutlinedButton(onClick = { onSave(form) }, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Save draft") }
        }
    }
    if (discard) DiscardDialog(onDismiss = { discard = false }, onDiscard = { discard = false; onBack() })
    if (confirmSubmit) AlertDialog(onDismissRequest = { confirmSubmit = false },
        title = { Text("Submit this preview application?") },
        text = { Text("The saved status will become Pending review, and the form will be locked. This stays on your device and is not sent to a club.") },
        confirmButton = { TextButton(onClick = { confirmSubmit = false; onSubmit(form) }) { Text("Submit preview") } },
        dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("Keep editing") } })
}

@Composable
fun FormField(label: String, value: String, onChange: (String) -> Unit, error: String? = null,
    keyboard: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        isError = error != null, supportingText = error?.let { { Text(it) } }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), enabled = enabled,
        modifier = Modifier.fillMaxWidth())
}
