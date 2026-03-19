package sc.pirate.app.chat

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.PirateMobileHeader
import sc.pirate.app.util.abbreviateAddress
import sc.pirate.app.util.resolveAvatarUrl
import coil.compose.AsyncImage
import org.xmtp.android.library.libxmtp.PermissionOption

@Composable
internal fun MessageThread(
  messages: List<ChatMessage>,
  conversation: ConversationItem,
  onBack: () -> Unit,
  onSend: (String) -> Unit,
  onOpenSettings: () -> Unit,
  onOpenProfile: (() -> Unit)? = null,
) {
  var inputText by remember { mutableStateOf("") }
  val listState = rememberLazyListState()

  // Scroll to bottom when messages change
  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.size - 1)
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    val threadAvatarUri =
      if (conversation.type == ConversationType.DM) {
        messages
          .asReversed()
          .firstOrNull { !it.isFromMe && !it.senderAvatarUri.isNullOrBlank() }
          ?.senderAvatarUri ?: conversation.avatarUri
      } else {
        conversation.avatarUri
      }
    val threadTitle =
      when {
        conversation.type == ConversationType.DM && !conversation.peerAddress.isNullOrBlank() -> {
          val display = conversation.displayName.trim()
          if (display.isBlank() || display.equals(conversation.peerAddress, ignoreCase = true)) {
            abbreviateAddress(conversation.peerAddress)
          } else {
            display
          }
        }
        else -> conversation.displayName.ifBlank { "Conversation" }
      }
    PirateMobileHeader(
      title = threadTitle,
      onBackPress = onBack,
      isAuthenticated = true,
      titleAvatarUri = if (conversation.type == ConversationType.DM) threadAvatarUri else null,
      titleAvatarDisplayName = if (conversation.type == ConversationType.DM) threadTitle else null,
      onTitlePress = if (conversation.type == ConversationType.DM) onOpenProfile else null,
      rightSlot = {
        PirateIconButton(onClick = onOpenSettings) {
          Icon(PhosphorIcons.Regular.DotsThree, contentDescription = "Settings")
        }
      },
    )

    // Messages
    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
      state = listState,
      verticalArrangement = Arrangement.spacedBy(4.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
      items(messages, key = { it.id }) { msg ->
        MessageBubble(
          message = msg,
          defaultIncomingDisplayName = threadTitle,
          defaultIncomingAvatarUri = threadAvatarUri,
          isGroupConversation = conversation.type == ConversationType.GROUP,
        )
      }
    }

    // Input bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .imePadding()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = inputText,
        onValueChange = { inputText = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("Message") },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      PirateIconButton(
        onClick = {
          if (inputText.isNotBlank()) {
            onSend(inputText.trim())
            inputText = ""
          }
        },
        enabled = inputText.isNotBlank(),
      ) {
        Icon(
          PhosphorIcons.Regular.PaperPlaneRight,
          contentDescription = "Send",
          tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
          else PiratePalette.TextMuted,
        )
      }
    }
  }
}

@Composable
internal fun MessageBubble(
  message: ChatMessage,
  defaultIncomingDisplayName: String,
  defaultIncomingAvatarUri: String?,
  isGroupConversation: Boolean,
) {
  val alignment = if (message.isFromMe) Alignment.End else Alignment.Start
  val bgColor = if (message.isFromMe) MaterialTheme.colorScheme.primary
  else MaterialTheme.colorScheme.surfaceVariant
  val textColor = if (message.isFromMe) MaterialTheme.colorScheme.onPrimary
  else MaterialTheme.colorScheme.onSurfaceVariant
  val incomingLabel =
    when {
      message.isFromMe -> ""
      isGroupConversation && !message.senderDisplayName.isNullOrBlank() -> message.senderDisplayName.orEmpty()
      isGroupConversation -> abbreviateAddress(message.senderAddress)
      else -> defaultIncomingDisplayName.ifBlank { "Unknown" }
    }
  val incomingAvatar =
    if (isGroupConversation) message.senderAvatarUri ?: defaultIncomingAvatarUri
    else defaultIncomingAvatarUri

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = alignment,
  ) {
    if (!message.isFromMe) {
      Row(
        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IdentityAvatar(
          displayName = incomingLabel,
          avatarUri = incomingAvatar,
          size = 18.dp,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = incomingLabel,
          color = PiratePalette.TextMuted,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = bgColor,
      modifier = Modifier.widthIn(max = 280.dp),
    ) {
      Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
          text = message.text,
          color = textColor,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = formatTime(message.timestampMs),
          style = MaterialTheme.typography.labelSmall,
          color = textColor.copy(alpha = 0.7f),
        )
      }
    }
  }
}

@Composable
internal fun IdentityAvatar(
  displayName: String,
  avatarUri: String?,
  size: Dp,
) {
  val avatarUrl = resolveAvatarUrl(avatarUri)
  if (!avatarUrl.isNullOrBlank()) {
    AsyncImage(
      model = avatarUrl,
      contentDescription = displayName,
      modifier = Modifier.size(size).clip(CircleShape),
      contentScale = ContentScale.Crop,
    )
  } else {
    Surface(
      modifier = Modifier.size(size),
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(
          text = avatarInitial(displayName),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
internal fun PermissionOptionSelector(
  title: String,
  selected: PermissionOption,
  onSelect: (PermissionOption) -> Unit,
  enabled: Boolean,
) {
  val options =
    listOf(
      PermissionOption.Allow,
      PermissionOption.Admin,
      PermissionOption.SuperAdmin,
      PermissionOption.Deny,
    )
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))
    options.forEach { option ->
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable(enabled = enabled) { onSelect(option) }
          .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RadioButton(
          selected = selected == option,
          onClick = null,
          enabled = enabled,
        )
        Text(permissionOptionLabel(option))
      }
    }
  }
}
