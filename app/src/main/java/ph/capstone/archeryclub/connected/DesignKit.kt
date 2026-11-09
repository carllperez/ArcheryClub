@file:OptIn(ExperimentalMaterial3Api::class)

package ph.capstone.archeryclub.connected

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/* Shared building blocks for the workflow screens: one visual language for status, counts and actions. */

private data class ToneColors(val container: Color, val content: Color)

@Composable private fun toneColors(tone: Tone): ToneColors {
    val dark = MaterialTheme.colorScheme.background.red < .5f
    return when (tone) {
        Tone.POSITIVE -> if (dark) ToneColors(Color(0xFF1F3D2E), Color(0xFF9AD4B8)) else ToneColors(Color(0xFFE3F2E8), Color(0xFF0B5A33))
        Tone.WARNING -> if (dark) ToneColors(Color(0xFF3F3218), Color(0xFFF1C76B)) else ToneColors(Color(0xFFFFF3D6), Color(0xFF7A4B00))
        Tone.DANGER -> if (dark) ToneColors(Color(0xFF452222), Color(0xFFF3A6A0)) else ToneColors(Color(0xFFFDE7E5), Color(0xFF9C1C13))
        Tone.INFO -> if (dark) ToneColors(Color(0xFF1C3046), Color(0xFFA6C8F0)) else ToneColors(Color(0xFFE5EEFA), Color(0xFF184A85))
        Tone.NEUTRAL -> ToneColors(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = toneColors(tone)
    Text(text, modifier.clip(RoundedCornerShape(50)).background(c.container).padding(horizontal = 10.dp, vertical = 4.dp),
        color = c.content, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

fun ActivityStatus.tone() = when (this) {
    ActivityStatus.REGISTERED, ActivityStatus.ATTENDED, ActivityStatus.OPEN -> Tone.POSITIVE
    ActivityStatus.WAITLISTED, ActivityStatus.CLOSING_SOON, ActivityStatus.FULL, ActivityStatus.EXCUSED -> Tone.WARNING
    ActivityStatus.MISSED -> Tone.DANGER
    ActivityStatus.NOT_YET_OPEN, ActivityStatus.IN_PROGRESS -> Tone.INFO
    ActivityStatus.CLOSED, ActivityStatus.ENDED -> Tone.NEUTRAL
}

fun EquipmentState.tone() = when (this) {
    EquipmentState.AVAILABLE -> Tone.POSITIVE
    EquipmentState.RESERVED, EquipmentState.MAINTENANCE -> Tone.WARNING
    EquipmentState.IN_USE -> Tone.INFO
    EquipmentState.OVERDUE, EquipmentState.DAMAGED -> Tone.DANGER
    EquipmentState.RETIRED -> Tone.NEUTRAL
}

fun borrowingTone(status: String, overdue: Boolean) = when {
    overdue -> Tone.DANGER
    status == "pending" || status == "returned_for_correction" -> Tone.WARNING
    status == "approved" || status == "returned" -> Tone.POSITIVE
    status == "released" -> Tone.INFO
    else -> Tone.NEUTRAL
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, tone: Tone = Tone.NEUTRAL, onClick: (() -> Unit)? = null) {
    val c = toneColors(tone)
    Surface(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), shape = MaterialTheme.shapes.medium,
        color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.surfaceContainer else c.container) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else c.content)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
fun TileRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)

@Composable
fun CapacityBar(registered: Int, capacity: Int, waitlisted: Int = 0) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val fraction = if (capacity <= 0) 0f else (registered.toFloat() / capacity).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
            color = if (fraction >= 1f) Color(0xFFB26A00) else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        val left = (capacity - registered).coerceAtLeast(0)
        Text(buildString {
            append("$registered of $capacity slots taken")
            append(if (left > 0) " · $left left" else " · full")
            if (waitlisted > 0) append(" · $waitlisted waitlisted")
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DateBlock(instant: Instant?, modifier: Modifier = Modifier) {
    val date = instant?.atZone(ZoneId.systemDefault())
    Surface(modifier.width(56.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date?.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))?.uppercase().orEmpty(),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(date?.dayOfMonth?.toString() ?: "—", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** A tappable list card with a leading slot, title, supporting lines and a trailing status. */
@Composable
fun WorkCard(title: String, lines: List<String>, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null,
             status: (@Composable () -> Unit)? = null, footer: (@Composable ColumnScope.() -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                leading?.invoke()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    lines.filter { it.isNotBlank() }.forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (status != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { status() }
            footer?.invoke(this)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ph.capstone.archeryclub.ui.TargetMark()
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun KeyValue(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(132.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PrimaryAction(text: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.small,
        colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        else ButtonDefaults.buttonColors()) { Text(text) }
}

@Composable
fun SecondaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.small) { Text(text) }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false,
                  extra: (@Composable ColumnScope.() -> Unit)? = null, confirmEnabled: Boolean = true) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(body); extra?.invoke(this) } },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } })
}

/** A chip row used for filters and segmented choices. */
@Composable
fun ChoiceChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

// ------------------------------------------------------------------ dates

private val zone get() = ZoneId.systemDefault()
fun fmtDay(i: Instant?) = i?.atZone(zone)?.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())) ?: "Date to be confirmed"
fun fmtTime(i: Instant?) = i?.atZone(zone)?.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())).orEmpty()
fun fmtDateTime(i: Instant?) = if (i == null) "—" else "${fmtDay(i)} · ${fmtTime(i)}"
fun fmtRange(a: Instant?, b: Instant?): String {
    if (a == null) return "Date to be confirmed"
    val sameDay = b != null && a.atZone(zone).toLocalDate() == b.atZone(zone).toLocalDate()
    return if (b == null) fmtDateTime(a) else if (sameDay) "${fmtDay(a)} · ${fmtTime(a)} – ${fmtTime(b)}" else "${fmtDateTime(a)} – ${fmtDateTime(b)}"
}
fun relative(target: Instant?, now: Instant = Instant.now()): String {
    if (target == null) return ""
    val d = Duration.between(now, target)
    val past = d.isNegative; val a = d.abs()
    val text = when {
        a.toMinutes() < 60 -> "${a.toMinutes().coerceAtLeast(1)} min"
        a.toHours() < 24 -> "${a.toHours()} h"
        else -> "${a.toDays()} day${if (a.toDays() == 1L) "" else "s"}"
    }
    return if (past) "$text ago" else "in $text"
}

