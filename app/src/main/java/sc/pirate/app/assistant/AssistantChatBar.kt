package sc.pirate.app.assistant

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sc.pirate.app.R
import sc.pirate.app.theme.PiratePalette
import kotlinx.coroutines.launch

@Composable
fun AssistantChatBar(
  service: AssistantService,
  isAuthenticated: Boolean,
  wallet: String?,
  onShowMessage: (String) -> Unit,
) {
  val messages by service.messages.collectAsState()
  val sending by service.sending.collectAsState()
  var expanded by remember { mutableStateOf(false) }
  var inputText by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()

  // Auto-scroll to bottom when new messages arrive
  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.size - 1)
    }
  }

  val accentPurple = Color(0xFFCBA6F7) // catppuccin mauve

  fun doSend() {
    val text = inputText.trim()
    if (text.isBlank() || sending) return
    if (!isAuthenticated || wallet == null) {
      onShowMessage("Sign in to chat with Violet")
      return
    }
    inputText = ""
    scope.launch {
      val result = service.sendMessage(text, wallet)
      result.onFailure { e ->
        onShowMessage("Violet: ${e.message ?: "Error"}")
      }
    }
  }

  Surface(
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column {
      // Header row (always visible) — tap to toggle
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { expanded = !expanded }
          .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Avatar
        Image(
          painter = painterResource(id = R.drawable.assistant_avatar),
          contentDescription = "Violet",
          modifier = Modifier.size(32.dp).clip(CircleShape),
          contentScale = ContentScale.Crop,
        )

        Spacer(Modifier.width(12.dp))

        if (!expanded) {
          // Show last message preview when collapsed
          val lastMsg = messages.lastOrNull()
          Text(
            text = if (lastMsg != null) {
              if (lastMsg.role == "assistant") "Violet: ${lastMsg.content}" else lastMsg.content
            } else {
              "Chat with Violet"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (messages.isEmpty()) PiratePalette.TextMuted else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
        } else {
          Text(
            "Violet",
            style = MaterialTheme.typography.titleMedium,
            color = accentPurple,
            modifier = Modifier.weight(1f),
          )
        }

        if (sending) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = accentPurple,
          )
          Spacer(Modifier.width(8.dp))
        }

        Icon(
          imageVector = if (expanded) PhosphorIcons.Regular.CaretUp else PhosphorIcons.Regular.CaretDown,
          contentDescription = if (expanded) "Collapse" else "Expand",
          tint = PiratePalette.TextMuted,
          modifier = Modifier.size(24.dp),
        )
      }

      // Expanded content
      AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
      ) {
        Column {
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

          // Message list
          LazyColumn(
            state = listState,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 100.dp, max = 300.dp)
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            if (messages.isEmpty()) {
              item {
                Text(
                  "Say hello to Violet!",
                  style = MaterialTheme.typography.bodyLarge,
                  color = PiratePalette.TextMuted,
                  modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp),
                )
              }
            }
            items(messages, key = { it.id }) { msg ->
              MessageBubble(msg)
            }
          }

          // Input row
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
              value = inputText,
              onValueChange = { inputText = it },
              placeholder = {
                Text(
                  "Message Violet...",
                  style = MaterialTheme.typography.bodyLarge,
                )
              },
              textStyle = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(24.dp),
              singleLine = true,
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentPurple,
              ),
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
              keyboardActions = KeyboardActions(onSend = { doSend() }),
              enabled = !sending,
            )

            Spacer(Modifier.width(8.dp))

            IconButton(
              onClick = { doSend() },
              enabled = inputText.isNotBlank() && !sending,
            ) {
              Icon(
                PhosphorIcons.Regular.PaperPlaneRight,
                contentDescription = "Send",
                tint = if (inputText.isNotBlank() && !sending) accentPurple else PiratePalette.TextMuted,
                modifier = Modifier.size(24.dp),
              )
            }
          }
        }
      }

      if (!expanded) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      }
    }
  }
}

@Composable
private fun MessageBubble(msg: AssistantMessage) {
  val isUser = msg.role == "user"
  val alignment = if (isUser) Alignment.End else Alignment.Start
  val bgColor = if (isUser) {
    MaterialTheme.colorScheme.primary
  } else {
    MaterialTheme.colorScheme.surfaceVariant
  }
  val textColor = if (isUser) {
    MaterialTheme.colorScheme.onPrimary
  } else {
    MaterialTheme.colorScheme.onSurface
  }

  Box(
    modifier = Modifier.fillMaxWidth(),
    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = bgColor,
      modifier = Modifier.fillMaxWidth(0.85f),
    ) {
      Text(
        text = msg.content,
        style = MaterialTheme.typography.bodyLarge,
        color = textColor,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      )
    }
  }
}
