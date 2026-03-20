package sc.pirate.app.schedule

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sc.pirate.app.assistant.VoiceCallState
import sc.pirate.app.theme.PirateTokens

@Composable
fun ScheduledSessionCallScreen(
  controller: ScheduledSessionVoiceController,
  onMinimize: () -> Unit,
) {
  val state by controller.state.collectAsState()
  val isMuted by controller.isMuted.collectAsState()
  val duration by controller.durationSeconds.collectAsState()
  val peerConnected by controller.peerConnected.collectAsState()
  val errorMessage by controller.errorMessage.collectAsState()
  val bookingId by controller.activeBookingId.collectAsState()

  LaunchedEffect(state) {
    if (state == VoiceCallState.Idle) {
      onMinimize()
    }
  }

  Column(
    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PirateIconButton(onClick = onMinimize) {
        Icon(
          PhosphorIcons.Regular.CaretDown,
          contentDescription = "Minimize",
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(28.dp),
        )
      }
      Spacer(modifier = Modifier.weight(1f))
      Text(
        text = when (state) {
          VoiceCallState.Connecting -> "Connecting..."
          VoiceCallState.Connected -> formatCallDuration(duration)
          VoiceCallState.Error -> "Error"
          VoiceCallState.Idle -> ""
        },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.weight(1f))
      Box(modifier = Modifier.size(48.dp))
    }

    Spacer(modifier = Modifier.weight(1f))

    Text(
      text = "Session Call",
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = when (state) {
        VoiceCallState.Connecting -> "Joining booking #${bookingId ?: "?"}..."
        VoiceCallState.Connected -> if (peerConnected) "Peer connected" else "Waiting for peer..."
        VoiceCallState.Error -> errorMessage ?: "Connection failed"
        VoiceCallState.Idle -> ""
      },
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.weight(1f))

    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 64.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
          modifier = Modifier.size(64.dp),
          shape = CircleShape,
          color = if (isMuted) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
          PirateIconButton(
            onClick = { controller.toggleMute() },
            modifier = Modifier.fillMaxSize(),
          ) {
            Icon(
              if (isMuted) PhosphorIcons.Regular.MicrophoneSlash else PhosphorIcons.Regular.Microphone,
              contentDescription = if (isMuted) "Unmute" else "Mute",
              tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.size(28.dp),
            )
          }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = if (isMuted) "Unmute" else "Mute",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
          modifier = Modifier.size(64.dp),
          shape = CircleShape,
          color = PirateTokens.colors.accentDanger,
        ) {
          PirateIconButton(
            onClick = { controller.endCall() },
            modifier = Modifier.fillMaxSize(),
          ) {
            Icon(
              PhosphorIcons.Regular.PhoneX,
              contentDescription = "End call",
              tint = PirateTokens.colors.textOnAccent,
              modifier = Modifier.size(28.dp),
            )
          }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "End",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private fun formatCallDuration(seconds: Int): String {
  val mins = seconds / 60
  val secs = seconds % 60
  return "%d:%02d".format(mins, secs)
}
