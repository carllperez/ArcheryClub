package ph.capstone.archeryclub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenShell(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ARCHERY CLUB", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) },
                navigationIcon = { onBack?.let { TextButton(onClick = it) { Text("Back") } } })
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().imePadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}

@Composable
fun InfoCard(title: String, text: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PreviewNotice() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text("LOCAL PREVIEW · Sample accounts only. Changes stay on this device; no club receives them.",
            Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun StatusBadge(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun TargetMark() {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(72.dp)) {
        drawCircle(primary, style = Stroke(width = 3.dp.toPx()))
        drawCircle(primary, radius = size.minDimension * .32f, style = Stroke(width = 2.dp.toPx()))
        drawCircle(accent, radius = size.minDimension * .11f)
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
        confirmButton = { TextButton(onClick = onDiscard) { Text("Discard changes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep editing") } })
}
