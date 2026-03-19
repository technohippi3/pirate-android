package sc.pirate.app.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PlayerScrubber(
  positionSec: Float,
  durationSec: Float,
  onSeek: (Float) -> Unit,
) {
  var isSeeking by remember { mutableStateOf(false) }
  var seekValue by remember { mutableStateOf(0f) }

  val safeDuration = if (durationSec > 0f) durationSec else 1f
  val sliderValue = (if (isSeeking) seekValue else positionSec).coerceIn(0f, safeDuration)

  Column(modifier = Modifier.fillMaxWidth()) {
    Slider(
      value = sliderValue,
      onValueChange = { v ->
        if (durationSec <= 0f) return@Slider
        isSeeking = true
        seekValue = v
      },
      onValueChangeFinished = {
        if (durationSec <= 0f) return@Slider
        onSeek(seekValue)
        isSeeking = false
      },
      valueRange = 0f..safeDuration,
      enabled = durationSec > 0f,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        formatTime(if (isSeeking) seekValue else positionSec),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        formatTime(durationSec),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun SoftIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  size: Dp = 56.dp,
  selected: Boolean = false,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier,
    shape = CircleShape,
    color =
      if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      },
  ) {
    PirateIconButton(
      modifier = Modifier.size(size),
      onClick = onClick,
    ) {
      content()
    }
  }
}

private fun formatTime(seconds: Float): String {
  val safe = seconds.coerceAtLeast(0f).toInt()
  val mins = safe / 60
  val secs = safe % 60
  return "${mins}:${secs.toString().padStart(2, '0')}"
}