/** Calendar date picker followed by a clock picker. Replaces typed YYYY-MM-DDTHH:MM entry. */
@Composable
fun DateTimeField(label: String, value: Instant?, onChange: (Instant) -> Unit, modifier: Modifier = Modifier, dateOnly: Boolean = false) {
    var step by remember { mutableIntStateOf(0) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val local = value?.atZone(zone)
    OutlinedButton(onClick = { step = 1 }, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (value == null) "Select" else if (dateOnly) fmtDay(value) else fmtDateTime(value), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    if (step == 1) {
        val initialMillis = (local?.toLocalDate() ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(onDismissRequest = { step = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        if (dateOnly) { onChange(date.atStartOfDay(zone).toInstant()); step = 0 } else { pickedDate = date; step = 2 }
                    }
                }) { Text(if (dateOnly) "Done" else "Next") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } }) { DatePicker(state = state) }
    }
    if (step == 2) {
        val state = rememberTimePickerState(initialHour = local?.hour ?: 9, initialMinute = local?.minute ?: 0, is24Hour = false)
        AlertDialog(onDismissRequest = { step = 0 }, title = { Text(label) }, text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    pickedDate?.let { onChange(it.atTime(state.hour, state.minute).atZone(zone).toInstant()) }
                    step = 0
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Cancel") } })
    }
}
