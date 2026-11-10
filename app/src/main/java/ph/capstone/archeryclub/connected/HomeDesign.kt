package ph.capstone.archeryclub.connected

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
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
import ph.capstone.archeryclub.ui.ClubIcon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/* The member home: a forest-green hero with target rings, a raised "next session" card,
 * two quick tiles and tappable rows. Everything is drawn in Compose (no image assets),
 * so it scales on any screen and stays crisp in dark mode. */

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

// ------------------------------------------------------------------ hero layouts

/** White status-bar icons while a dark green hero is showing; restores the theme default on leave. */
@Composable
fun LightStatusBarIcons() {
    val view = androidx.compose.ui.platform.LocalView.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = false
        onDispose { bars?.isAppearanceLightStatusBars = !dark }
    }
}

/** The green band with target rings used by every hero layout. */
@Composable
fun HeroBand(modifier: Modifier = Modifier, bottomPadding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Club.forestDeep, Club.forest))).statusBarsPadding()) {
        TargetRings(Modifier.matchParentSize(), Color.White.copy(alpha = .07f), accent = Club.lime.copy(alpha = .5f))
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/**
 * Hero screen: green band (back button, eyebrow pills, big white title, optional extra like tabs or stats),
 * then cards pulled up over the band by [overlap]. A sticky action bar or the tab bar sits at the bottom.
 */
@Composable
fun HeroScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    overlap: Dp = 44.dp,
    busy: Boolean = false,
    topActions: @Composable RowScope.() -> Unit = {},
    eyebrow: (@Composable RowScope.() -> Unit)? = null,
    heroExtra: (@Composable ColumnScope.() -> Unit)? = null,
    sticky: (@Composable RowScope.() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    LightStatusBarIcons()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        bottomBar = {
            if (sticky != null) Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = sticky)
            } else bottomBar()
        }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Box {
                HeroBand(bottomPadding = overlap + 22.dp) {
                    Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        onBack?.let { ph.capstone.archeryclub.ui.RoundButton("back", "Back", it, onDark = true) }
                        Spacer(Modifier.weight(1f)); topActions()
                    }
                    eyebrow?.let { Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = it) }
                    Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                    heroExtra?.invoke(this)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding(), color = Club.lime, trackColor = Color.Transparent)
            }
            Column(Modifier.offset(y = -overlap).padding(horizontal = 16.dp).widthIn(max = 640.dp).padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** Segmented control on the green hero (Open · My schedule · History). */
@Composable
fun HeroTabs(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(CircleShape).background(Color.White.copy(alpha = .13f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            val bg by androidx.compose.animation.animateColorAsState(if (on) Color.White else Color.Transparent, label = "tab")
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(CircleShape).background(bg)
                .clickable(role = Role.Tab) { onSelect(key) }.semantics { this.selected = on }, contentAlignment = Alignment.Center) {
                Text(label, color = if (on) Club.forestDeep else Color.White, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** Translucent stat chips inside the hero (e.g. 86% attendance). */
@Composable
fun HeroStats(stats: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.forEach { (value, label) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .13f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(value, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(label, color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Card section with icon rows separated by hairlines (settings-style lists). */
@Composable
fun RowGroup(content: @Composable ColumnScope.() -> Unit) =
    RaisedCard(radius = 18.dp) { Column(content = content) }

@Composable
fun GroupRow(icon: String, title: String, value: String = "", tint: Color = Club.forest, chip: Color = Club.mint, divider: Boolean = true,
             trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 14.dp, vertical = 12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(chip), contentAlignment = Alignment.Center) { ClubIcon(icon, Modifier.size(18.dp), tint) }
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        trailing?.invoke()
        if (onClick != null) ClubIcon("chevron", Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (divider) HorizontalDivider(Modifier.padding(start = 62.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
}

/** Dark pill call-to-action used in sticky bars (Register, Save). */
@Composable
fun StickyButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 50.dp), shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Club.forestDeep, contentColor = Color.White), contentPadding = PaddingValues(horizontal = 26.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(8.dp)); ClubIcon("arrow", Modifier.size(18.dp), Color.White)
    }

/** Segmented progress for multi-step processes, drawn on the green hero (lime = done, white ring = now). */
@Composable
fun ProgressSegments(steps: List<Milestone>, labels: List<String> = listOf("Details", "Submitted", "Screening", "Approved", "Active")) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEach { m ->
                val color by androidx.compose.animation.animateColorAsState(
                    when { m.done -> Club.lime; m.current -> Color.White.copy(alpha = .55f); else -> Color.White.copy(alpha = .2f) }, label = "step")
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(color))
            }
        }
        Row {
            steps.forEachIndexed { i, m ->
                Text(labels.getOrElse(i) { m.title }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = if (m.done || m.current) 1f else .7f), maxLines = 1)
            }
        }
    }
}
