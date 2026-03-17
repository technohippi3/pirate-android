package com.pirate.app.assistant

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.pirate.app.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val AccentPurple = Color(0xFFCBA6F7)
private val EndCallRed = Color(0xFFE57373)

@Composable
fun AssistantCallScreen(
  controller: AgoraVoiceController,
  onMinimize: () -> Unit,
) {
  val state by controller.state.collectAsState()
  val isMuted by controller.isMuted.collectAsState()
  val duration by controller.durationSeconds.collectAsState()
  val isBotSpeaking by controller.isBotSpeaking.collectAsState()
  val errorMessage by controller.errorMessage.collectAsState()
  val quotaStatus by controller.quota.collectAsState()

  // Auto-dismiss when call ends
  LaunchedEffect(state) {
    if (state == VoiceCallState.Idle) {
      onMinimize()
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Top bar with minimize button
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = onMinimize) {
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
      // Spacer for balance
      Box(modifier = Modifier.size(48.dp))
    }

    Spacer(modifier = Modifier.weight(1f))

    // Big avatar with speaking animation
    BigSpeakingAvatar(
      isBotSpeaking = isBotSpeaking,
      isConnecting = state == VoiceCallState.Connecting,
    )

    Spacer(modifier = Modifier.height(24.dp))

    Text(
      text = "Violet",
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      color = AccentPurple,
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text = when (state) {
        VoiceCallState.Connecting -> "Connecting..."
        VoiceCallState.Connected -> if (isBotSpeaking) "Speaking..." else "Listening..."
        VoiceCallState.Error -> errorMessage ?: "Connection failed"
        VoiceCallState.Idle -> ""
      },
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val quota = quotaStatus?.quota
    if (quota != null) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "Free call remaining: ${formatCallSeconds(quota.freeCallSecondsRemaining)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (!quota.verificationTier.equals("verified", ignoreCase = true)) {
        Text(
          text = "Verify with Self.xyz for 3 free call minutes/day.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Spacer(modifier = Modifier.weight(1f))

    // Controls
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 64.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Mute button
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
          modifier = Modifier.size(64.dp),
          shape = CircleShape,
          color = if (isMuted) MaterialTheme.colorScheme.errorContainer
          else MaterialTheme.colorScheme.surfaceVariant,
        ) {
          IconButton(
            onClick = { controller.toggleMute() },
            modifier = Modifier.fillMaxSize(),
          ) {
            Icon(
              if (isMuted) PhosphorIcons.Regular.MicrophoneSlash else PhosphorIcons.Regular.Microphone,
              contentDescription = if (isMuted) "Unmute" else "Mute",
              tint = if (isMuted) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.onSurface,
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

      // End call button
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
          modifier = Modifier.size(64.dp),
          shape = CircleShape,
          color = EndCallRed,
        ) {
          IconButton(
            onClick = { controller.endCall() },
            modifier = Modifier.fillMaxSize(),
          ) {
            Icon(
              PhosphorIcons.Regular.PhoneX,
              contentDescription = "End call",
              tint = Color.White,
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

@Composable
private fun BigSpeakingAvatar(isBotSpeaking: Boolean, isConnecting: Boolean) {
  val pulseTransition = rememberInfiniteTransition(label = "bigPulse")
  val pulseAlpha by pulseTransition.animateFloat(
    initialValue = 1f,
    targetValue = 0.5f,
    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
    label = "bigPulseAlpha",
  )

  // Outer glow ring when speaking
  Box(contentAlignment = Alignment.Center) {
    if (isBotSpeaking) {
      Surface(
        modifier = Modifier
          .size(160.dp)
          .alpha(pulseAlpha * 0.3f),
        shape = CircleShape,
        color = AccentPurple,
      ) {}
    }

    Surface(
      modifier = Modifier
        .size(128.dp)
        .then(if (isBotSpeaking) Modifier.alpha(pulseAlpha) else Modifier),
      shape = CircleShape,
      color = AccentPurple,
    ) {
      Box(contentAlignment = Alignment.Center) {
        if (isConnecting) {
          CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 3.dp,
            color = Color.White,
          )
        } else {
          Image(
            painter = painterResource(id = R.drawable.assistant_avatar),
            contentDescription = "Violet avatar",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
          )
        }
      }
    }
  }
}

private fun formatCallDuration(seconds: Int): String {
  val m = seconds / 60
  val s = seconds % 60
  return "%d:%02d".format(m, s)
}
