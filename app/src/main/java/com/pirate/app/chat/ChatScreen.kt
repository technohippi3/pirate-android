package com.pirate.app.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.pirate.app.assistant.AgoraVoiceController
import com.pirate.app.assistant.AssistantService
import com.pirate.app.profile.TempoNameRecordsApi
import kotlinx.coroutines.launch
import org.xmtp.android.library.libxmtp.PermissionOption

internal enum class ChatView {
  Conversations,
  Thread,
  Assistant,
  NewConversation,
  NewGroupMembers,
  NewGroupDetails,
}

internal data class DmSuggestion(
  val title: String,
  val subtitle: String,
  val inputValue: String,
  val avatarUri: String?,
  val verificationAddress: String? = null,
)

@Composable
fun ChatScreen(
  chatService: XmtpChatService,
  assistantService: AssistantService,
  voiceController: AgoraVoiceController,
  isAuthenticated: Boolean,
  userAddress: String?,
  onOpenDrawer: () -> Unit,
  onShowMessage: (String) -> Unit,
  onNavigateToCall: () -> Unit = {},
  onNavigateToCredits: () -> Unit = {},
  onNavigateToVerifyIdentity: () -> Unit = {},
  onOpenProfile: (String) -> Unit = {},
  onThreadVisibilityChange: (Boolean) -> Unit = {},
) {
  val scope = rememberCoroutineScope()
  val connected by chatService.connected.collectAsState()
  val conversations by chatService.conversations.collectAsState()
  val messages by chatService.messages.collectAsState()
  val activeConversationId by chatService.activeConversationId.collectAsState()
  val activeConversation =
    remember(activeConversationId, conversations) {
      conversations.find { it.id == activeConversationId }
    }

  var currentView by rememberSaveable { mutableStateOf(ChatView.Conversations) }
  var connecting by remember { mutableStateOf(false) }
  var newDmAddress by remember { mutableStateOf("") }
  var newDmBusy by remember { mutableStateOf(false) }
  var newDmError by remember { mutableStateOf<String?>(null) }
  var dmDirectorySuggestions by remember { mutableStateOf<List<DmSuggestion>>(emptyList()) }
  var dmDirectoryBusy by remember { mutableStateOf(false) }
  var dmDirectoryError by remember { mutableStateOf<String?>(null) }
  var newGroupMemberQuery by remember { mutableStateOf("") }
  var newGroupMembers by remember { mutableStateOf<List<String>>(emptyList()) }
  var newGroupName by remember { mutableStateOf("") }
  var newGroupDescription by remember { mutableStateOf("") }
  var newGroupBusy by remember { mutableStateOf(false) }
  var newGroupError by remember { mutableStateOf<String?>(null) }
  var groupDirectorySuggestions by remember { mutableStateOf<List<DmSuggestion>>(emptyList()) }
  var groupDirectoryBusy by remember { mutableStateOf(false) }
  var groupDirectoryError by remember { mutableStateOf<String?>(null) }
  var showSettingsSheet by remember { mutableStateOf(false) }
  var disappearingRetentionSeconds by remember { mutableStateOf<Long?>(null) }
  var disappearingBusy by remember { mutableStateOf(false) }
  var groupMetaName by remember { mutableStateOf("") }
  var groupMetaDescription by remember { mutableStateOf("") }
  var groupMetaImageUrl by remember { mutableStateOf("") }
  var groupMetaAppData by remember { mutableStateOf("") }
  var groupMetaBusy by remember { mutableStateOf(false) }
  var groupMetaError by remember { mutableStateOf<String?>(null) }
  var groupPermissionAddMembers by remember { mutableStateOf(PermissionOption.Admin) }
  var groupPermissionMetadata by remember { mutableStateOf(PermissionOption.Allow) }
  var groupPermissionBusy by remember { mutableStateOf(false) }
  var groupPermissionError by remember { mutableStateOf<String?>(null) }

  val dmRecentSuggestions = remember(conversations, newDmAddress, currentView, activeConversationId) {
    if (currentView == ChatView.NewConversation) {
      buildDmSuggestions(
        conversations = conversations,
        query = newDmAddress,
        excludeConversationId = activeConversationId,
      )
    } else {
      emptyList()
    }
  }
  val groupRecentSuggestions = remember(conversations, newGroupMemberQuery, currentView, activeConversationId) {
    if (currentView == ChatView.NewGroupMembers) {
      buildDmSuggestions(
        conversations = conversations,
        query = newGroupMemberQuery,
        excludeConversationId = activeConversationId,
      )
    } else {
      emptyList()
    }
  }
  val dmVisibleDirectorySuggestions =
    remember(dmDirectorySuggestions, dmRecentSuggestions) {
      dropKnownSuggestions(
        directorySuggestions = dmDirectorySuggestions,
        existingSuggestions = dmRecentSuggestions,
      )
    }
  val groupVisibleDirectorySuggestions =
    remember(groupDirectorySuggestions, groupRecentSuggestions) {
      dropKnownSuggestions(
        directorySuggestions = groupDirectorySuggestions,
        existingSuggestions = groupRecentSuggestions,
      )
    }

  ChatScreenLaunchEffects(
    chatService = chatService,
    isAuthenticated = isAuthenticated,
    userAddress = userAddress,
    connected = connected,
    connecting = connecting,
    activeConversationId = activeConversationId,
    currentView = currentView,
    newDmAddress = newDmAddress,
    newGroupMemberQuery = newGroupMemberQuery,
    onSetCurrentView = { currentView = it },
    onSetConnecting = { connecting = it },
    onSetShowSettingsSheet = { showSettingsSheet = it },
    onSetDisappearingRetentionSeconds = { disappearingRetentionSeconds = it },
    onThreadVisibilityChange = onThreadVisibilityChange,
    onSetDmDirectorySuggestions = { dmDirectorySuggestions = it },
    onSetDmDirectoryBusy = { dmDirectoryBusy = it },
    onSetDmDirectoryError = { dmDirectoryError = it },
    onSetGroupDirectorySuggestions = { groupDirectorySuggestions = it },
    onSetGroupDirectoryBusy = { groupDirectoryBusy = it },
    onSetGroupDirectoryError = { groupDirectoryError = it },
  )

  val composerState =
    ChatComposerUiState(
      newDmAddress = newDmAddress,
      newDmBusy = newDmBusy,
      newDmError = newDmError,
      dmRecentSuggestions = dmRecentSuggestions,
      dmVisibleDirectorySuggestions = dmVisibleDirectorySuggestions,
      dmDirectoryBusy = dmDirectoryBusy,
      dmDirectoryError = dmDirectoryError,
      newGroupMemberQuery = newGroupMemberQuery,
      newGroupMembers = newGroupMembers,
      groupRecentSuggestions = groupRecentSuggestions,
      groupVisibleDirectorySuggestions = groupVisibleDirectorySuggestions,
      groupDirectoryBusy = groupDirectoryBusy,
      groupDirectoryError = groupDirectoryError,
      newGroupName = newGroupName,
      newGroupDescription = newGroupDescription,
      newGroupBusy = newGroupBusy,
      newGroupError = newGroupError,
    )
  val settingsState =
    ChatGroupSettingsUiState(
      groupMetaName = groupMetaName,
      groupMetaDescription = groupMetaDescription,
      groupMetaImageUrl = groupMetaImageUrl,
      groupMetaAppData = groupMetaAppData,
      groupMetaBusy = groupMetaBusy,
      groupMetaError = groupMetaError,
      groupPermissionAddMembers = groupPermissionAddMembers,
      groupPermissionMetadata = groupPermissionMetadata,
      groupPermissionBusy = groupPermissionBusy,
      groupPermissionError = groupPermissionError,
      disappearingRetentionSeconds = disappearingRetentionSeconds,
      disappearingBusy = disappearingBusy,
    )

  ChatScreenRouteHost(
    currentView = currentView,
    activeConversationId = activeConversationId,
    activeConversation = activeConversation,
    conversations = conversations,
    messages = messages,
    assistantService = assistantService,
    voiceController = voiceController,
    isAuthenticated = isAuthenticated,
    xmtpConnecting = connecting,
    userAddress = userAddress,
    composerState = composerState,
    showSettingsSheet = showSettingsSheet,
    settingsState = settingsState,
    onSetCurrentView = { currentView = it },
    onOpenConversation = { conversationId ->
      scope.launch {
        chatService.openConversation(conversationId)
      }
    },
    onCloseConversation = {
      chatService.closeConversation()
      currentView = ChatView.Conversations
    },
    onSendMessage = { text ->
      scope.launch {
        sendMessageWithUi(
          chatService = chatService,
          text = text,
          onShowMessage = onShowMessage,
        )
      }
    },
    onOpenActiveConversationProfile = {
      val conversation = activeConversation ?: return@ChatScreenRouteHost
      if (conversation.type != ConversationType.DM) return@ChatScreenRouteHost
      scope.launch {
        val directAddress =
          conversation.peerAddress
            ?.trim()
            ?.takeIf { looksLikeEthereumAddress(it) }
            ?.let(::normalizeEthAddressOrNull)
        val resolvedAddress =
          directAddress ?: run {
            val display = conversation.displayName.trim()
            if (!looksLikeTempoName(display)) null
            else TempoNameRecordsApi.resolveAddressForName(display)?.let(::normalizeEthAddressOrNull)
          }
        if (!resolvedAddress.isNullOrBlank()) {
          onOpenProfile(resolvedAddress)
        } else {
          onShowMessage("Profile unavailable")
        }
      }
    },
    onOpenSettings = {
      scope.launch {
        openThreadSettingsWithUi(
          chatService = chatService,
          isGroupConversation = activeConversation?.type == ConversationType.GROUP,
          onSetShowSettingsSheet = { showSettingsSheet = it },
          onSetGroupMetaBusy = { groupMetaBusy = it },
          onSetGroupMetaError = { groupMetaError = it },
          onSetGroupPermissionBusy = { groupPermissionBusy = it },
          onSetGroupPermissionError = { groupPermissionError = it },
          onSetGroupMetaName = { groupMetaName = it },
          onSetGroupMetaDescription = { groupMetaDescription = it },
          onSetGroupMetaImageUrl = { groupMetaImageUrl = it },
          onSetGroupMetaAppData = { groupMetaAppData = it },
          onSetGroupPermissionAddMembers = { groupPermissionAddMembers = it },
          onSetGroupPermissionMetadata = { groupPermissionMetadata = it },
        )
      }
    },
    onDmQueryChange = {
      newDmAddress = it
      if (!newDmError.isNullOrBlank()) newDmError = null
      if (!dmDirectoryError.isNullOrBlank()) dmDirectoryError = null
    },
    onSubmitDm = {
      scope.launch {
        openDmWithUi(
          newDmBusy = newDmBusy,
          chatService = chatService,
          isAuthenticated = isAuthenticated,
          userAddress = userAddress,
          targetInput = newDmAddress,
          onSetNewDmBusy = { newDmBusy = it },
          onSetNewDmError = { newDmError = it },
          onSetNewDmAddress = { newDmAddress = it },
          onShowMessage = onShowMessage,
        )
      }
    },
    onOpenDmSuggestion = { suggestion ->
      scope.launch {
        openDmWithUi(
          newDmBusy = newDmBusy,
          chatService = chatService,
          isAuthenticated = isAuthenticated,
          userAddress = userAddress,
          targetInput = suggestion,
          onSetNewDmBusy = { newDmBusy = it },
          onSetNewDmError = { newDmError = it },
          onSetNewDmAddress = { newDmAddress = it },
          onShowMessage = onShowMessage,
        )
      }
    },
    onOpenGroupComposer = {
      resetChatGroupDraft(
        onSetNewGroupMemberQuery = { newGroupMemberQuery = it },
        onSetNewGroupMembers = { newGroupMembers = it },
        onSetNewGroupName = { newGroupName = it },
        onSetNewGroupDescription = { newGroupDescription = it },
        onSetNewGroupError = { newGroupError = it },
        onSetGroupDirectorySuggestions = { groupDirectorySuggestions = it },
        onSetGroupDirectoryBusy = { groupDirectoryBusy = it },
        onSetGroupDirectoryError = { groupDirectoryError = it },
      )
      currentView = ChatView.NewGroupMembers
    },
    onGroupMembersBack = { currentView = ChatView.NewConversation },
    onGroupMemberQueryChange = {
      newGroupMemberQuery = it
      if (!newGroupError.isNullOrBlank()) newGroupError = null
      if (!groupDirectoryError.isNullOrBlank()) groupDirectoryError = null
    },
    onAddGroupMemberQuery = {
      newGroupMembers = mergeGroupMembers(existingMembers = newGroupMembers, rawInput = newGroupMemberQuery)
      newGroupMemberQuery = ""
    },
    onAddGroupMemberSuggestion = { suggestion ->
      newGroupMembers = mergeGroupMembers(existingMembers = newGroupMembers, rawInput = suggestion)
      newGroupMemberQuery = ""
    },
    onRemoveGroupMember = { member ->
      newGroupMembers = newGroupMembers.filterNot { it.equals(member, ignoreCase = true) }
    },
    onProceedToGroupDetails = { currentView = ChatView.NewGroupDetails },
    onGroupDetailsBack = { currentView = ChatView.NewGroupMembers },
    onGroupNameChange = {
      newGroupName = it
      if (!newGroupError.isNullOrBlank()) newGroupError = null
    },
    onGroupDescriptionChange = { newGroupDescription = it },
    onCreateGroup = {
      scope.launch {
        createGroupWithUi(
          newGroupBusy = newGroupBusy,
          newGroupMembers = newGroupMembers,
          chatService = chatService,
          isAuthenticated = isAuthenticated,
          userAddress = userAddress,
          newGroupName = newGroupName,
          newGroupDescription = newGroupDescription,
          onSetNewGroupBusy = { newGroupBusy = it },
          onSetNewGroupError = { newGroupError = it },
          onSetNewGroupName = { newGroupName = it },
          onSetNewGroupDescription = { newGroupDescription = it },
          onSetNewGroupMemberQuery = { newGroupMemberQuery = it },
          onSetNewGroupMembers = { newGroupMembers = it },
          onShowMessage = onShowMessage,
        )
      }
    },
    onOpenComposer = {
      resetChatComposerDraft(
        onSetNewDmAddress = { newDmAddress = it },
        onSetNewDmError = { newDmError = it },
        onSetDmDirectorySuggestions = { dmDirectorySuggestions = it },
        onSetDmDirectoryBusy = { dmDirectoryBusy = it },
        onSetDmDirectoryError = { dmDirectoryError = it },
        onSetNewGroupMemberQuery = { newGroupMemberQuery = it },
        onSetNewGroupMembers = { newGroupMembers = it },
        onSetNewGroupName = { newGroupName = it },
        onSetNewGroupDescription = { newGroupDescription = it },
        onSetNewGroupError = { newGroupError = it },
        onSetGroupDirectorySuggestions = { groupDirectorySuggestions = it },
        onSetGroupDirectoryBusy = { groupDirectoryBusy = it },
        onSetGroupDirectoryError = { groupDirectoryError = it },
      )
      currentView = ChatView.NewConversation
    },
    onGroupMetaNameChange = { groupMetaName = it },
    onGroupMetaDescriptionChange = { groupMetaDescription = it },
    onGroupMetaImageUrlChange = { groupMetaImageUrl = it },
    onGroupMetaAppDataChange = { groupMetaAppData = it },
    onSaveGroupMetadata = {
      scope.launch {
        saveGroupMetadataWithUi(
          chatService = chatService,
          name = groupMetaName,
          description = groupMetaDescription,
          imageUrl = groupMetaImageUrl,
          appData = groupMetaAppData,
          onSetGroupMetaBusy = { groupMetaBusy = it },
          onSetGroupMetaError = { groupMetaError = it },
          onShowMessage = onShowMessage,
        )
      }
    },
    onGroupPermissionAddMembersChange = { groupPermissionAddMembers = it },
    onGroupPermissionMetadataChange = { groupPermissionMetadata = it },
    onSaveGroupPermissions = {
      scope.launch {
        saveGroupPermissionsWithUi(
          chatService = chatService,
          groupPermissionAddMembers = groupPermissionAddMembers,
          groupPermissionMetadata = groupPermissionMetadata,
          onSetGroupPermissionBusy = { groupPermissionBusy = it },
          onSetGroupPermissionError = { groupPermissionError = it },
          onShowMessage = onShowMessage,
        )
      }
    },
    onSelectDisappearing = { seconds ->
      scope.launch {
        saveDisappearingWithUi(
          chatService = chatService,
          seconds = seconds,
          onSetDisappearingBusy = { disappearingBusy = it },
          onSetDisappearingRetentionSeconds = { disappearingRetentionSeconds = it },
          onSetShowSettingsSheet = { showSettingsSheet = it },
          onShowMessage = onShowMessage,
        )
      }
    },
    onDismissSettings = { showSettingsSheet = false },
    onOpenDrawer = onOpenDrawer,
    onShowMessage = onShowMessage,
    onNavigateToCall = onNavigateToCall,
    onNavigateToCredits = onNavigateToCredits,
    onNavigateToVerifyIdentity = onNavigateToVerifyIdentity,
  )
}
