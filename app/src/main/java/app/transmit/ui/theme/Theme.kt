package app.transmit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Colours Material does not cover: translation accents and feedback verdicts. */
@Immutable
data class TransmitColors(
    val aiTint: Color,
    val good: Color,
    val warn: Color,
    val bad: Color,
)

val LocalTransmitColors = staticCompositionLocalOf {
    TransmitColors(Color(0xFF6750A4), Color(0xFF2E7D32), Color(0xFFF9A825), Color(0xFFC62828))
}

@Composable
fun TransmitTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val extra = if (dark) {
        TransmitColors(aiTint = scheme.tertiary, good = Color(0xFF81C784), warn = Color(0xFFFFD54F), bad = Color(0xFFEF9A9A))
    } else {
        TransmitColors(aiTint = scheme.tertiary, good = Color(0xFF2E7D32), warn = Color(0xFFB26A00), bad = Color(0xFFC62828))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalTransmitColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
