package ph.capstone.archeryclub.connected

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.capstone.archeryclub.ui.Club
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/* The member home: a forest-green hero with target rings, a raised "next session" card,
 * two quick tiles and tappable rows. Everything is drawn in Compose (no image assets),
 * so it scales on any screen and stays crisp in dark mode. */

// ------------------------------------------------------------------ icons

/** Line icons drawn on a 24-unit grid, stroke 1.8, matching the bottom bar. */
@Composable
fun ClubIcon(kind: String, modifier: Modifier = Modifier.size(24.dp), tint: Color = LocalContentColor.current) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.8f * u, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x * u, y * u), Offset(x2 * u, y2 * u), 1.8f * u, StrokeCap.Round)
        fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), tint, style = stroke)
        when (kind) {
            "home" -> path { moveTo(3 * u, 10.5f * u); lineTo(12 * u, 3 * u); lineTo(21 * u, 10.5f * u); lineTo(21 * u, 21 * u); lineTo(14.5f * u, 21 * u); lineTo(14.5f * u, 14.5f * u); lineTo(9.5f * u, 14.5f * u); lineTo(9.5f * u, 21 * u); lineTo(3 * u, 21 * u); close() }
            "activities", "calendar" -> { drawRoundRect(tint, Offset(3 * u, 5 * u), Size(18 * u, 16 * u), CornerRadius(2.5f * u), style = stroke); line(3f, 10f, 21f, 10f); line(8f, 2.5f, 8f, 7f); line(16f, 2.5f, 16f, 7f) }
            "gear", "box" -> path { moveTo(12 * u, 2.5f * u); lineTo(20.5f * u, 7 * u); lineTo(20.5f * u, 17 * u); lineTo(12 * u, 21.5f * u); lineTo(3.5f * u, 17 * u); lineTo(3.5f * u, 7 * u); close(); moveTo(3.5f * u, 7 * u); lineTo(12 * u, 11.5f * u); lineTo(20.5f * u, 7 * u); moveTo(12 * u, 11.5f * u); lineTo(12 * u, 21.5f * u) }
            "profile", "account" -> { drawCircle(tint, 4 * u, Offset(12 * u, 8 * u), style = stroke); path { moveTo(4 * u, 21 * u); cubicTo(4 * u, 15.5f * u, 20 * u, 15.5f * u, 20 * u, 21 * u) } }
            "modules" -> for (x in listOf(3f, 14f)) for (y in listOf(3f, 14f)) drawRoundRect(tint, Offset(x * u, y * u), Size(7 * u, 7 * u), CornerRadius(1.5f * u), style = stroke)
            "target" -> { drawCircle(tint, 9 * u, Offset(12 * u, 12 * u), style = stroke); drawCircle(tint, 5 * u, Offset(12 * u, 12 * u), style = stroke); drawCircle(tint, 1.6f * u, Offset(12 * u, 12 * u)) }
            "arrows" -> { line(4f, 4f, 20f, 20f); line(20f, 4f, 4f, 20f); line(4f, 4f, 8f, 4.5f); line(4f, 4f, 4.5f, 8f); line(20f, 4f, 16f, 4.5f); line(20f, 4f, 19.5f, 8f) }
            "pin" -> { path { moveTo(12 * u, 21.5f * u); cubicTo(6 * u, 15 * u, 5 * u, 12 * u, 5 * u, 9.5f * u); cubicTo(5 * u, 5.5f * u, 8 * u, 2.5f * u, 12 * u, 2.5f * u); cubicTo(16 * u, 2.5f * u, 19 * u, 5.5f * u, 19 * u, 9.5f * u); cubicTo(19 * u, 12 * u, 18 * u, 15 * u, 12 * u, 21.5f * u); close() }; drawCircle(tint, 2.5f * u, Offset(12 * u, 9.5f * u), style = stroke) }
            "arrow" -> { line(4f, 12f, 19f, 12f); line(13f, 6f, 19f, 12f); line(13f, 18f, 19f, 12f) }
            "bow" -> { path { moveTo(7 * u, 2.5f * u); cubicTo(15 * u, 6 * u, 15 * u, 18 * u, 7 * u, 21.5f * u) }; line(7f, 2.5f, 7f, 21.5f); line(4f, 12f, 20f, 12f); line(17f, 9f, 20f, 12f); line(17f, 15f, 20f, 12f) }
            "bell" -> { path { moveTo(6 * u, 17 * u); lineTo(6 * u, 11 * u); cubicTo(6 * u, 7 * u, 8.5f * u, 4.5f * u, 12 * u, 4.5f * u); cubicTo(15.5f * u, 4.5f * u, 18 * u, 7 * u, 18 * u, 11 * u); lineTo(18 * u, 17 * u); lineTo(19.5f * u, 18.5f * u); lineTo(4.5f * u, 18.5f * u); close() }; line(10.5f, 21f, 13.5f, 21f) }
            "tasks" -> { line(9f, 6f, 20f, 6f); line(9f, 12f, 20f, 12f); line(9f, 18f, 20f, 18f); line(3.5f, 6f, 4.5f, 7f); line(4.5f, 7f, 6.5f, 5f); line(3.5f, 12f, 4.5f, 13f); line(4.5f, 13f, 6.5f, 11f); line(3.5f, 18f, 4.5f, 19f); line(4.5f, 19f, 6.5f, 17f) }
            else -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) } // chevron
        }
    }
}

