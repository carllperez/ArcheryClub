package ph.capstone.archeryclub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/** Round icon button used for back, close and header actions; white on light pages, translucent on the green hero. */
@Composable
fun RoundButton(icon: String, label: String, onClick: () -> Unit, onDark: Boolean = false, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.size(44.dp).semantics { contentDescription = label }, shape = CircleShape,
        color = if (onDark) Color.White.copy(alpha = .16f) else MaterialTheme.colorScheme.surface,
        contentColor = if (onDark) Color.White else MaterialTheme.colorScheme.primary,
        shadowElevation = if (onDark) 0.dp else 3.dp) {
        Box(contentAlignment = Alignment.Center) { ClubIcon(icon, Modifier.size(20.dp)) }
    }
}

/**
 * Every inner screen: sage page, round back button and bold title, a scrolling column of cards,
 * and an optional sticky action bar (e.g. Register, Save) pinned above the system bar.
 */
@Composable
fun ScreenShell(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    stickyAction: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (stickyAction != null) Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = stickyAction)
            } else bottomBar()
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                onBack?.let { RoundButton("back", "Back", it) }
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() },
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                actions()
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                key(title) {
                    Column(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding()
                            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun TargetMark() {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(64.dp)) {
        drawCircle(primary.copy(alpha = .25f), style = Stroke(width = 3.dp.toPx()))
        drawCircle(primary.copy(alpha = .55f), radius = size.minDimension * .3f, style = Stroke(width = 3.dp.toPx()))
        drawCircle(Club.lime, radius = size.minDimension * .12f)
    }
}

@Composable
fun SetupScreen(title: String, description: String) {
    ScreenShell(title) { TargetMark(); InfoCard("Project foundation", description) }
}

@Composable
fun DiscardDialog(onDismiss: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Discard unsaved changes?") },
        text = { Text("Go back to save your changes before leaving this screen.") },
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard changes", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep editing") } })
}
