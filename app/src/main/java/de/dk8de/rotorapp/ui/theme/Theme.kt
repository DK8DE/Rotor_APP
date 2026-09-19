package de.dk8de.rotorapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BridgeBg = Color(0xFF121212)
val BridgePanel = Color(0xFF1A1A1A)
val BridgeButton = Color(0xFF333333)
val BridgeButtonBorder = Color(0xFF555555)
val BridgeText = Color(0xFFE8E8E8)
val BridgeMuted = Color(0xFF9A9A9A)
val BridgeAccent = Color(0xFF5EB5F7)
val BridgeIst = Color(0xFF5EE07A)
val BridgeSoll = Color(0xFFFF6B6B)
val BridgeNeedle = BridgeIst
val BridgeTarget = BridgeSoll
val BridgeStop = Color(0xFFCC4444)
val BridgeRing = Color(0xFFFFFFFF)
val BridgeTick = Color(0xFFE0E0E0)

private val scheme = darkColorScheme(
    primary = BridgeAccent,
    onPrimary = Color(0xFF001018),
    secondary = BridgeIst,
    tertiary = BridgeStop,
    background = BridgeBg,
    onBackground = BridgeText,
    surface = BridgePanel,
    onSurface = BridgeText,
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = BridgeMuted,
    outline = BridgeButtonBorder,
    error = BridgeStop,
    onError = BridgeText,
)

@Composable
fun RotorAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
