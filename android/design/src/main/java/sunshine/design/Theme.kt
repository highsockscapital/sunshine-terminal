// Sunshine Design System — Android Compose spec (generated from src/tokens.json)
// Do not hand-edit hex values here; edit tokens.json + src/theme.js instead.
package sunshine.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object SunshineTokens {
    val windowBackground = Color(0xFFF6F3E7)
    val textPrimary = Color(0xFF161610)
    val textSecondary = Color(0xFF5C5C55)
    val cardSurface = Color(0xFFFFFFFF)
    val surfaceVariant = Color(0xFFF5F5E6)
    val primaryAccent = Color(0xFFFF9E43)
    val onPrimaryAccent = Color(0xFF161610)
    val strokeBorder = Color(0xFF161610)
    val strokeBorderLight = Color(0xFFE0E0D6)
    val inputBorder = Color(0xFF161616)
    val chatBubbleBg = Color(0xFFFFE8CF)
    val chatBubbleText = Color(0xFF794C12)
    val error = Color(0xFFC62828)
}

object SunshineShape {
    val badge = RoundedCornerShape(8.dp)
    val canvas = RoundedCornerShape(12.dp) // terminal canvas & code blocks
    val modal = RoundedCornerShape(16.dp)
    val fab = RoundedCornerShape(24.dp)
}

// Elevation: flat 0dp terminal grid/embedded, 1dp file-manager sidebar.
object SunshineElevation {
    const val flat = 0
    const val sidebar = 1
}

fun sunshineColorScheme(): ColorScheme = lightColorScheme(
    background = SunshineTokens.windowBackground,
    onBackground = SunshineTokens.textPrimary,
    surface = SunshineTokens.cardSurface,
    onSurface = SunshineTokens.textPrimary,
    surfaceVariant = SunshineTokens.surfaceVariant,
    onSurfaceVariant = SunshineTokens.textSecondary,
    primary = SunshineTokens.primaryAccent,
    onPrimary = SunshineTokens.onPrimaryAccent,
    outline = SunshineTokens.strokeBorder,
    outlineVariant = SunshineTokens.strokeBorderLight,
    error = SunshineTokens.error
)

fun sunshineTypography(): Typography = Typography(
    // General labels: Inter (add FontFamily when bundled)
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp),
    // Terminal screen + code viewers: Monospace 13sp / 18sp line height
    bodySmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
)
