package ph.capstone.archeryclub.connected

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
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

/** Rings and hits, shared by the face and the magnifying loupe. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFace(c: Offset, r: Float, placed: List<Arrow>) {
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
}

/** The target face with every arrow that has a position, and the group centre as a lime cross.
 *  With [onTap], press and hold to place an arrow: a loupe magnifies the spot under the finger, and lifting drops it there. */
@Composable
fun TargetPlot(ends: List<List<Arrow>>, size: Dp, showSpread: Boolean = false, onTap: ((Double, Double) -> Unit)? = null) {
    val placed = ends.flatten().filter { it.x != null && it.y != null && it.p != "M" }
    val centre = roundStats(ends).centre
    val spread = if (showSpread) groupSpread(ends.flatten()) else null
    // The gesture handler is set up once; reading the latest callback keeps each arrow going into the current end.
    val latestTap by rememberUpdatedState(onTap)
    var finger by remember { mutableStateOf<Offset?>(null) }
    val tap = if (onTap == null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            var last = down.position; finger = last
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                last = change.position; finger = last; change.consume()
            }
            finger = null
            val r = this.size.width.coerceAtMost(this.size.height) / 2f * 0.96f
            latestTap?.invoke(((last.x - this.size.width / 2f) / r).toDouble(), ((last.y - this.size.height / 2f) / r).toDouble())
        }
    }
    Canvas(Modifier.size(size).then(tap)) {
        val c = Offset(this.size.width / 2, this.size.height / 2); val r = this.size.minDimension / 2 * 0.96f
        drawFace(c, r, placed)
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
        finger?.let { f ->
            // The loupe sits above the finger (below it near the top edge) and shows the face 2.5x larger.
            val lr = this.size.minDimension * 0.2f; val k = 2.5f
            val lc = Offset(f.x.coerceIn(lr, this.size.width - lr), (f.y - lr * 1.6f).let { if (it < lr) f.y + lr * 1.6f else it })
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(lc, lr)) }) {
                drawCircle(Color.White, lr, lc)
                withTransform({ translate(lc.x - f.x * k, lc.y - f.y * k); scale(k, k, Offset.Zero) }) { drawFace(c, r, placed) }
                drawLine(Club.forestDeep, Offset(lc.x - lr * 0.3f, lc.y), Offset(lc.x + lr * 0.3f, lc.y), 2.5f)
                drawLine(Club.forestDeep, Offset(lc.x, lc.y - lr * 0.3f), Offset(lc.x, lc.y + lr * 0.3f), 2.5f)
            }
            drawCircle(Club.forestDeep, lr, lc, style = Stroke(5f))
            drawCircle(Club.forestDeep, r * 0.05f, f, style = Stroke(3f))
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
    // "session:<id>" combines every round of a training.
    val session = id.removePrefix("session:").takeIf { id.startsWith("session:") }?.let { trainingById(workspace, it) }
    val card = session?.first ?: scoreCards(workspace).find { it.id == id }
    val ends = session?.rounds?.flatMap { roundArrows(it.row) } ?: card?.row?.let(::roundArrows).orEmpty()
    ScreenShell(if (session != null) "Training statistics" else "Statistics", onBack, snackbar) {
        if (card == null || ends.isEmpty()) { EmptyState("No arrows on this round", "Only rounds entered arrow by arrow or imported from a scoring app have statistics."); return@ScreenShell }
        val st = roundStats(ends)
        Text(if (session != null) "${plainNumber(session.total)} / ${plainNumber(session.max)} · ${fmtDate(session.date)} · ${session.rounds.size} round${if (session.rounds.size == 1) "" else "s"}"
            else "${card.scoreLabel} · ${fmtDate(card.date)} · ${card.conditions}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** A member scores a round arrow by arrow. Route ids: "new" (free training), "std:WA 18m" (a standard round),
 *  "add:<session id>" (another round in the same training) or a round id to edit it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScoreEntryScreen(workspace: JsonObject, id: String, busy: Boolean, snackbar: SnackbarHostState, vm: SystemViewModel?, onBack: () -> Unit,
                     onSaved: (String) -> Unit = { onBack() }, onTotalOnly: (JsonObject) -> Unit) {
    val mine = scoreCards(workspace, workspace.text("user_id"))
    val existing = scoreCards(workspace).find { it.id == id }?.row
    val addTo = id.removePrefix("add:").takeIf { id.startsWith("add:") }?.let { trainingById(workspace, it) }
    val preset = STANDARD_ROUNDS.find { it.name == id.removePrefix("std:") }?.takeIf { id.startsWith("std:") }
    // Details come from the round being edited, else the training it joins, else the member's last round.
    val seed = existing ?: addTo?.rounds?.last()?.row ?: mine.firstOrNull()?.row
    val seedInfo = seed?.let(::roundInfo) ?: RoundInfo()
    val sessionInfo = (existing ?: addTo?.first?.row)?.let(::roundInfo)
    val sessionId = rememberSaveable(id) { sessionInfo?.sessionId?.ifBlank { null } ?: addTo?.sessionId ?: java.util.UUID.randomUUID().toString() }
    var date by rememberSaveable(id) { mutableStateOf((existing ?: addTo?.first?.row)?.text("training_date")?.take(10) ?: LocalDate.now().toString()) }
    var title by rememberSaveable(id) { mutableStateOf(sessionInfo?.title?.ifBlank { null } ?: preset?.name?.let { "$it · ${LocalDate.now()}" } ?: "Training ${LocalDate.now()}") }
    var standard by rememberSaveable(id) { mutableStateOf(existing?.let { roundInfo(it).standardRound }?.ifBlank { null } ?: preset?.name ?: addTo?.info?.standardRound ?: "Free training") }
    var plannedEnds by rememberSaveable(id) { mutableStateOf((existing?.let { roundInfo(it).plannedEnds } ?: preset?.ends ?: addTo?.info?.plannedEnds)?.toString().orEmpty()) }
    var environment by rememberSaveable(id) { mutableStateOf(seedInfo.environment.ifBlank { if ((preset?.distance ?: 18) <= 25) "Indoor" else "Outdoor" }) }
    var bow by rememberSaveable(id) { mutableStateOf(seedInfo.bow.ifBlank { "Recurve" }) }
    var arrowName by rememberSaveable(id) { mutableStateOf(seedInfo.arrow) }
    var distance by rememberSaveable(id) { mutableStateOf(preset?.distance?.toString() ?: seed?.text("distance")?.toDoubleOrNull()?.let(::plainNumber) ?: "18") }
    var face by rememberSaveable(id) { mutableStateOf(preset?.face ?: seed?.text("target_face")?.ifBlank { null } ?: "WA Full (40cm)") }
    var notes by rememberSaveable(id) { mutableStateOf(existing?.text("observations").orEmpty()) }
    var perEnd by rememberSaveable(id) { mutableIntStateOf(existing?.let { roundArrows(it).firstOrNull()?.size } ?: preset?.perEnd ?: seed?.let { roundArrows(it).firstOrNull()?.size } ?: 6) }
    // Ends kept as "9@0.1/0.2,M;8" so they survive rotation.
    var encoded by rememberSaveable(id) { mutableStateOf(existing?.let { encodeEnds(roundArrows(it)) }.orEmpty()) }
    val ends = decodeEnds(encoded)
    var onTarget by rememberSaveable(id) { mutableStateOf(true) }
    // New trainings open on their setup; another round in a training goes straight to scoring.
    var editingSetup by rememberSaveable(id) { mutableStateOf(existing == null && addTo == null) }
    // The end on screen; -1 means the end being shot.
    var viewing by rememberSaveable(id) { mutableIntStateOf(-1) }
    val planned = plannedEnds.toIntOrNull()
    val endFull = ends.lastOrNull()?.let { it.size >= perEnd } ?: true
    val finished = planned != null && ends.size >= planned && endFull
    fun add(a: Arrow) {
        if (finished) return
        val list = ends.map { it.toMutableList() }.toMutableList()
        if (list.isEmpty() || list.last().size >= perEnd) list.add(mutableListOf())
        list.last().add(a)
        encoded = encodeEnds(list); viewing = -1
    }
    fun undo() {
        val list = ends.map { it.toMutableList() }.toMutableList()
        if (list.isNotEmpty()) { list.last().removeAt(list.last().lastIndex); if (list.last().isEmpty()) list.removeAt(list.lastIndex) }
        encoded = encodeEnds(list); viewing = -1
    }
    val flat = ends.flatten(); val total = flat.sumOf { it.points }; val count = flat.size
    val ready = count > 0 && distance.toDoubleOrNull() != null && face.isNotBlank() && bow.isNotBlank() && title.isNotBlank() && standard.isNotBlank() &&
        runCatching { LocalDate.parse(date) }.isSuccess
    fun save() {
        vm?.save("save_round", buildJsonObject {
            put("p_action", "save_personal_training"); put("p_submit", false)
            put("p_data", buildJsonObject {
                existing?.let { put("id", it.text("id")); it.text("version").toLongOrNull()?.let { v -> put("version", v) } }
                put("training_date", date); put("distance", distance); put("target_face", face.trim()); put("category", bowCategory(bow.trim()))
                put("scoring_format", standard.trim()); put("observations", notes.trim()); put("score", total); put("maximum_score", count * 10)
                put("arrows", arrowsJson(ends.filter { it.isNotEmpty() }, RoundInfo(title.trim(), standard.trim(), environment, bow.trim(), arrowName.trim(), sessionId, planned)))
            })
        }) { onSaved(sessionId) }
    }
    val roundNo = (addTo?.rounds?.size ?: 0) + 1
    val endNo = if (viewing >= 0) viewing + 1 else if (ends.isEmpty() || (endFull && !finished)) ends.size + 1 else ends.size
    val shown = if (viewing >= 0) ends.getOrNull(viewing).orEmpty() else ends.lastOrNull()?.takeIf { !endFull || finished }.orEmpty()
    HeroScaffold(if (existing != null) "Edit round" else title, onBack, snackbar, overlap = 44.dp, busy = busy,
        heroExtra = { HeroStats(listOf(
            "${endNo}${planned?.let { "/$it" } ?: ""}" to "End",
            "$total/${count * 10}" to "Round ${if (existing != null) "" else roundNo.toString() + " "}· ${distance} m",
            (if (count == 0) "—" else (Math.round(total * 100.0 / count) / 100.0).toString()) to "Average")) },
        sticky = {
            OutlinedButton(onClick = { editingSetup = !editingSetup }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(999.dp)) {
                Text(if (editingSetup) "Score" else "Details", fontWeight = FontWeight.ExtraBold) }
            Button(onClick = { save() }, enabled = !busy && ready && vm != null, modifier = Modifier.weight(1.6f).height(50.dp), shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text(if (existing != null) "Save changes" else "Save round", fontWeight = FontWeight.ExtraBold, maxLines = 1) }
        }) {
        if (editingSetup) {
            RaisedCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it.take(80) }, label = { Text("Title") }, placeholder = { Text("e.g. Final Year T1 Week 9") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DateTimeField("Date", runCatching { LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull(),
                    { date = it.atZone(ZoneId.systemDefault()).toLocalDate().toString() }, dateOnly = true)
                Text("Round", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf("Free training" to "Free training") + STANDARD_ROUNDS.map { it.name to it.name }, standard) { pick ->
                    standard = pick
                    // A standard round sets its distance, face, arrows per end and number of ends.
                    STANDARD_ROUNDS.find { it.name == pick }?.let { r -> distance = r.distance.toString(); face = r.face; perEnd = r.perEnd; plannedEnds = r.ends.toString() }
                        ?: run { plannedEnds = "" }
                }
                Text(STANDARD_ROUNDS.find { it.name == standard }?.let { "${it.distance} m · ${it.face} · ${it.ends} ends of ${it.perEnd}" } ?: "Shoot as many ends as you like.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Environment", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf("Indoor" to "Indoor", "Outdoor" to "Outdoor"), environment) { environment = it }
                OutlinedTextField(bow, { bow = it.take(60) }, label = { Text("Bow") }, placeholder = { Text("e.g. Fortified Recurve") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(arrowName, { arrowName = it.take(60) }, label = { Text("Arrow (optional)") }, placeholder = { Text("e.g. Super Carbon") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Distance", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DISTANCES_M.forEach { d ->
                        val on = distance == d.toString()
                        Box(Modifier.width(64.dp).height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Club.forestDeep else Club.mint)
                            .clickable { distance = d.toString() }, contentAlignment = Alignment.Center) {
                            Text("$d m", color = if (on) Color.White else Club.forestDeep, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
                OutlinedTextField(distance, { distance = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = { Text("Or type a distance (m)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Target face", style = MaterialTheme.typography.labelLarge)
                RowGroup {
                    TARGET_FACES.forEachIndexed { i, (name, detail) ->
                        val on = faceName(face) == name
                        Row(Modifier.fillMaxWidth().clickable { face = "$name (${faceSize(face).ifBlank { "40cm" }})" }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.ExtraBold)
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            RadioButton(on, { face = "$name (${faceSize(face).ifBlank { "40cm" }})" })
                        }
                        if (i < TARGET_FACES.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    }
                }
                ChoiceChips(FACE_SIZES.map { it to it }, faceSize(face)) { face = "${faceName(face).ifBlank { "WA Full" }} ($it)" }
                Text("Arrows per end: $perEnd", style = MaterialTheme.typography.labelLarge)
                Slider(perEnd.toFloat(), { perEnd = it.toInt().coerceIn(1, 12) }, valueRange = 1f..12f, steps = 10, enabled = count == 0,
                    colors = SliderDefaults.colors(thumbColor = Club.forestDeep, activeTrackColor = Club.forest))
                if (count > 0) Text("Arrows per end is fixed once scoring starts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(notes, { notes = it.take(600) }, label = { Text("Notes (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Button(onClick = { editingSetup = false }, enabled = distance.toDoubleOrNull() != null && bow.isNotBlank() && title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep)) { Text("Start scoring", fontWeight = FontWeight.ExtraBold) }
            } }
            if (existing == null && addTo == null) TextButton(onClick = { onTotalOnly(newRoundSeed(workspace, mine.firstOrNull())) }, modifier = Modifier.fillMaxWidth()) {
                Text("Just enter the total instead") }
            return@HeroScaffold
        }
        RaisedCard(onClick = { editingSetup = true }) {
            Text("$standard · $environment · $distance m · $face · $bow", Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // This end's arrows, with dashed spaces for the ones still to shoot.
        RaisedCard { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { ArrowDot(it.p, 34.dp) }
                if (viewing < 0) repeat((perEnd - shown.size).coerceAtLeast(0)) {
                    Box(Modifier.size(34.dp).border(1.5.dp, Color(0xFFB5BFB8), CircleShape)) }
            }
            Text("${shown.sumOf { it.points }}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            IconButton(onClick = { undo() }, enabled = count > 0 && !busy) { Text("↶", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
        } }
        if (finished) NoticeCard("Round complete", "All ${planned} ends are in. Save the round, then add another or send the training to your coach.", Tone.POSITIVE)
        ChoiceChips(listOf("target" to "Tap the target", "keypad" to "Tap the score"), if (onTarget) "target" else "keypad") { onTarget = it == "target" }
        if (onTarget) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TargetPlot(listOf(shown), 320.dp, showSpread = shown.size > 1,
                    onTap = if (viewing >= 0 || finished) null else ({ x: Double, y: Double -> if (!busy && count < 1440) add(Arrow(scoreAt(x, y), x, y)) }))
            }
            OutlinedButton(onClick = { add(Arrow("M")) }, enabled = !busy && !finished && viewing < 0, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(999.dp)) { Text("Miss (off the face)") }
            Text("Press and hold on the target: a magnifier shows exactly where the arrow is. Lift your finger to place it. Arrows touching a line get the higher score.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else ARROW_KEYS.chunked(4).forEach { rowKeys ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowKeys.forEach { k ->
                    val (bg, fg) = arrowColors(k)
                    Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                        .let { if (bg == Color.White) it.border(1.5.dp, Color(0xFFC9D3C5), RoundedCornerShape(14.dp)) else it }
                        .clickable(enabled = !busy && count < 1440 && !finished && viewing < 0) { add(Arrow(k)) }, contentAlignment = Alignment.Center) {
                        Text(k, color = fg, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
        // Step back through finished ends to check them; scoring always continues on the newest end.
        val lastDone = if (endFull) ends.lastIndex else ends.lastIndex - 1
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { viewing = if (viewing < 0) lastDone else viewing - 1 }, enabled = lastDone >= 0 && viewing != 0,
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) { Text("Previous end") }
            OutlinedButton(onClick = { viewing = if (viewing in 0 until lastDone) viewing + 1 else -1 }, enabled = viewing >= 0,
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) { Text(if (viewing >= 0 && viewing >= lastDone) "Back to scoring" else "Next end") }
        }
        if (ends.isNotEmpty()) EndsCard(ends.filter { it.isNotEmpty() }) { viewing = if (it <= lastDone) it else -1 }
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
