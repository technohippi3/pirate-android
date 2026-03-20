package sc.pirate.app.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.PhoneX
import sc.pirate.app.R
import sc.pirate.app.theme.PirateTokens

@Composable
fun AssistantVoiceBar(controller: AgoraVoiceController, onOpen: (() -> Unit)? = null) {
  val state by controller.state.collectAsState()
  val duration by controller.durationSeconds.collectAsState()
  val isBotSpeaking by controller.isBotSpeaking.collectAsState()

  if (state != VoiceCallState.Connecting && state != VoiceCallState.Connected) return

  val statusText =
    when (state) {
      VoiceCallState.Connecting -> "Connecting..."
      VoiceCallState.Connected -> if (isBotSpeaking) "Speaking..." else formatDuration(duration)
      else -> ""
    }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface)
      .clickable { onOpen?.invoke() },
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(2.dp)
        .background(
          if (isBotSpeaking) PirateTokens.colors.accentBrand
          else PirateTokens.colors.accentBrand.copy(alpha = 0.4f),
        ),
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(64.dp)
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier = Modifier
          .size(48.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Image(
          painter = painterResource(id = R.drawable.assistant_avatar),
          contentDescription = "Violet avatar",
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Violet",
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.bodyLarge,
        )
        Text(
          text = statusText,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyLarge,
        )
      }

      PirateIconButton(onClick = { controller.endCall() }) {
        Icon(
          imageVector = PhosphorIcons.Regular.PhoneX,
          contentDescription = "End call",
          tint = PirateTokens.colors.accentDanger,
          modifier = Modifier.size(20.dp),
        )
      }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
  }
}

private fun formatDuration(seconds: Int): String {
  val m = seconds / 60
  val s = seconds % 60
  return "%d:%02d".format(m, s)
}
