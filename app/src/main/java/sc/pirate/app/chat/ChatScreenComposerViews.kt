package sc.pirate.app.chat

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import sc.pirate.app.assistant.AssistantService
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.PirateMobileHeader

@Composable
internal fun NotAuthenticatedPlaceholder() {
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(
        PhosphorIcons.Regular.User,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = PiratePalette.TextMuted,
      )
      Spacer(modifier = Modifier.height(16.dp))
      Text("Sign in to chat", color = PiratePalette.TextMuted)
    }
  }
}

@Composable
internal fun ConnectingPlaceholder() {
  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator(modifier = Modifier.size(32.dp))
      Spacer(modifier = Modifier.height(16.dp))
      Text("Connecting to XMTP...", color = PiratePalette.TextMuted)
    }
  }
}

@Composable
internal fun ConversationList(
  conversations: List<ConversationItem>,
  assistantService: AssistantService,
  isAuthenticated: Boolean,
  xmtpConnecting: Boolean,
  onOpenAssistant: () -> Unit,
  onOpenComposer: () -> Unit,
  onOpenConversation: (String) -> Unit,
  onOpenDrawer: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize()) {
    PirateMobileHeader(
      title = "Chat",
      isAuthenticated = true,
      onAvatarPress = onOpenDrawer,
      rightSlot = {
        if (isAuthenticated) {
          PirateIconButton(onClick = onOpenComposer) {
            Icon(PhosphorIcons.Regular.Plus, contentDescription = "New conversation")
          }
        }
      },
    )

    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
      item(key = "assistant") {
        val assistantMessages by assistantService.messages.collectAsState()
        val lastAssistantMsg = assistantMessages.lastOrNull()
        AssistantConversationRow(
          lastMessage = lastAssistantMsg?.content,
          onClick = onOpenAssistant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      }

      if (conversations.isEmpty()) {
        item {
          Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              if (!isAuthenticated) {
                Text("Sign in to message other users", color = PiratePalette.TextMuted)
              } else if (xmtpConnecting) {
                Text("Connecting to XMTP...", color = PiratePalette.TextMuted)
              }
            }
          }
        }
      } else {
        items(conversations, key = { it.id }) { convo ->
          ConversationRow(
            conversation = convo,
            onClick = { onOpenConversation(convo.id) },
          )
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
      }
    }
  }
}