/** Faint concentric rings, the club's target motif, drawn behind hero and tiles. */
@Composable
private fun TargetRings(modifier: Modifier, color: Color, rings: Int = 5, accent: Color? = null) = Canvas(modifier) {
    val c = Offset(size.width, size.height * .45f)
    val max = size.height * .9f
    for (i in 1..rings) drawCircle(color, max * i / rings, c, style = Stroke(max / rings * .42f))
    accent?.let { drawArc(it, 200f, 70f, false, Offset(c.x - max, c.y - max), Size(max * 2, max * 2), style = Stroke(max / rings * .42f, cap = StrokeCap.Round)) }
}

// ------------------------------------------------------------------ hero

@Composable
fun HomeHero(clubName: String, badge: String, greeting: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Club.forestDeep, Club.forest))).statusBarsPadding()) {
        TargetRings(Modifier.matchParentSize(), Color.White.copy(alpha = .07f), accent = Club.lime.copy(alpha = .55f))
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClubIcon("bow", Modifier.size(22.dp), Color.White)
                Text(clubName.uppercase(Locale.getDefault()), color = Color.White, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.2.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (badge.isNotBlank()) Pill(badge.uppercase(Locale.getDefault()), Club.mint, Club.forestDeep)
            }
            Text(greeting, color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)
            Box(Modifier.size(36.dp, 3.dp).clip(CircleShape).background(Club.lime))
        }
    }
}

