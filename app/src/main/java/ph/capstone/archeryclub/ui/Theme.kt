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

private val LightColors = lightColorScheme(
    primary = Color(0xFF00532B), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F2EB), onPrimaryContainer = Color(0xFF003D20),
    secondary = Color(0xFF526359), secondaryContainer = Color(0xFFEEF3EF),
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE9EDE5),
    surfaceContainer = Color(0xFFF3F5F4), surfaceContainerHighest = Color(0xFFEEF1EF),
    onBackground = Color(0xFF131715), onSurface = Color(0xFF131715),
    onSurfaceVariant = Color(0xFF565F59),
    outline = Color(0xFF78817B), outlineVariant = Color(0xFFE2E7E3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AD4B8), primaryContainer = Color(0xFF174E3C),
    onPrimary = Color(0xFF073829), background = Color(0xFF121914), surface = Color(0xFF121914),
    surfaceContainerLow = Color(0xFF1A241E),
)

@Composable
fun ArcheryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = Shapes(small=RoundedCornerShape(8.dp),medium=RoundedCornerShape(12.dp),large=RoundedCornerShape(16.dp)),
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
