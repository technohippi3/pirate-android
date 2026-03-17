package com.pirate.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

private val PirateShapes = Shapes().copy(
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(14.dp),
  large = RoundedCornerShape(14.dp),
  extraLarge = RoundedCornerShape(16.dp),
)

private val PirateTypography = Typography().copy(
  labelLarge = Typography().labelLarge.copy(fontSize = 16.sp),
)

private val PirateDarkColors = darkColorScheme(
  background = Color(0xFF171717),
  onBackground = Color(0xFFFAFAFA),

  surface = Color(0xFF1C1C1C),
  onSurface = Color(0xFFFAFAFA),

  surfaceVariant = Color(0xFF262626),
  onSurfaceVariant = Color(0xFFD4D4D4),

  primary = Color(0xFF89B4FA),
  onPrimary = Color(0xFFFFFFFF),
  primaryContainer = Color(0xFF1E2D40),
  onPrimaryContainer = Color(0xFF89B4FA),

  secondary = Color(0xFFcba6f7),
  onSecondary = Color(0xFFFFFFFF),
  secondaryContainer = Color(0xFF2E2040),
  onSecondaryContainer = Color(0xFFcba6f7),

  tertiary = Color(0xFFA6E3A1),
  onTertiary = Color(0xFFFFFFFF),

  outline = Color(0xFF404040),
  outlineVariant = Color(0xFF363636),
)

@Composable
fun PirateTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = PirateDarkColors,
    shapes = PirateShapes,
    typography = PirateTypography,
    content = content,
  )
}