@Composable
fun Pill(text: String, container: Color, content: Color) = Surface(shape = CircleShape, color = container) {
    Text(text, color = content, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Raised white card with soft shadow; the home's building block. */
@Composable
fun RaisedCard(modifier: Modifier = Modifier, radius: Dp = 20.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Surface(modifier.fillMaxWidth().shadow(10.dp, shape, ambientColor = Club.shadow, spotColor = Club.shadow)
        .then(if (onClick != null) Modifier.clip(shape).clickable(role = Role.Button, onClick = onClick) else Modifier),
        shape = shape, color = MaterialTheme.colorScheme.surface) { Column(content = content) }
}

// ------------------------------------------------------------------ next session

data class SessionCardData(val title: String, val start: Instant?, val venue: String, val status: String = "")

@Composable
private fun DateTile(start: Instant?) {
    val z = start?.atZone(ZoneId.systemDefault())
    Box(Modifier.size(78.dp, 100.dp).clip(RoundedCornerShape(14.dp)).background(Brush.verticalGradient(listOf(Club.forest, Club.forestDeep)))) {
        TargetRings(Modifier.matchParentSize(), Color.White.copy(alpha = .08f), rings = 3)
        Column(Modifier.padding(10.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(z?.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))?.uppercase().orEmpty(), color = Club.mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(z?.dayOfMonth?.toString() ?: "–", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
            Text(z?.format(DateTimeFormatter.ofPattern("MMM", Locale.getDefault()))?.uppercase().orEmpty(), color = Color.White.copy(alpha = .8f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun NextSessionCard(session: SessionCardData?, onOpen: () -> Unit, onBrowse: () -> Unit, modifier: Modifier = Modifier) = RaisedCard(modifier) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NEXT SESSION", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (session != null) Pill(countdownLabel(session.start), Club.amberSoft, Club.amberInk)
        }
        if (session == null) {
            Text("No session booked yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Browse open activities and register for your next practice.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PillButton("Browse activities", onBrowse)
        } else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DateTile(session.start)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ClubIcon("pin", Modifier.size(15.dp).padding(top = 2.dp), Club.forest)
                    Text(listOfNotNull(session.start?.let { fmtDateTime(it) }, session.venue.takeIf { it.isNotBlank() }).joinToString(" @ "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                if (session.status.isNotBlank()) Text(session.status, style = MaterialTheme.typography.labelMedium, color = Club.forest)
                Spacer(Modifier.height(2.dp))
                PillButton("View Session Details", onOpen)
            }
        }
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 46.dp), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep, contentColor = Color.White), contentPadding = PaddingValues(horizontal = 16.dp)) {
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.width(8.dp)); ClubIcon("arrow", Modifier.size(18.dp), Color.White)
    }

// ------------------------------------------------------------------ tiles and rows

@Composable
fun HomeTile(icon: String, value: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.shadow(6.dp, shape, ambientColor = Club.shadow, spotColor = Club.shadow).clip(shape)
        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer)))
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "$value $label" }) {
        TargetRings(Modifier.matchParentSize(), Club.forest.copy(alpha = .06f), rings = 4)
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ClubIcon(icon, Modifier.size(24.dp), Club.forest)
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, lineHeight = 32.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                ClubIcon("chevron", Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun HomeRow(icon: String, title: String, subtitle: String = "", trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) =
    RaisedCard(radius = 16.dp, onClick = onClick) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Club.mint), contentAlignment = Alignment.Center) { ClubIcon(icon, Modifier.size(20.dp), Club.forest) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
            ClubIcon("chevron", Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

/** Percentage pill coloured by how complete something is (green ≥ 70, amber ≥ 30, red below). */
@Composable
fun CompletionPill(percent: Int) = when {
    percent >= 70 -> Pill("$percent% Complete", Club.mint, Club.forestDeep)
    percent >= 30 -> Pill("$percent% Complete", Club.amberSoft, Club.amberInk)
    else -> Pill("$percent% Complete", Club.roseSoft, Club.roseInk)
}

// ------------------------------------------------------------------ bottom bar

@Composable
fun ClubBottomBar(tabs: List<Pair<String, String>>, selected: String, badges: Map<String, Int> = emptyMap(), onSelect: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp)) {
            tabs.forEach { (key, label) ->
                val on = key == selected
                val color = if (on) Club.forest else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab, onClick = { onSelect(key) }).semantics { contentDescription = label },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    BadgedBox(badge = { badges[key]?.takeIf { it > 0 }?.let { Badge { Text("$it") } } }) { ClubIcon(key, Modifier.size(24.dp), color) }
                    Spacer(Modifier.height(4.dp))
                    Text(label, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = color)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(28.dp, 3.dp).clip(CircleShape).background(if (on) Club.forest else Color.Transparent))
                }
            }
        }
    }
}
