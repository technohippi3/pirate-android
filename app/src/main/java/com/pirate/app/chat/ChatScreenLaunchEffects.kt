package com.pirate.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal fun ChatScreenLaunchEffects(
  chatService: XmtpChatService,
  isAuthenticated: Boolean,
  userAddress: String?,
  connected: Boolean,
  connecting: Boolean,
  activeConversationId: String?,
  currentView: ChatView,
  newDmAddress: String,
  newGroupMemberQuery: String,
  onSetCurrentView: (ChatView) -> Unit,
  onSetConnecting: (Boolean) -> Unit,
  onSetShowSettingsSheet: (Boolean) -> Unit,
  onSetDisappearingRetentionSeconds: (Long?) -> Unit,
  onThreadVisibilityChange: (Boolean) -> Unit,
  onSetDmDirectorySuggestions: (List<DmSuggestion>) -> Unit,
  onSetDmDirectoryBusy: (Boolean) -> Unit,
  onSetDmDirectoryError: (String?) -> Unit,
  onSetGroupDirectorySuggestions: (List<DmSuggestion>) -> Unit,
  onSetGroupDirectoryBusy: (Boolean) -> Unit,
  onSetGroupDirectoryError: (String?) -> Unit,
) {
  LaunchedEffect(isAuthenticated, userAddress) {
    if (isAuthenticated && !connected && !connecting && userAddress != null) {
      onSetConnecting(true)
      try {
        chatService.connect(userAddress)
      } catch (_: kotlinx.coroutines.CancellationException) {
        throw kotlinx.coroutines.CancellationException("cancelled")
      } catch (_: Exception) {
        // Keep the screen usable; user can retry by re-entering chat.
      } finally {
        onSetConnecting(false)
      }
    }
  }

  LaunchedEffect(activeConversationId, currentView) {
    if (activeConversationId != null) {
      onSetCurrentView(ChatView.Thread)
    } else if (currentView == ChatView.Thread) {
      onSetCurrentView(ChatView.Conversations)
    }
    onSetShowSettingsSheet(false)
    onSetDisappearingRetentionSeconds(chatService.activeDisappearingSeconds())
  }

  val inThread = currentView == ChatView.Thread || currentView == ChatView.Assistant || activeConversationId != null
  LaunchedEffect(inThread) {
    onThreadVisibilityChange(inThread)
  }

  LaunchedEffect(currentView, newDmAddress) {
    refreshDirectorySuggestions(
      currentView = currentView,
      expectedView = ChatView.NewConversation,
      rawQuery = newDmAddress,
      onSuggestions = onSetDmDirectorySuggestions,
      onBusy = onSetDmDirectoryBusy,
      onError = onSetDmDirectoryError,
    )
  }

  LaunchedEffect(currentView, newGroupMemberQuery) {
    refreshDirectorySuggestions(
      currentView = currentView,
      expectedView = ChatView.NewGroupMembers,
      rawQuery = newGroupMemberQuery,
      onSuggestions = onSetGroupDirectorySuggestions,
      onBusy = onSetGroupDirectoryBusy,
      onError = onSetGroupDirectoryError,
    )
  }
}
