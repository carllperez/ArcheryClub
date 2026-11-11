package ph.capstone.archeryclub.connected

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ClubIcon
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/* Small pieces shared by the training, payments and club-administration screens. */

fun fmtDate(d: LocalDate?): String = d?.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())) ?: "No date"

/** Month/day block tinted by status, used at the start of scorecard and payment rows. */
@Composable
fun DayChip(date: LocalDate?, tone: Tone) {
    val c = toneColors(tone)
    Column(Modifier.width(48.dp).clip(RoundedCornerShape(12.dp)).background(c.container).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(date?.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))?.uppercase().orEmpty(), style = MaterialTheme.typography.labelSmall, color = c.content, fontWeight = FontWeight.ExtraBold)
        Text(date?.dayOfMonth?.toString() ?: "—", style = MaterialTheme.typography.titleMedium, color = c.content, fontWeight = FontWeight.ExtraBold)
    }
}

/** A tappable card row: leading slot, title, subtitle and a trailing status pill. */
@Composable
fun DeskRow(title: String, subtitle: String, leading: @Composable () -> Unit, status: Pair<String, Tone>? = null, trailing: (@Composable () -> Unit)? = null,
            onClick: (() -> Unit)? = null, footer: (@Composable ColumnScope.() -> Unit)? = null) {
    RaisedCard(radius = 18.dp, onClick = onClick) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                leading()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                status?.let { (label, tone) -> StatusPill(label, tone) }
                trailing?.invoke()
            }
            footer?.invoke(this)
        }
    }
}

/** Compact pill buttons for actions inside a card (Return · Validate). */
@Composable
fun CardButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    if (primary) Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep, contentColor = Color.White)) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    } else OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp), shape = CircleShape,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1,
            color = if (danger) Club.roseInk else MaterialTheme.colorScheme.primary)
    }
}

/** Upper-case section label with an optional action on the right. */
@Composable
fun DeskLabel(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.ExtraBold) }
    }
}

/** Line of percentages, oldest to newest, with the latest point highlighted. */
@Composable
fun TrendLine(percents: List<Int>, modifier: Modifier = Modifier) {
    val line = Club.forest; val grid = MaterialTheme.colorScheme.outlineVariant
    val description = if (percents.size < 2) "Not enough rounds for a trend" else "Score trend from ${percents.first()}% to ${percents.last()}% over ${percents.size} rounds"
    Canvas(modifier.fillMaxWidth().height(84.dp).semantics { contentDescription = description }) {
        drawLine(grid, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 2f)
        if (percents.size < 2) return@Canvas
        val lo = (percents.min() - 5).coerceAtLeast(0).toFloat(); val hi = (percents.max() + 5).coerceAtMost(100).toFloat().coerceAtLeast(lo + 1)
        val pad = 10f
        fun point(i: Int) = Offset(pad + i * (size.width - 2 * pad) / (percents.size - 1), pad + (1 - (percents[i] - lo) / (hi - lo)) * (size.height - 2 * pad))
        val path = Path().apply { moveTo(point(0).x, point(0).y); for (i in 1 until percents.size) lineTo(point(i).x, point(i).y) }
        drawPath(path, line, style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = point(percents.lastIndex)
        drawCircle(Club.forestDeep, 13f, last); drawCircle(Club.lime, 8f, last)
    }
}

/** Ring showing a score as a share of its maximum. */
@Composable
fun ScoreRing(percent: Int, big: String, small: String, size: Int = 96) {
    val track = Club.mint; val fill = Club.forest
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 20f; val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(fill, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(big, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(small, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Progress bar for goals and stats (amber when behind). */
@Composable
fun GoalBar(progress: Float, behind: Boolean) {
    val track = if (behind) Club.amberSoft else Club.mint; val fill = if (behind) Color(0xFFB26B00) else Club.forest
    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(fill))
    }
}

/** Private files attached to a record, each opened through a short-lived signed link. */
@Composable
fun AttachedFiles(workspace: JsonObject, recordId: String, onOpen: (String) -> Unit) {
    val docs = workspace.records("documents").filter { it.text("record_id") == recordId && it.text("superseded_by").isBlank() }
    docs.forEach { d ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClubIcon(if (d.text("name").endsWith(".mp4", true)) "upload" else "doc", Modifier.size(18.dp), Club.forest)
            Column(Modifier.weight(1f)) {
                Text(d.text("requirement").ifBlank { d.text("name") }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.text("name"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { onOpen(d.text("path")) }) { Text("Open") }
        }
    }
}

/** A quiet red "Delete …" that always asks first. [needReason] adds a required reason (for official records). */
@Composable
fun DeleteRecordButton(label: String, question: String, explain: String, busy: Boolean, needReason: Boolean = false, onDelete: (String) -> Unit) {
    var asking by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    TextButton(onClick = { asking = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = ph.capstone.archeryclub.ui.Club.roseInk, fontWeight = FontWeight.ExtraBold)
    }
    if (asking) AlertDialog(onDismissRequest = { asking = false },
        title = { Text(question) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(explain, style = MaterialTheme.typography.bodyMedium)
            if (needReason) OutlinedTextField(reason, { reason = it.take(300) }, label = { Text("Why? (kept in the activity log)") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { asking = false; onDelete(reason.trim()) }, enabled = !busy && (!needReason || reason.isNotBlank())) {
            Text("Delete", color = ph.capstone.archeryclub.ui.Club.roseInk, fontWeight = FontWeight.ExtraBold) } },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Keep it") } })
}
