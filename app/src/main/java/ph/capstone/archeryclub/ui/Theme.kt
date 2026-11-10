package ph.capstone.archeryclub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.capstone.archeryclub.R

/** Brand tokens of the "forest and target" design. Every screen draws from these. */
object Club {
    val forestDeep = Color(0xFF183F27)
    val forest = Color(0xFF2B5E3C)
    val lime = Color(0xFFB9DC7A)
    val mint = Color(0xFFDDEFD3)
    val sage = Color(0xFFF2F6EF)
    val amberSoft = Color(0xFFFCEBC2)
    val amberInk = Color(0xFF7A4B00)
    val roseSoft = Color(0xFFFBE1DF)
    val roseInk = Color(0xFF8C1D18)
    val shadow = Color(0x33183F27)
    val line = Color(0xFFD5DED2)
}

/** Plus Jakarta Sans (SIL Open Font License, see assets/OFL-plus-jakarta-sans.txt). */
val Jakarta = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
)

private val LightColors = lightColorScheme(
    primary = Club.forest, onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F1E0), onPrimaryContainer = Club.forestDeep,
    secondary = Color(0xFF4F6354), secondaryContainer = Club.mint, onSecondaryContainer = Club.forestDeep,
    tertiary = Club.amberInk, tertiaryContainer = Club.amberSoft,
    background = Club.sage, surface = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE6ECE3),
    surfaceContainer = Color(0xFFEDF2EA), surfaceContainerHigh = Color(0xFFE6ECE3), surfaceContainerHighest = Color(0xFFE1E8DE),
    onBackground = Color(0xFF131715), onSurface = Color(0xFF131715),
    onSurfaceVariant = Color(0xFF565F59),
    outline = Color(0xFF78817B), outlineVariant = Club.line,
    error = Color(0xFFB3261E), errorContainer = Club.roseSoft, onErrorContainer = Club.roseInk,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8D5A2), onPrimary = Color(0xFF0B2A17),
    primaryContainer = Color(0xFF23402E), onPrimaryContainer = Club.mint,
    secondaryContainer = Color(0xFF2A3B30), background = Color(0xFF101612), surface = Color(0xFF18211B),
    surfaceContainerLowest = Color(0xFF141B16), surfaceContainerLow = Color(0xFF18211B), surfaceContainer = Color(0xFF1E2921),
    surfaceContainerHigh = Color(0xFF243027), surfaceContainerHighest = Color(0xFF2A372D),
    outlineVariant = Color(0xFF34423A),
)

private fun t(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) =
    TextStyle(fontFamily = Jakarta, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)

@Composable
fun ArcheryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp)),
        typography = Typography(
            displaySmall = t(36, 42, FontWeight.ExtraBold, -0.6),
            headlineLarge = t(30, 36, FontWeight.ExtraBold, -0.5),
            headlineMedium = t(26, 32, FontWeight.ExtraBold, -0.3),
            headlineSmall = t(22, 28, FontWeight.ExtraBold, -0.2),
            titleLarge = t(20, 26, FontWeight.ExtraBold),
            titleMedium = t(16, 22, FontWeight.Bold),
            titleSmall = t(14, 20, FontWeight.Bold),
            bodyLarge = t(16, 24, FontWeight.Normal),
            bodyMedium = t(14, 21, FontWeight.Normal),
            bodySmall = t(12, 17, FontWeight.Normal),
            labelLarge = t(14, 20, FontWeight.Bold),
            labelMedium = t(12, 16, FontWeight.Bold, 0.2),
            labelSmall = t(11, 14, FontWeight.Bold, 0.4),
        ), content = content)
}
