package sc.pirate.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PirateTokens

enum class PirateBadgeTone {
  Default,
  Brand,
  Info,
  Success,
  Warning,
  Danger,
}

enum class PirateBadgeSize {
  Default,
  Small,
  Count,
}

@Composable
fun PirateBadge(
  text: String,
  modifier: Modifier = Modifier,
  tone: PirateBadgeTone = PirateBadgeTone.Default,
  size: PirateBadgeSize = PirateBadgeSize.Default,
) {
  val colors =
    when (tone) {
      PirateBadgeTone.Default ->
        Triple(
          PirateTokens.colors.surfaceSubtle,
          PirateTokens.colors.borderSoft,
          PirateTokens.colors.textSecondary,
        )
      PirateBadgeTone.Brand, PirateBadgeTone.Info ->
        Triple(
          PirateTokens.colors.surfaceAccent,
          PirateTokens.colors.accentBrand.copy(alpha = 0.35f),
          PirateTokens.colors.accentBrand,
        )
      PirateBadgeTone.Success ->
        Triple(
          PirateTokens.colors.accentSuccess.copy(alpha = 0.12f),
          PirateTokens.colors.accentSuccess.copy(alpha = 0.35f),
          PirateTokens.colors.accentSuccess,
        )
      PirateBadgeTone.Warning ->
        Triple(
          PirateTokens.colors.accentWarning.copy(alpha = 0.12f),
          PirateTokens.colors.accentWarning.copy(alpha = 0.35f),
          PirateTokens.colors.accentWarning,
        )
      PirateBadgeTone.Danger ->
        Triple(
          PirateTokens.colors.surfaceDanger,
          PirateTokens.colors.accentDanger.copy(alpha = 0.35f),
          PirateTokens.colors.accentDanger,
        )
    }
  val (horizontalPadding, verticalPadding, minSize) =
    when (size) {
      PirateBadgeSize.Default -> Triple(12.dp, 4.dp, 0.dp)
      PirateBadgeSize.Small -> Triple(10.dp, 4.dp, 0.dp)
      PirateBadgeSize.Count -> Triple(6.dp, 2.dp, 20.dp)
    }

  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.extraLarge,
    color = colors.first,
    border = BorderStroke(1.dp, colors.second),
  ) {
    Box(
      modifier =
        Modifier
          .defaultMinSize(minWidth = minSize, minHeight = minSize)
          .padding(horizontal = horizontalPadding, vertical = verticalPadding),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = text,
        color = colors.third,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}
