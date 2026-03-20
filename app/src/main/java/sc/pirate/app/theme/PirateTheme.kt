package sc.pirate.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

private val PirateShapes = Shapes().copy(
  small = RoundedCornerShape(PirateTokens.radii.lg),
  medium = RoundedCornerShape(PirateTokens.radii.xl),
  large = RoundedCornerShape(PirateTokens.radii.xl),
  extraLarge = RoundedCornerShape(PirateTokens.radii.x2l),
)

private val PirateTypography = Typography().copy(
  labelLarge = Typography().labelLarge.copy(fontSize = 16.sp),
)

private val PirateDarkColors = darkColorScheme(
  background = PirateTokens.darkColors.bgPage,
  onBackground = PirateTokens.darkColors.textPrimary,

  surface = PirateTokens.darkColors.bgSurface,
  onSurface = PirateTokens.darkColors.textPrimary,

  surfaceVariant = PirateTokens.darkColors.surfaceInteractive,
  onSurfaceVariant = PirateTokens.darkColors.textSecondary,

  primary = PirateTokens.darkColors.accentBrand,
  onPrimary = PirateTokens.darkColors.textOnAccent,
  primaryContainer = PirateTokens.darkColors.surfaceAccent,
  onPrimaryContainer = PirateTokens.darkColors.accentBrand,

  secondary = PirateTokens.darkColors.textSecondary,
  onSecondary = PirateTokens.darkColors.bgPage,
  secondaryContainer = PirateTokens.darkColors.surfaceInteractive,
  onSecondaryContainer = PirateTokens.darkColors.textPrimary,

  tertiary = PirateTokens.darkColors.accentSuccess,
  onTertiary = Color(0xFF102014),
  tertiaryContainer = PirateTokens.darkColors.surfaceSubtle,
  onTertiaryContainer = PirateTokens.darkColors.accentSuccess,

  error = PirateTokens.darkColors.accentDanger,
  onError = PirateTokens.darkColors.textOnAccent,
  errorContainer = PirateTokens.darkColors.surfaceDanger,
  onErrorContainer = PirateTokens.darkColors.accentDanger,

  outline = PirateTokens.darkColors.borderDefault,
  outlineVariant = PirateTokens.darkColors.borderSoft,
)

@Composable
fun PirateTheme(content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalPirateColors provides PirateTokens.darkColors,
    LocalPirateRadii provides PirateTokens.radii,
    LocalPirateElevation provides PirateTokens.elevation,
  ) {
    MaterialTheme(
      colorScheme = PirateDarkColors,
      shapes = PirateShapes,
      typography = PirateTypography,
      content = content,
    )
  }
}
