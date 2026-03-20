package sc.pirate.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val WebDarkBackground = Color(0xFF262624)
private val WebDarkForeground = Color(0xFFC3C0B6)
private val WebDarkElevated = Color(0xFF30302E)
private val WebDarkPrimary = Color(0xFFD97757)
private val WebDarkMuted = Color(0xFF1B1B19)
private val WebDarkMutedForeground = Color(0xFFB7B5A9)
private val WebDarkBorder = Color(0xFF3E3E38)
private val WebDarkInput = Color(0xFF52514A)
private val WebDarkDestructive = Color(0xFFEF4444)

private fun surfaceFrom(base: Color, alpha: Float, backdrop: Color = WebDarkBackground): Color =
  base.copy(alpha = alpha).compositeOver(backdrop)

@Immutable
data class PirateColorTokens(
  val bgPage: Color,
  val bgSurface: Color,
  val bgElevated: Color,
  val bgOverlay: Color,
  val surfaceHoverSubtle: Color,
  val surfaceSubtle: Color,
  val surfaceInteractive: Color,
  val surfaceSkeleton: Color,
  val surfaceDisabled: Color,
  val surfaceAccent: Color,
  val surfaceDanger: Color,
  val surfaceFrosted: Color,
  val surfaceCardGlass: Color,
  val surfaceToastError: Color,
  val surfaceToastInfo: Color,
  val surfaceToastSuccess: Color,
  val surfaceToastWarning: Color,
  val textPrimary: Color,
  val textSecondary: Color,
  val textDisabled: Color,
  val textOnAccent: Color,
  val borderDefault: Color,
  val borderSoft: Color,
  val borderStrong: Color,
  val borderOnDark: Color,
  val accentBrand: Color,
  val accentDanger: Color,
  val accentSuccess: Color,
  val accentWarning: Color,
  val input: Color,
)

@Immutable
data class PirateRadiusTokens(
  val xs: Dp,
  val sm: Dp,
  val md: Dp,
  val lg: Dp,
  val xl: Dp,
  val x2l: Dp,
  val x3l: Dp,
  val full: Dp,
)

@Immutable
data class PirateElevationTokens(
  val sm: Dp,
  val md: Dp,
  val lg: Dp,
  val xl: Dp,
)

private val PirateDarkTokens =
  PirateColorTokens(
    bgPage = WebDarkBackground,
    bgSurface = WebDarkBackground,
    bgElevated = WebDarkElevated,
    bgOverlay = Color.Black.copy(alpha = 0.55f),
    surfaceHoverSubtle = surfaceFrom(WebDarkMuted, 0.08f),
    surfaceSubtle = surfaceFrom(WebDarkMuted, 0.24f),
    surfaceInteractive = surfaceFrom(WebDarkMuted, 0.48f),
    surfaceSkeleton = surfaceFrom(WebDarkMuted, 0.62f),
    surfaceDisabled = surfaceFrom(WebDarkMuted, 0.78f),
    surfaceAccent = surfaceFrom(WebDarkPrimary, 0.10f),
    surfaceDanger = surfaceFrom(WebDarkDestructive, 0.08f),
    surfaceFrosted = WebDarkBackground.copy(alpha = 0.74f).compositeOver(WebDarkElevated),
    surfaceCardGlass = WebDarkBackground.copy(alpha = 0.68f).compositeOver(WebDarkElevated),
    surfaceToastError = Color(0xFF2D1A19),
    surfaceToastInfo = Color(0xFF2B2A27),
    surfaceToastSuccess = Color(0xFF1F2C1F),
    surfaceToastWarning = Color(0xFF352919),
    textPrimary = WebDarkForeground,
    textSecondary = WebDarkMutedForeground,
    textDisabled = WebDarkMutedForeground.copy(alpha = 0.45f),
    textOnAccent = Color.White,
    borderDefault = WebDarkBorder,
    borderSoft = WebDarkBorder.copy(alpha = 0.70f),
    borderStrong = WebDarkInput,
    borderOnDark = Color.White.copy(alpha = 0.10f),
    accentBrand = WebDarkPrimary,
    accentDanger = WebDarkDestructive,
    accentSuccess = Color(0xFF34D399),
    accentWarning = Color(0xFFF59E0B),
    input = WebDarkInput,
  )

private val PirateRadiusScale =
  PirateRadiusTokens(
    xs = 2.dp,
    sm = 4.dp,
    md = 8.dp,
    lg = 12.dp,
    xl = 14.dp,
    x2l = 16.dp,
    x3l = 24.dp,
    full = 999.dp,
  )

private val PirateElevationScale =
  PirateElevationTokens(
    sm = 4.dp,
    md = 8.dp,
    lg = 16.dp,
    xl = 24.dp,
  )

internal val LocalPirateColors = staticCompositionLocalOf<PirateColorTokens> {
  error("Pirate color tokens not provided")
}

internal val LocalPirateRadii = staticCompositionLocalOf<PirateRadiusTokens> {
  error("Pirate radius tokens not provided")
}

internal val LocalPirateElevation = staticCompositionLocalOf<PirateElevationTokens> {
  error("Pirate elevation tokens not provided")
}

object PirateTokens {
  val darkColors: PirateColorTokens = PirateDarkTokens
  val radii: PirateRadiusTokens = PirateRadiusScale
  val elevation: PirateElevationTokens = PirateElevationScale

  val colors: PirateColorTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalPirateColors.current

  val radius: PirateRadiusTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalPirateRadii.current

  val shadow: PirateElevationTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalPirateElevation.current
}

