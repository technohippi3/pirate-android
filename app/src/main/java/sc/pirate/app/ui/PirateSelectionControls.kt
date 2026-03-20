package sc.pirate.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PirateTokens

enum class PirateToggleChipVariant {
  Pill,
  Card,
}

@Composable
fun PirateToggleChip(
  selected: Boolean,
  onClick: () -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  variant: PirateToggleChipVariant = PirateToggleChipVariant.Pill,
) {
  val shape =
    when (variant) {
      PirateToggleChipVariant.Pill -> RoundedCornerShape(PirateTokens.radius.full)
      PirateToggleChipVariant.Card -> RoundedCornerShape(PirateTokens.radius.xl)
    }
  val containerColor = if (selected) PirateTokens.colors.surfaceAccent else PirateTokens.colors.bgSurface
  val borderColor = if (selected) PirateTokens.colors.accentBrand.copy(alpha = 0.4f) else PirateTokens.colors.borderSoft
  val textColor = if (selected) PirateTokens.colors.textPrimary else PirateTokens.colors.textSecondary
  val padding =
    when (variant) {
      PirateToggleChipVariant.Pill -> PaddingValues(horizontal = 16.dp, vertical = 10.dp)
      PirateToggleChipVariant.Card -> PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    }

  Surface(
    modifier =
      modifier
        .defaultMinSize(minHeight = 44.dp)
        .alpha(if (enabled) 1f else 0.6f)
        .clickable(enabled = enabled, onClick = onClick),
    shape = shape,
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
  ) {
    Box(
      modifier = Modifier.padding(padding),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = textColor,
        textAlign = TextAlign.Center,
      )
    }
  }
}

enum class PirateSelectionCardLayout {
  Row,
  Stack,
}

@Composable
fun PirateSelectionCard(
  selected: Boolean,
  onClick: () -> Unit,
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  enabled: Boolean = true,
  layout: PirateSelectionCardLayout = PirateSelectionCardLayout.Row,
  badge: (@Composable () -> Unit)? = null,
  media: (@Composable (Boolean) -> Unit)? = null,
) {
  val containerColor = if (selected) PirateTokens.colors.surfaceAccent else PirateTokens.colors.bgSurface
  val borderColor = if (selected) PirateTokens.colors.accentBrand.copy(alpha = 0.4f) else PirateTokens.colors.borderSoft

  Surface(
    modifier =
      modifier
        .alpha(if (enabled) 1f else 0.6f)
        .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(PirateTokens.radius.xl),
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
  ) {
    when (layout) {
      PirateSelectionCardLayout.Row ->
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.Top,
        ) {
          if (media != null) {
            media(selected)
          }
          Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = PirateTokens.colors.textPrimary,
              )
              if (badge != null) {
                badge()
              }
            }
            if (!description.isNullOrBlank()) {
              Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = PirateTokens.colors.textSecondary,
              )
            }
          }
        }
      PirateSelectionCardLayout.Stack ->
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (media != null) {
            media(selected)
          }
          if (badge != null) {
            badge()
          }
          Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = PirateTokens.colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          if (!description.isNullOrBlank()) {
            Text(
              description,
              style = MaterialTheme.typography.bodySmall,
              color = PirateTokens.colors.textSecondary,
              textAlign = TextAlign.Center,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
    }
  }
}
