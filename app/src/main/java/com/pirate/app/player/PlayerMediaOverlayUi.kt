package com.pirate.app.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pirate.app.ui.PirateIconButton

internal val OverlayRailEndPadding = 18.dp
internal val OverlayRailBottomPadding = 178.dp
internal val OverlayTextStartPadding = 16.dp
internal val OverlayTextEndPadding = 92.dp
internal val OverlayTextBottomPadding = 92.dp

@Composable
internal fun OverlayActionIcon(
  icon: ImageVector,
  contentDescription: String,
  count: String? = null,
  active: Boolean = false,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  val tint = if (active) Color(0xFFFF6A6A) else Color.White
  val textColor = if (enabled) Color.White else Color.White.copy(alpha = 0.52f)
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(0.dp),
  ) {
    PirateIconButton(
      onClick = onClick,
      enabled = enabled,
      colors =
        IconButtonDefaults.iconButtonColors(
          contentColor = tint,
          disabledContentColor = tint.copy(alpha = 0.52f),
        ),
      modifier = Modifier.size(40.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier.size(32.dp),
      )
    }
    if (count != null) {
      Text(
        text = count,
        color = textColor,
        style = MaterialTheme.typography.labelSmall.copy(
          fontWeight = FontWeight.SemiBold,
          shadow = Shadow(
            color = Color.Black.copy(alpha = 0.65f),
            offset = Offset(0f, 1f),
            blurRadius = 3f,
          ),
        ),
      )
    }
  }
}

@Composable
internal fun OverlayActionCircleButton(
  contentDescription: String,
  onClick: () -> Unit,
  content: @Composable BoxScope.() -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    PirateIconButton(
      onClick = onClick,
      colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
      modifier = Modifier.size(48.dp),
    ) {
      Box(
        modifier =
          Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(
              brush =
                Brush.linearGradient(
                  colors = listOf(Color(0xFFFB7185), Color(0xFFC084FC)),
                ),
            ),
        contentAlignment = Alignment.Center,
      ) {
        content()
      }
    }
  }
}

internal fun formatOverlayCount(value: Long): String {
  if (value < 1_000L) return value.toString()
  if (value < 1_000_000L) {
    val scaled = value / 100L
    val whole = scaled / 10L
    val decimal = scaled % 10L
    return if (decimal == 0L) "${whole}K" else "${whole}.${decimal}K"
  }
  val scaled = value / 100_000L
  val whole = scaled / 10L
  val decimal = scaled % 10L
  return if (decimal == 0L) "${whole}M" else "${whole}.${decimal}M"
}
