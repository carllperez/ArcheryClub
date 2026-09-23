package ph.capstone.archeryclub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF165A45), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBE2), onPrimaryContainer = Color(0xFF123C30),
    secondary = Color(0xFF775B2E), secondaryContainer = Color(0xFFF1E5CB),
    background = Color(0xFFF8F7F2), surface = Color(0xFFF8F7F2),
    surfaceContainerLow = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE9EDE5),
    onBackground = Color(0xFF202D26), onSurface = Color(0xFF202D26),
    outline = Color(0xFF727D72),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AD4B8), primaryContainer = Color(0xFF174E3C),
    onPrimary = Color(0xFF073829), background = Color(0xFF121914), surface = Color(0xFF121914),
    surfaceContainerLow = Color(0xFF1A241E),
)

@Composable
fun ArcheryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
