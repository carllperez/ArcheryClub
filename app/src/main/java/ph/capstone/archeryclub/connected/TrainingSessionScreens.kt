package ph.capstone.archeryclub.connected

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ScreenShell

/* A training groups the rounds shot in one session (they share a session id saved with their arrows).
 * The training page lists its rounds, adds another, opens the scoreboard and statistics, and sends
 * every unsent round to the coach in one step (migration 032, submit_training_session). */

@Composable
fun TrainingOverviewScreen(workspace: JsonObject, sessionId: String, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?,
                           onBack: () -> Unit, onRoute: (String) -> Unit) {
    val t = trainingById(workspace, sessionId)
    if (t == null) { ScreenShell("Training", onBack, snackbar) { EmptyState("Training not found", "It may have been removed. Go back and refresh.") }; return }
    val own = t.first.userId == workspace.text("user_id")
    val info = t.info
    var confirmSend by remember { mutableStateOf(false) }
    val anyArrows = t.rounds.any { roundArrows(it.row).isNotEmpty() }
    val sticky: (@Composable RowScope.() -> Unit)? = if (own && t.toSend.isNotEmpty() && vm != null) ({
        StickyButton(if (t.state == ScoreState.RETURNED) "Fix and resend" else "Send training to coach", !busy, Modifier.weight(1f)) { confirmSend = true }
    }) else null
    HeroScaffold(t.title, onBack, snackbar, overlap = 44.dp, busy = busy,
        eyebrow = { Pill(t.state.label.uppercase(), Color.White.copy(alpha = .16f), Color.White) },
        heroExtra = { HeroStats(listOf("${plainNumber(t.total)}/${plainNumber(t.max)}" to "Total",
            "${t.rounds.size}" to if (t.rounds.size == 1) "Round" else "Rounds",
            (if (t.max > 0) (Math.round(t.total * 1000 / t.max) / 100.0).toString() else "—") to "Average")) },
        sticky = sticky) {
        RowGroup {
            listOf("Date" to fmtDate(t.date), "Round" to info.standardRound.ifBlank { "Free training" }, "Environment" to info.environment,
                "Bow" to info.bow, "Arrow" to info.arrow).filter { it.second.isNotBlank() }.forEachIndexed { i, (k, v) ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(v, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (t.state == ScoreState.RETURNED) NoticeCard("Your coach sent this back", "Open the returned round to see the note, fix it, then resend the training.", Tone.DANGER)
        DeskLabel("Rounds", if (own) "+ Add a round" else null) { onRoute("score_entry:add:${t.sessionId}") }
        t.rounds.forEachIndexed { i, r ->
            val ends = roundArrows(r.row); val flat = ends.flatten()
            val chips: (@Composable ColumnScope.() -> Unit)? = if (flat.isEmpty()) null else ({
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    flat.take(36).forEach { ArrowDot(it.p, 24.dp) }
                    if (flat.size > 36) Text("+${flat.size - 36}", style = MaterialTheme.typography.bodySmall)
                }
            })
            DeskRow("Round ${i + 1} · ${r.scoreLabel}", r.conditions, leading = { DayChip(r.date, r.state.tone) }, status = r.state.label to r.state.tone,
                onClick = { onRoute("scorecard:${r.id}") }, footer = chips)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { onRoute("scoreboard:${t.sessionId}") }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(999.dp)) {
                Text("Scoreboard", fontWeight = FontWeight.ExtraBold) }
            OutlinedButton(onClick = { onRoute("scorestats:session:${t.sessionId}") }, enabled = anyArrows, modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(999.dp)) { Text("Statistics", fontWeight = FontWeight.ExtraBold) }
        }
        if (own && t.toSend.isNotEmpty()) Text("Sending puts all ${t.toSend.size} unsent round${if (t.toSend.size == 1) "" else "s"} in your coach's queue together. You can't edit them while the coach checks.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirmSend && vm != null) AlertDialog(onDismissRequest = { confirmSend = false },
        title = { Text("Send this training to your coach?") },
        text = { Text("${t.toSend.size} round${if (t.toSend.size == 1) "" else "s"} (${plainNumber(t.toSend.sumOf { it.score })}/${plainNumber(t.toSend.sumOf { it.max })}) go to the coach to validate. Rounds already with the coach or validated stay as they are.") },
        confirmButton = { TextButton(onClick = {
            vm.save("submit_training_session", buildJsonObject { put("p_ids", JsonArray(t.toSend.map { JsonPrimitive(it.id) })) }) { confirmSend = false }
        }, enabled = !busy) { Text("Send", fontWeight = FontWeight.ExtraBold) } },
        dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } })
}

