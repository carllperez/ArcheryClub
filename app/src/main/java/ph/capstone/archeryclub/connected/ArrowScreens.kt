package ph.capstone.archeryclub.connected

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import ph.capstone.archeryclub.ui.Club
import ph.capstone.archeryclub.ui.ScreenShell
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* Rounds arrow by arrow: ends in WA ring colours, statistics, where arrows landed, and tap-to-score entry. */

private val Gold = Color(0xFFF2D94E); private val RingRed = Color(0xFFE0574C); private val RingBlue = Color(0xFF4AA3E0)
private val RingBlack = Color(0xFF1E1F1E); private val Miss = Color(0xFF3A3F3C); private val Ink = Color(0xFF131715)

/** WA target colours: gold 10–9, red 8–7, blue 6–5, black 4–3, white 2–1. */
fun arrowColors(p: String): Pair<Color, Color> = when (p) {
    "M" -> Miss to Color.White
    "X", "10", "9" -> Gold to Ink
    "8", "7" -> RingRed to Color.White
    "6", "5" -> RingBlue to Color.White
    "4", "3" -> RingBlack to Color.White
    else -> Color.White to Ink
}

@Composable
fun ArrowDot(p: String, size: Dp = 32.dp) {
    val (bg, fg) = arrowColors(p)
    Box(Modifier.size(size).clip(CircleShape).background(bg).let { if (bg == Color.White) it.border(1.5.dp, Color(0xFFC9D3C5), CircleShape) else it },
        contentAlignment = Alignment.Center) {
        Text(p, color = fg, fontWeight = FontWeight.ExtraBold, fontSize = if (size >= 30.dp) 14.sp else 11.sp)
    }
}

