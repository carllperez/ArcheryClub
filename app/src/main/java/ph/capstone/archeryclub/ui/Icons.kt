package ph.capstone.archeryclub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp


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
            "back" -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "search" -> { drawCircle(tint, 7 * u, Offset(11 * u, 11 * u), style = stroke); line(20f, 20f, 16.2f, 16.2f) }
            "check" -> { line(5f, 12.5f, 10f, 17.5f); line(10f, 17.5f, 20f, 7f) }
            "clock" -> { drawCircle(tint, 9 * u, Offset(12 * u, 12 * u), style = stroke); line(12f, 7f, 12f, 12f); line(12f, 12f, 15f, 14f) }
            "doc" -> path { moveTo(6 * u, 3 * u); lineTo(14 * u, 3 * u); lineTo(18 * u, 7 * u); lineTo(18 * u, 21 * u); lineTo(6 * u, 21 * u); close(); moveTo(14 * u, 3 * u); lineTo(14 * u, 7 * u); lineTo(18 * u, 7 * u) }
            "upload" -> { line(12f, 16f, 12f, 4f); line(7f, 9f, 12f, 4f); line(17f, 9f, 12f, 4f); line(4f, 16f, 4f, 20f); line(4f, 20f, 20f, 20f); line(20f, 20f, 20f, 16f) }
            "lock" -> { drawRoundRect(tint, Offset(5 * u, 11 * u), Size(14 * u, 10 * u), CornerRadius(2 * u), style = stroke); path { moveTo(8 * u, 11 * u); lineTo(8 * u, 8 * u); cubicTo(8 * u, 3 * u, 16 * u, 3 * u, 16 * u, 8 * u); lineTo(16 * u, 11 * u) } }
            "users" -> { drawCircle(tint, 3.5f * u, Offset(9 * u, 8 * u), style = stroke); path { moveTo(2.5f * u, 20 * u); cubicTo(2.5f * u, 15.5f * u, 15.5f * u, 15.5f * u, 15.5f * u, 20 * u) }; path { moveTo(16 * u, 4.5f * u); cubicTo(19 * u, 5 * u, 19 * u, 11 * u, 16 * u, 11.5f * u); moveTo(18 * u, 14.5f * u); cubicTo(20 * u, 15.3f * u, 21.5f * u, 17 * u, 21.5f * u, 20 * u) } }
            "chart" -> { line(4f, 20f, 4f, 10f); line(10f, 20f, 10f, 4f); line(16f, 20f, 16f, 13f); line(2f, 20f, 22f, 20f) }
            "money" -> { drawRoundRect(tint, Offset(3 * u, 6 * u), Size(18 * u, 13 * u), CornerRadius(2 * u), style = stroke); line(3f, 10f, 21f, 10f) }
            "vote" -> path { moveTo(4 * u, 11 * u); lineTo(20 * u, 11 * u); lineTo(20 * u, 20 * u); lineTo(4 * u, 20 * u); close(); moveTo(9 * u, 11 * u); lineTo(9 * u, 5 * u); lineTo(15 * u, 5 * u); lineTo(15 * u, 11 * u) }
            "settings" -> { drawCircle(tint, 3 * u, Offset(12 * u, 12 * u), style = stroke); line(12f, 2f, 12f, 5f); line(12f, 19f, 12f, 22f); line(2f, 12f, 5f, 12f); line(19f, 12f, 22f, 12f); line(5f, 5f, 7f, 7f); line(17f, 17f, 19f, 19f); line(5f, 19f, 7f, 17f); line(17f, 7f, 19f, 5f) }
            "plus" -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            "share" -> { line(12f, 15f, 12f, 3f); line(7f, 8f, 12f, 3f); line(17f, 8f, 12f, 3f); line(5f, 14f, 5f, 20f); line(5f, 20f, 19f, 20f); line(19f, 20f, 19f, 14f) }
            "warning" -> { path { moveTo(12 * u, 3 * u); lineTo(22 * u, 21 * u); lineTo(2 * u, 21 * u); close() }; line(12f, 10f, 12f, 15f); line(12f, 18f, 12f, 18.5f) }
            "qr" -> { drawRoundRect(tint, Offset(3 * u, 3 * u), Size(7 * u, 7 * u), CornerRadius(1 * u), style = stroke); drawRoundRect(tint, Offset(14 * u, 3 * u), Size(7 * u, 7 * u), CornerRadius(1 * u), style = stroke); drawRoundRect(tint, Offset(3 * u, 14 * u), Size(7 * u, 7 * u), CornerRadius(1 * u), style = stroke); line(14f, 14f, 21f, 21f); line(21f, 14f, 14f, 21f) }
            else -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) } // chevron
        }
    }
}

