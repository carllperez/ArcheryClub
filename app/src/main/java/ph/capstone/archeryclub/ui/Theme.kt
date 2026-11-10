package ph.capstone.archeryclub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand tokens from the "forest and target" home design; the colour scheme below is derived from them. */
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
}

private val LightColors = lightColorScheme(
    primary = Club.forest, onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F1E0), onPrimaryContainer = Club.forestDeep,
    secondary = Color(0xFF4F6354), secondaryContainer = Club.mint,
    background = Club.sage, surface = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE6ECE3),
    surfaceContainer = Color(0xFFEDF2EA), surfaceContainerHighest = Color(0xFFE6ECE3),
    onBackground = Color(0xFF131715), onSurface = Color(0xFF131715),
    onSurfaceVariant = Color(0xFF565F59),
    outline = Color(0xFF78817B), outlineVariant = Color(0xFFE2E7E3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8D5A2), primaryContainer = Color(0xFF23402E),
    onPrimary = Color(0xFF0B2A17), background = Color(0xFF101612), surface = Color(0xFF18211B),
    surfaceContainerLow = Color(0xFF18211B), surfaceContainer = Color(0xFF1E2921),
)

@Composable
fun ArcheryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),large=RoundedCornerShape(20.dp)),
        typography = Typography(
            headlineLarge=TextStyle(fontSize=32.sp,lineHeight=38.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.6).sp),
            headlineMedium=TextStyle(fontSize=28.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.4).sp),
            titleLarge=TextStyle(fontSize=22.sp,lineHeight=28.sp,fontWeight=FontWeight.SemiBold),
            titleMedium=TextStyle(fontSize=17.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold),
            bodyLarge=TextStyle(fontSize=16.sp,lineHeight=24.sp),
            bodyMedium=TextStyle(fontSize=14.sp,lineHeight=21.sp),
            labelLarge=TextStyle(fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold)
        ),content = content)
}