/** One row per end: its total and the arrows, highest first, as on a scoresheet. */
@Composable
fun EndsCard(ends: List<List<Arrow>>, onEnd: ((Int) -> Unit)? = null) {
    RowGroup {
        ends.forEachIndexed { i, end ->
            Row(Modifier.fillMaxWidth().clickable(enabled = onEnd != null) { onEnd?.invoke(i) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(60.dp)) {
                    Text("End ${i + 1}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                    Text("${end.sumOf { it.points }}/${end.size * 10}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    end.sortedWith(compareByDescending<Arrow> { it.points }.thenBy { if (it.p == "X") 0 else 1 }).forEach { ArrowDot(it.p) }
                }
            }
            if (i < ends.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
        }
    }
}

/** The target face with every arrow that has a position, and the group centre as a lime cross. */
@Composable
fun TargetPlot(ends: List<List<Arrow>>, size: Dp, showSpread: Boolean = false, onTap: ((Double, Double) -> Unit)? = null) {
    val placed = ends.flatten().filter { it.x != null && it.y != null && it.p != "M" }
    val centre = roundStats(ends).centre
    val spread = if (showSpread) groupSpread(ends.flatten()) else null
    // The tap handler is set up once; reading the latest callback keeps each tap adding to the current end.
    val latestTap by rememberUpdatedState(onTap)
    val tap = if (onTap == null) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures { o ->
            val r = this.size.width.coerceAtMost(this.size.height) / 2f * 0.96f
            latestTap?.invoke(((o.x - this.size.width / 2f) / r).toDouble(), ((o.y - this.size.height / 2f) / r).toDouble())
        }
    }
    Canvas(Modifier.size(size).then(tap)) {
        val c = Offset(this.size.width / 2, this.size.height / 2); val r = this.size.minDimension / 2 * 0.96f
        // Ten scoring rings from the outside in: white, white, black, black, blue, blue, red, red, gold, gold.
        listOf(Color.White, Color.White, RingBlack, RingBlack, RingBlue, RingBlue, RingRed, RingRed, Gold, Gold).forEachIndexed { i, col ->
            val rr = r * (10 - i) / 10f
            drawCircle(col, rr, c)
            drawCircle(if (col == RingBlack) Color(0xFF5A5F5C) else Color(0xFF9AA39D), rr, c, style = Stroke(1f))
        }
        drawCircle(Color(0xFF9AA39D), r * 0.05f, c, style = Stroke(1f))
        placed.forEach { a ->
            val p = Offset(c.x + r * a.x!!.toFloat(), c.y + r * a.y!!.toFloat())
            val onDark = a.points in 3..6
            drawCircle(if (onDark) Color.White else Ink, r * 0.035f, p)
            drawCircle(Ink, r * 0.035f, p, style = Stroke(1f))
        }
        if (spread != null && centre != null) {
            val (sx, sy) = spread; val (mx, my) = centre
            val w = (2 * sx * r).toFloat().coerceAtLeast(r * 0.06f); val h = (2 * sy * r).toFloat().coerceAtLeast(r * 0.06f)
            drawOval(Color(0xFF6B746E).copy(alpha = .45f), Offset(c.x + r * mx.toFloat() - w / 2, c.y + r * my.toFloat() - h / 2), Size(w, h))
        }
        centre?.let { (x, y) ->
            val p = Offset(c.x + r * x.toFloat(), c.y + r * y.toFloat()); val lime = Color(0xFF9BCB3C)
            drawCircle(lime, r * 0.09f, p, style = Stroke(4f))
            drawLine(lime, Offset(p.x - r * 0.15f, p.y), Offset(p.x + r * 0.15f, p.y), 4f)
            drawLine(lime, Offset(p.x, p.y - r * 0.15f), Offset(p.x, p.y + r * 0.15f), 4f)
        }
    }
}

@Composable
private fun EndAverageChart(averages: List<Double>) {
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val left = 8f; val right = size.width - 8f; val top = 10f; val bottom = size.height - 6f
        fun x(i: Int) = if (averages.size == 1) (left + right) / 2 else left + (right - left) * i / (averages.size - 1)
        fun y(v: Double) = (bottom - (bottom - top) * v / 10).toFloat()
        listOf(0.0, 5.0, 10.0).forEach { drawLine(Color(0xFFE6ECE3), Offset(left, y(it)), Offset(right, y(it)), 1.5f) }
        val path = Path().apply { averages.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        drawPath(path, Club.forest, style = Stroke(5f))
        averages.forEachIndexed { i, v -> drawCircle(Club.forest, 8f, Offset(x(i), y(v))) }
    }
    Row(Modifier.fillMaxWidth()) {
        averages.forEachIndexed { i, v ->
            Text("End ${i + 1}\n${(Math.round(v * 10) / 10.0)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                textAlign = if (i == 0) androidx.compose.ui.text.style.TextAlign.Start else if (i == averages.lastIndex) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DistributionDonut(stats: RoundStats) {
    val total = stats.counts.sumOf { it.second }.coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                var start = -90f; val w = size.minDimension * 0.2f
                stats.counts.forEach { (k, n) ->
                    val sweep = 360f * n / total
                    drawArc(arrowColors(k).first, start, sweep - 1.5f, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Hits", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${stats.hits}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                if (stats.misses > 0) Text("${stats.misses} miss${if (stats.misses == 1) "" else "es"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            stats.counts.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEach { (k, n) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { ArrowDot(k, 22.dp); Text("$n", fontWeight = FontWeight.Bold) } }
                }
            }
        }
    }
}

/** On a scorecard: the ends, plus a small target and one line for the coach, and a way into the statistics. */
@Composable
fun RoundArrowsSection(ends: List<List<Arrow>>, coachView: Boolean, onEnd: (Int) -> Unit = {}, onStats: () -> Unit) {
    val st = roundStats(ends)
    DeskLabel("Ends", "Statistics", onStats)
    EndsCard(ends, onEnd)
    if (coachView || st.plotted > 0) RaisedCard(onClick = onStats) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (st.plotted > 0) TargetPlot(ends, 96.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                groupDescription(st.centre)?.let { Text("Group sits $it.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
                endTrend(st)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("All statistics", style = MaterialTheme.typography.labelLarge, color = Club.forest, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
fun ScoreStatsScreen(workspace: JsonObject, id: String, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val card = scoreCards(workspace).find { it.id == id }
    val ends = card?.row?.let(::roundArrows).orEmpty()
    ScreenShell("Statistics", onBack, snackbar) {
        if (card == null || ends.isEmpty()) { EmptyState("No arrows on this round", "Only rounds entered arrow by arrow or imported from a scoring app have statistics."); return@ScreenShell }
        val st = roundStats(ends)
        Text("${card.scoreLabel} · ${fmtDate(card.date)} · ${card.conditions}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AVERAGE ARROW PER END", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EndAverageChart(st.endAverages)
            endTrend(st)?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = if (it.startsWith("Dropped")) Club.amberInk else Club.forest) }
        } }
        RaisedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DISTRIBUTION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DistributionDonut(st)
        } }
        RaisedCard { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("WHERE ARROWS LANDED", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
            if (st.plotted == 0) Text("This round was scored by tapping, so there are no hit positions. Import from a scoring app to see where arrows landed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                TargetPlot(ends, 240.dp, showSpread = true)
                Text("Group centre ${groupDescription(st.centre)} (lime cross)." + if (st.misses > 0) " ${st.misses} miss${if (st.misses == 1) "" else "es"} not shown." else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    }
}

/** A member scores a round arrow by arrow: X, 10..1, M on a keypad, ends filling as they go. */
@Composable
fun ScoreEntryScreen(workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit, onTotalOnly: (JsonObject) -> Unit) {
    val existing = scoreCards(workspace).find { it.id == id }?.row
    val last = scoreCards(workspace, workspace.text("user_id")).firstOrNull()?.row
    val seed = existing ?: last
    var date by rememberSaveable { mutableStateOf(existing?.text("training_date")?.take(10) ?: LocalDate.now().toString()) }
    var distance by rememberSaveable { mutableStateOf(seed?.text("distance")?.toDoubleOrNull()?.let(::plainNumber) ?: "18") }
    // The same fields, in the same order, as a scoring app's CSV export: Title, Date, Standard round,
    // Indoor/Outdoor, Bow, Arrow, Distance, Target. Bow and arrow carry over from the last round.
    val seedInfo = seed?.let(::roundInfo) ?: RoundInfo()
    val existingInfo = existing?.let(::roundInfo)
    var title by rememberSaveable { mutableStateOf(existingInfo?.title?.ifBlank { null } ?: "Practice ${LocalDate.now()}") }
    var standard by rememberSaveable { mutableStateOf(existingInfo?.standardRound?.ifBlank { null } ?: "Practice") }
    var environment by rememberSaveable { mutableStateOf(existingInfo?.environment?.ifBlank { null } ?: seedInfo.environment.ifBlank { "Indoor" }) }
    var bow by rememberSaveable { mutableStateOf(seedInfo.bow.ifBlank { "Recurve" }) }
    var arrowName by rememberSaveable { mutableStateOf(seedInfo.arrow) }
    var face by rememberSaveable { mutableStateOf(seed?.text("target_face")?.ifBlank { null } ?: "WA Full (40cm)") }
    var notes by rememberSaveable { mutableStateOf(existing?.text("observations").orEmpty()) }
    var perEnd by rememberSaveable { mutableIntStateOf(existing?.let { roundArrows(it).firstOrNull()?.size } ?: 6) }
    // Ends kept as "9,6,6;9,9" so they survive rotation.
    var encoded by rememberSaveable { mutableStateOf(existing?.let { encodeEnds(roundArrows(it)) }.orEmpty()) }
    val ends = decodeEnds(encoded)
    // Tap where the arrow hit (keeps positions, so statistics can show the group) or tap its score on a keypad.
    var onTarget by rememberSaveable { mutableStateOf(true) }
    var editingSetup by rememberSaveable { mutableStateOf(existing == null) }
    fun add(a: Arrow) {
        val list = ends.map { it.toMutableList() }.toMutableList()
        if (list.isEmpty() || list.last().size >= perEnd) list.add(mutableListOf())
        list.last().add(a)
        encoded = encodeEnds(list)
    }
    fun undo() {
        val list = ends.map { it.toMutableList() }.toMutableList()
        if (list.isNotEmpty()) { list.last().removeAt(list.last().lastIndex); if (list.last().isEmpty()) list.removeAt(list.lastIndex) }
        encoded = encodeEnds(list)
    }
    val total = ends.flatten().sumOf { it.points }; val count = ends.flatten().size
    val ready = count > 0 && distance.toDoubleOrNull() != null && face.isNotBlank() && bow.isNotBlank() && title.isNotBlank() && standard.isNotBlank() &&
        runCatching { LocalDate.parse(date) }.isSuccess
    fun save(submit: Boolean) {
        vm?.save("save_round", buildJsonObject {
            put("p_action", "save_personal_training"); put("p_submit", submit)
            put("p_data", buildJsonObject {
                existing?.let { put("id", it.text("id")); it.text("version").toLongOrNull()?.let { v -> put("version", v) } }
                put("training_date", date); put("distance", distance); put("target_face", face.trim()); put("category", bowCategory(bow.trim()))
                put("scoring_format", standard.trim()); put("observations", notes.trim()); put("score", total); put("maximum_score", count * 10)
                put("arrows", arrowsJson(ends, RoundInfo(title.trim(), standard.trim(), environment, bow.trim(), arrowName.trim())))
            })
        }) { onBack() }
    }
    ScreenShell(if (existing == null) "Enter a round" else "Edit round", onBack, snackbar, stickyAction = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { save(false) }, enabled = !busy && ready && vm != null, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(999.dp)) {
                Text("Save", fontWeight = FontWeight.ExtraBold) }
            Button(onClick = { save(true) }, enabled = !busy && ready && vm != null, modifier = Modifier.weight(1.6f).height(50.dp), shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text("Save and send to coach", fontWeight = FontWeight.ExtraBold, maxLines = 1) }
        }
    }) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        // The round's details, collapsed to one line once set so the keypad stays in reach.
        RaisedCard(onClick = { editingSetup = !editingSetup }) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                        Text("$standard · $environment · $distance m · $face · $bow", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (editingSetup) "Done" else "Change", color = Club.forest, fontWeight = FontWeight.ExtraBold)
                }
                if (editingSetup) {
                    OutlinedTextField(title, { title = it.take(80) }, label = { Text("Title") }, placeholder = { Text("e.g. Final Year T1 Week 9") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    DateTimeField("Date", runCatching { LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull(),
                        { date = it.atZone(ZoneId.systemDefault()).toLocalDate().toString() }, dateOnly = true)
                    Text("Standard round", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(listOf("Practice", "WA 18m", "WA 25m", "WA 70m", "WA 720").map { it to it }, standard) { standard = it }
                    OutlinedTextField(standard, { standard = it.take(60) }, label = { Text("Or type a round") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Environment", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(listOf("Indoor" to "Indoor", "Outdoor" to "Outdoor"), environment) { environment = it }
                    OutlinedTextField(bow, { bow = it.take(60) }, label = { Text("Bow") }, placeholder = { Text("e.g. Fortified Recurve") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(arrowName, { arrowName = it.take(60) }, label = { Text("Arrow (optional)") }, placeholder = { Text("e.g. Super Carbon") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(distance, { distance = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Distance (m)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Target", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(listOf("WA Full (40cm)", "WA Full (80cm)", "WA Full (122cm)", "WA 3-spot").map { it to it }, face) { face = it }
                    Text("Arrows per end", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(listOf("3" to "3 arrows", "6" to "6 arrows"), perEnd.toString()) { perEnd = it.toInt() }
                    OutlinedTextField(notes, { notes = it.take(600) }, label = { Text("Notes (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (ends.isNotEmpty()) EndsCard(ends)
        val inEnd = ends.lastOrNull()?.size?.takeIf { it < perEnd } ?: 0
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Total $total / ${count * 10}" + (if (count > 0) " · end ${if (inEnd == 0) ends.size + 1 else ends.size}, arrow ${inEnd + 1} of $perEnd" else ""),
                fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { undo() }, enabled = count > 0) { Text("Undo last", fontWeight = FontWeight.ExtraBold) }
        }
        ChoiceChips(listOf("target" to "Tap the target", "keypad" to "Tap the score"), if (onTarget) "target" else "keypad") { onTarget = it == "target" }
        if (onTarget) {
            // The end being shot is drawn on the face; a full end starts a fresh one on the next tap.
            val current = ends.lastOrNull()?.takeIf { it.size < perEnd }.orEmpty()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TargetPlot(listOf(current), 300.dp, showSpread = current.size > 1) { x, y -> if (!busy && count < 1440) add(Arrow(scoreAt(x, y), x, y)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { add(Arrow("M")) }, enabled = !busy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) { Text("Miss (off the face)") }
            }
            Text("Tap where each arrow hit. Arrows touching a line get the higher score.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else ARROW_KEYS.chunked(4).forEach { rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowKeys.forEach { k ->
                    val (bg, fg) = arrowColors(k)
                    Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                        .let { if (bg == Color.White) it.border(1.5.dp, Color(0xFFC9D3C5), RoundedCornerShape(14.dp)) else it }
                        .clickable(enabled = !busy && count < 1440) { add(Arrow(k)) }, contentAlignment = Alignment.Center) {
                        Text(k, color = fg, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
        if (existing == null) TextButton(onClick = { onTotalOnly(newRoundSeed(workspace, scoreCards(workspace, workspace.text("user_id")).firstOrNull())) },
            modifier = Modifier.fillMaxWidth()) { Text("Just enter the total instead") }
    }
}


/** One end at a time, like a scoring app: the end's arrows, where they hit, the group, and previous/next. */
@Composable
fun EndScreen(workspace: JsonObject, routeId: String, snackbar: SnackbarHostState, onBack: () -> Unit, onGo: (String) -> Unit) {
    val id = routeId.substringBefore(':'); val start = routeId.substringAfter(':', "0").toIntOrNull() ?: 0
    val card = scoreCards(workspace).find { it.id == id }
    val ends = card?.row?.let(::roundArrows).orEmpty()
    var index by rememberSaveable(routeId) { mutableIntStateOf(start.coerceIn(0, (ends.size - 1).coerceAtLeast(0))) }
    if (card == null || ends.isEmpty()) { ScreenShell("End", onBack, snackbar) { EmptyState("No arrows on this round", "Only rounds scored arrow by arrow have ends to show.") }; return }
    val end = ends[index]; val st = roundStats(ends)
    HeroScaffold(roundInfo(card.row).title.ifBlank { card.row.text("observations").substringBefore(" · ").ifBlank { card.row.text("scoring_format") } }, onBack, snackbar, overlap = 44.dp,
        heroExtra = { HeroStats(listOf("${end.sumOf { it.points }}/${end.size * 10}" to "End ${index + 1} of ${ends.size}",
            "${st.total}/${st.max}" to "Round", (Math.round(st.average * 100) / 100.0).toString() to "Average")) },
        sticky = {
            OutlinedButton(onClick = { index-- }, enabled = index > 0, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(999.dp)) { Text("Previous", fontWeight = FontWeight.ExtraBold) }
            Button(onClick = { index++ }, enabled = index < ends.lastIndex, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text("Next", fontWeight = FontWeight.ExtraBold) }
        }) {
        RaisedCard {
            Row(Modifier.padding(14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                end.sortedWith(compareByDescending<Arrow> { it.points }.thenBy { if (it.p == "X") 0 else 1 }).forEach { ArrowDot(it.p, 38.dp) }
            }
        }
        val placed = end.count { it.x != null && it.y != null && it.p != "M" }
        RaisedCard { Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (placed == 0) Text("Scored by tapping the score, so there are no hit positions for this end.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                TargetPlot(listOf(end), 300.dp, showSpread = true)
                Text("This end's group sits ${groupDescription(roundStats(listOf(end)).centre)}. The shaded oval shows how spread it is.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    }
}
