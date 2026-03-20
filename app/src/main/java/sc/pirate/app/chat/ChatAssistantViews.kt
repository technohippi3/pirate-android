package sc.pirate.app.chat

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import sc.pirate.app.R
import sc.pirate.app.assistant.AgoraVoiceController
import sc.pirate.app.assistant.AssistantMessage
import sc.pirate.app.assistant.AssistantService
import sc.pirate.app.assistant.AssistantQuotaStatus
import sc.pirate.app.assistant.VoiceCallState
import sc.pirate.app.assistant.asAssistantApiFailureOrNull
import sc.pirate.app.assistant.formatCallSeconds
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.theme.PirateTokens
import sc.pirate.app.ui.PirateMobileHeader
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.PirateSheetTitle
import kotlinx.coroutines.launch

@Composable
internal fun AssistantConversationRow(
  lastMessage: String?,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Image(
      painter = painterResource(R.drawable.assistant_avatar),
      contentDescription = "Violet",
      modifier = Modifier.size(48.dp).clip(CircleShape),
      contentScale = ContentScale.Crop,
    )

    Spacer(modifier = Modifier.width(12.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "Violet",
        fontWeight = FontWeight.Medium,
        color = PirateTokens.colors.accentBrand,
      )
      Text(
        text = lastMessage ?: stringResource(R.string.assistant_intro_message),
        color = PiratePalette.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
internal fun AssistantThread(
  assistantService: AssistantService,
  voiceController: AgoraVoiceController,
  wallet: String?,
  onBack: () -> Unit,
  onShowMessage: (String) -> Unit,
  onNavigateToCall: () -> Unit,
  onNavigateToCredits: () -> Unit,
  onNavigateToVerifyIdentity: () -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val messages by assistantService.messages.collectAsState()
  val sending by assistantService.sending.collectAsState()
  val voiceState by voiceController.state.collectAsState()
  val chatQuotaStatus by assistantService.quota.collectAsState()
  val callQuotaStatus by voiceController.quota.collectAsState()
  val quotaStatus = chatQuotaStatus ?: callQuotaStatus
  var inputText by remember { mutableStateOf("") }
  var showQuotaSheet by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()

  val micPermissionLauncher =
    rememberLauncherForActivityResult(
      ActivityResultContracts.RequestPermission(),
    ) { granted ->
      if (granted && wallet != null) {
        voiceController.startCall(wallet)
        onNavigateToCall()
      } else if (!granted) {
        onShowMessage("Microphone permission is required for voice calls")
      }
    }

  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.size - 1)
    }
  }

  LaunchedEffect(wallet) {
    if (wallet != null) {
      assistantService.refreshQuota(wallet)
    }
  }

  fun doSend() {
    val text = inputText.trim()
    if (text.isBlank() || sending) return
    if (wallet == null) {
      onShowMessage("Sign in to chat with Violet")
      return
    }
    inputText = ""
    scope.launch {
      val result = assistantService.sendMessage(text, wallet)
      result.onFailure { e ->
        val failure = e.asAssistantApiFailureOrNull()
        val isChatQuotaExhausted =
          failure?.code == "insufficient_credits" ||
            failure?.code == "no_chat_quota" ||
            failure?.quotaStatus?.quota?.freeChatMessagesRemaining == 0
        if (isChatQuotaExhausted) {
          showQuotaSheet = true
          onShowMessage("Free messages are used up. Verify or buy credits to keep chatting.")
        } else {
          onShowMessage("Violet: ${e.message ?: "Error"}")
        }
      }
    }
  }

  fun startVoiceCall(walletAddress: String) {
    if (hasNoCallAllowance(quotaStatus)) {
      showQuotaSheet = true
      onShowMessage("No Violet call time left. Buy Credits to continue.")
      return
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      voiceController.startCall(walletAddress)
      onNavigateToCall()
    } else {
      micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
  }

  fun startVoiceCallWithQuotaRefresh() {
    if (wallet == null) {
      onShowMessage("Sign in to call Violet")
      return
    }
    scope.launch {
      assistantService.refreshQuota(wallet).onFailure { err ->
        onShowMessage(err.message ?: "Could not refresh Violet quota.")
      }
      startVoiceCall(wallet)
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    PirateMobileHeader(
      title = "Violet",
      onBackPress = onBack,
      isAuthenticated = true,
      titleAvatarDrawableRes = R.drawable.assistant_avatar,
      titleAvatarDisplayName = "Violet",
      rightSlot = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (voiceState == VoiceCallState.Idle || voiceState == VoiceCallState.Error) {
            IconButton(onClick = { startVoiceCallWithQuotaRefresh() }) {
              Icon(
                PhosphorIcons.Regular.PhoneCall,
                contentDescription = "Voice call",
                tint = PirateTokens.colors.accentBrand,
              )
            }
          }
        }
      },
    )

    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
      state = listState,
      verticalArrangement = Arrangement.spacedBy(4.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
      if (messages.isEmpty()) {
        item {
          AssistantMessageBubble(
            AssistantMessage(
              id = "intro",
              role = "assistant",
              content = stringResource(R.string.assistant_intro_message),
              timestamp = 0L,
            ),
          )
        }
      }
      items(messages, key = { it.id }) { msg ->
        AssistantMessageBubble(msg)
      }
      if (sending) {
        item {
          Row(modifier = Modifier.padding(vertical = 8.dp)) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = PirateTokens.colors.accentBrand,
            )
            Spacer(Modifier.width(8.dp))
            Text("Violet is thinking...", color = PiratePalette.TextMuted)
          }
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = inputText,
        onValueChange = { inputText = it },
        modifier = Modifier.weight(1f),
        placeholder = { Text("Message Violet") },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        enabled = !sending,
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { doSend() }),
      )
      Spacer(modifier = Modifier.width(8.dp))
      IconButton(
        onClick = { doSend() },
        enabled = inputText.isNotBlank() && !sending,
      ) {
        Icon(
          PhosphorIcons.Regular.PaperPlaneRight,
          contentDescription = "Send",
          tint = if (inputText.isNotBlank() && !sending) PirateTokens.colors.accentBrand else PiratePalette.TextMuted,
        )
      }
    }
  }

  if (showQuotaSheet && quotaStatus != null) {
    AssistantQuotaSheet(
      quotaStatus = quotaStatus,
      onDismiss = { showQuotaSheet = false },
      onOpenCredits = {
        showQuotaSheet = false
        onNavigateToCredits()
      },
      onOpenVerifyIdentity = {
        showQuotaSheet = false
        onNavigateToVerifyIdentity()
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantQuotaSheet(
  quotaStatus: AssistantQuotaStatus,
  onDismiss: () -> Unit,
  onOpenCredits: () -> Unit,
  onOpenVerifyIdentity: () -> Unit,
) {
  val quota = quotaStatus.quota
  val verified = quota.verificationTier.equals("verified", ignoreCase = true)
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PirateSheetTitle(text = "Violet daily quota")
      Text(
        text = if (verified) "Verified tier" else "Unverified tier",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      QuotaRow(
        label = "Messages",
        value = "${quota.freeChatMessagesRemaining}/${quota.freeChatMessagesLimit} free left",
      )
      QuotaRow(
        label = "Call",
        value = "${formatCallSeconds(quota.freeCallSecondsRemaining)} / ${formatCallSeconds(quota.freeCallSecondsLimit)} free left",
      )
      val paidCredits = quotaStatus.paidCreditsAvailable?.trim().orEmpty().ifBlank { "0" }
      QuotaRow(
        label = "Paid credits",
        value = paidCredits,
      )
      if (!verified) {
        Text(
          text = "Verify with Self.xyz to unlock 30 free messages/day and 3 free call minutes/day.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
          onClick = onOpenVerifyIdentity,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Verify with Self.xyz")
        }
      }
      PiratePrimaryButton(
        text = "Buy Credits",
        onClick = onOpenCredits,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(modifier = Modifier.navigationBarsPadding())
    }
  }
}

@Composable
private fun QuotaRow(
  label: String,
  value: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

private fun hasNoCallAllowance(quotaStatus: AssistantQuotaStatus?): Boolean {
  val quota = quotaStatus?.quota ?: return false
  val maxCallSeconds = quotaStatus.maxCallSeconds
  if (maxCallSeconds != null) return maxCallSeconds <= 0
  return quota.freeCallSecondsRemaining <= 0
}

@Composable
internal fun AssistantMessageBubble(msg: AssistantMessage) {
  val isUser = msg.role == "user"
  val bgColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
  val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = bgColor,
      modifier = Modifier.widthIn(max = 280.dp),
    ) {
      Text(
        text = msg.content,
        color = textColor,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      )
    }
  }
}