@Composable
private fun Cell(text: String, width: Dp, bold: Boolean = false, head: Boolean = false) {
    Text(text, Modifier.width(width).background(if (head) Club.mint else Color.Transparent).padding(vertical = 6.dp), textAlign = TextAlign.Center,
        fontWeight = if (bold || head) FontWeight.ExtraBold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
}

/** The training as a paper-style scoreboard, ready to print or save as a PDF for the coach's signature. */
@Composable
fun ScoreboardScreen(workspace: JsonObject, sessionId: String, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val t = trainingById(workspace, sessionId)
    if (t == null) { ScreenShell("Scoreboard", onBack, snackbar) { EmptyState("Training not found", "It may have been removed. Go back and refresh.") }; return }
    val archer = memberName(workspace, t.first.userId)
    val context = LocalContext.current
    // Kept here so the page isn't thrown away before it has finished loading for the printer.
    var printer by remember { mutableStateOf<WebView?>(null) }
    ScreenShell("Scoreboard", onBack, snackbar, stickyAction = {
        Button(onClick = { printer = printScoreboard(context, t.title, scoreboardHtml(t, archer)) }, modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(999.dp), colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) {
            Text("Print or save as PDF", fontWeight = FontWeight.ExtraBold) }
    }) {
        Text(t.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Text("$archer · ${fmtDate(t.date)} · ${t.info.standardRound.ifBlank { "Free training" }} · ${plainNumber(t.total)}/${plainNumber(t.max)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        t.rounds.forEachIndexed { i, r ->
            val ends = roundArrows(r.row); val per = ends.maxOfOrNull { it.size } ?: 0
            DeskLabel("Round ${i + 1} · ${r.conditions}")
            RaisedCard { Column(Modifier.padding(10.dp).horizontalScroll(rememberScrollState())) {
                if (ends.isEmpty()) { Text("Total only: ${r.scoreLabel}", Modifier.padding(6.dp)); return@Column }
                Row { Cell("End", 40.dp, head = true); (1..per).forEach { Cell("$it", 34.dp, head = true) }; Cell("End", 44.dp, head = true); Cell("Total", 52.dp, head = true) }
                boardLines(ends).forEach { l ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    Row { Cell("${l.end}", 40.dp); (0 until per).forEach { Cell(l.arrows.getOrNull(it).orEmpty(), 34.dp) }; Cell("${l.total}", 44.dp, bold = true); Cell("${l.running}", 52.dp) }
                }
            } }
        }
        val st = roundStats(t.rounds.flatMap { roundArrows(it.row) })
        if (st.counts.isNotEmpty()) {
            DeskLabel("Summary")
            RaisedCard { Column(Modifier.padding(10.dp).horizontalScroll(rememberScrollState())) {
                Row { st.counts.forEach { Cell(it.first, 38.dp, head = true) }; Cell("Hits", 46.dp, head = true); Cell("Avg", 52.dp, head = true) }
                Row { st.counts.forEach { Cell("${it.second}", 38.dp) }; Cell("${st.hits}", 46.dp); Cell((Math.round(st.average * 100) / 100.0).toString(), 52.dp, bold = true) }
            } }
        }
        Text("The printed page adds signature lines for you and your coach.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Android prints web pages, so the scoreboard is laid out as HTML and handed to the system print dialog,
 *  which can send it to a printer or save it as a PDF. */
private fun printScoreboard(context: Context, title: String, html: String): WebView {
    val web = WebView(context)
    web.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val name = title.ifBlank { "Scoreboard" }
            manager.print(name, view.createPrintDocumentAdapter(name), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        }
    }
    web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    return web
}
