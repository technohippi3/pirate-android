package sc.pirate.app.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sc.pirate.app.identity.SelfVerificationService
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.VerifiedSealBadge
import sc.pirate.app.util.abbreviateAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DmSuggestionRow(
  suggestion: DmSuggestion,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IdentityAvatar(
      displayName = suggestion.title,
      avatarUri = suggestion.avatarUri,
      size = 36.dp,
    )
    Spacer(modifier = Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = suggestion.title,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        VerifiedChatBadge(address = suggestion.verificationAddress, size = 16.dp)
      }
      Text(
        text = suggestion.subtitle,
        color = PiratePalette.TextMuted,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
internal fun ConversationRow(
  conversation: ConversationItem,
  onClick: () -> Unit,
) {
  val title =
    if (conversation.type == ConversationType.DM && !conversation.peerAddress.isNullOrBlank()) {
      val display = conversation.displayName.trim()
      if (display.isBlank() || display.equals(conversation.peerAddress, ignoreCase = true)) {
        abbreviateAddress(conversation.peerAddress)
      } else {
        display
      }
    } else {
      conversation.displayName.ifBlank { "Untitled group" }
    }
  val subtitle =
    when {
      conversation.lastMessage.isNotBlank() -> conversation.lastMessage
      !conversation.subtitle.isNullOrBlank() -> conversation.subtitle
      conversation.type == ConversationType.DM && !conversation.peerAddress.isNullOrBlank() ->
        abbreviateAddress(conversation.peerAddress)
      else -> ""
    }
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IdentityAvatar(
      displayName = title,
      avatarUri = conversation.avatarUri,
      size = 48.dp,
    )

    Spacer(modifier = Modifier.width(12.dp))

    Column(modifier = Modifier.weight(1f)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = title,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (conversation.type == ConversationType.DM) {
          VerifiedChatBadge(address = conversation.peerAddress, size = 16.dp)
        }
      }
      if (subtitle.isNotBlank()) {
        Text(
          text = subtitle,
          color = PiratePalette.TextMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    if (conversation.lastMessageTimestampMs > 0) {
      Text(
        text = formatTimestamp(conversation.lastMessageTimestampMs),
        color = PiratePalette.TextMuted,
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

private object ChatVerificationCache {
  private val cache = mutableMapOf<String, Boolean>()

  suspend fun isVerified(address: String): Boolean {
    val normalized = address.trim().lowercase()
    cache[normalized]?.let { return it }
    return when (
      withContext(Dispatchers.IO) {
        SelfVerificationService.checkIdentity(SongPublishService.API_CORE_URL, normalized)
      }
    ) {
      is SelfVerificationService.IdentityResult.Verified -> {
        cache[normalized] = true
        true
      }
      is SelfVerificationService.IdentityResult.NotVerified -> {
        cache[normalized] = false
        false
      }
      is SelfVerificationService.IdentityResult.Error -> false
    }
  }
}

@Composable
private fun VerifiedChatBadge(
  address: String?,
  size: Dp,
) {
  val normalizedAddress =
    remember(address) {
      address
        ?.trim()
        ?.lowercase()
        ?.takeIf(::looksLikeEthereumAddress)
    } ?: return
  var isVerified by remember(normalizedAddress) { mutableStateOf(false) }

  LaunchedEffect(normalizedAddress) {
    isVerified = ChatVerificationCache.isVerified(normalizedAddress)
  }

  if (isVerified) {
    VerifiedSealBadge(size = size)
  }
}
