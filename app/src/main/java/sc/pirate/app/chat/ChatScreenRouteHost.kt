package sc.pirate.app.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import sc.pirate.app.assistant.AgoraVoiceController
import sc.pirate.app.assistant.AssistantService
import org.xmtp.android.library.libxmtp.PermissionOption

internal data class ChatComposerUiState(
  val newDmAddress: String,
  val newDmBusy: Boolean,
  val newDmError: String?,
  val dmRecentSuggestions: List<DmSuggestion>,
  val dmVisibleDirectorySuggestions: List<DmSuggestion>,
  val dmDirectoryBusy: Boolean,
  val dmDirectoryError: String?,
  val newGroupMemberQuery: String,
  val newGroupMembers: List<String>,
  val groupRecentSuggestions: List<DmSuggestion>,
  val groupVisibleDirectorySuggestions: List<DmSuggestion>,
  val groupDirectoryBusy: Boolean,
  val groupDirectoryError: String?,
  val newGroupName: String,
  val newGroupDescription: String,
  val newGroupBusy: Boolean,
  val newGroupError: String?,
)

internal data class ChatGroupSettingsUiState(
  val groupMetaName: String,
  val groupMetaDescription: String,
  val groupMetaImageUrl: String,
  val groupMetaAppData: String,
  val groupMetaBusy: Boolean,
  val groupMetaError: String?,
  val groupPermissionAddMembers: PermissionOption,
  val groupPermissionMetadata: PermissionOption,
  val groupPermissionBusy: Boolean,
  val groupPermissionError: String?,
  val disappearingRetentionSeconds: Long?,
  val disappearingBusy: Boolean,
)

@Composable
internal fun ChatScreenRouteHost(
  currentView: ChatView,
  activeConversationId: String?,
  activeConversation: ConversationItem?,
  conversations: List<ConversationItem>,
  messages: List<ChatMessage>,
  assistantService: AssistantService,
  voiceController: AgoraVoiceController,
  isAuthenticated: Boolean,
  xmtpConnecting: Boolean,
  userAddress: String?,
  composerState: ChatComposerUiState,
  showSettingsSheet: Boolean,
  settingsState: ChatGroupSettingsUiState,
  onSetCurrentView: (ChatView) -> Unit,
  onOpenConversation: (String) -> Unit,
  onCloseConversation: () -> Unit,
  onSendMessage: (String) -> Unit,
  onOpenSettings: () -> Unit,
  onOpenActiveConversationProfile: () -> Unit,
  onDmQueryChange: (String) -> Unit,
  onSubmitDm: () -> Unit,
  onOpenDmSuggestion: (String) -> Unit,
  onOpenGroupComposer: () -> Unit,
  onGroupMembersBack: () -> Unit,
  onGroupMemberQueryChange: (String) -> Unit,
  onAddGroupMemberQuery: () -> Unit,
  onAddGroupMemberSuggestion: (String) -> Unit,
  onRemoveGroupMember: (String) -> Unit,
  onProceedToGroupDetails: () -> Unit,
  onGroupDetailsBack: () -> Unit,
  onGroupNameChange: (String) -> Unit,
  onGroupDescriptionChange: (String) -> Unit,
  onCreateGroup: () -> Unit,
  onOpenComposer: () -> Unit,
  onGroupMetaNameChange: (String) -> Unit,
  onGroupMetaDescriptionChange: (String) -> Unit,
  onGroupMetaImageUrlChange: (String) -> Unit,
  onGroupMetaAppDataChange: (String) -> Unit,
  onSaveGroupMetadata: () -> Unit,
  onGroupPermissionAddMembersChange: (PermissionOption) -> Unit,
  onGroupPermissionMetadataChange: (PermissionOption) -> Unit,
  onSaveGroupPermissions: () -> Unit,
  onSelectDisappearing: (Long?) -> Unit,
  onDismissSettings: () -> Unit,
  onOpenDrawer: () -> Unit,
  onShowMessage: (String) -> Unit,
  onNavigateToCall: () -> Unit,
  onNavigateToCredits: () -> Unit,
  onNavigateToVerifyIdentity: () -> Unit,
) {
  Box(modifier = Modifier.fillMaxSize()) {
    when {
      currentView == ChatView.Assistant -> {
        AssistantThread(
          assistantService = assistantService,
          voiceController = voiceController,
          wallet = userAddress,
          onBack = { onSetCurrentView(ChatView.Conversations) },
          onShowMessage = onShowMessage,
          onNavigateToCall = onNavigateToCall,
          onNavigateToCredits = onNavigateToCredits,
          onNavigateToVerifyIdentity = onNavigateToVerifyIdentity,
        )
      }

      currentView == ChatView.Thread && activeConversationId != null -> {
        if (activeConversation == null) {
          ConnectingPlaceholder()
        } else {
          MessageThread(
            messages = messages,
            conversation = activeConversation,
            onBack = onCloseConversation,
            onSend = onSendMessage,
            onOpenSettings = onOpenSettings,
            onOpenProfile = onOpenActiveConversationProfile,
          )
        }
      }

      currentView == ChatView.NewConversation -> {
        NewConversationScreen(
          query = composerState.newDmAddress,
          submitBusy = composerState.newDmBusy,
          submitError = composerState.newDmError,
          recents = composerState.dmRecentSuggestions,
          directorySuggestions = composerState.dmVisibleDirectorySuggestions,
          directoryBusy = composerState.dmDirectoryBusy,
          directoryError = composerState.dmDirectoryError,
          onBack = { onSetCurrentView(ChatView.Conversations) },
          onQueryChange = onDmQueryChange,
          onSubmit = onSubmitDm,
          onOpenSuggestion = onOpenDmSuggestion,
          onOpenGroup = onOpenGroupComposer,
        )
      }

      currentView == ChatView.NewGroupMembers -> {
        NewGroupMembersScreen(
          query = composerState.newGroupMemberQuery,
          members = composerState.newGroupMembers,
          recents = composerState.groupRecentSuggestions,
          directorySuggestions = composerState.groupVisibleDirectorySuggestions,
          directoryBusy = composerState.groupDirectoryBusy,
          directoryError = composerState.groupDirectoryError,
          busy = composerState.newGroupBusy,
          error = composerState.newGroupError,
          onBack = onGroupMembersBack,
          onQueryChange = onGroupMemberQueryChange,
          onAddQuery = onAddGroupMemberQuery,
          onAddSuggestion = onAddGroupMemberSuggestion,
          onRemoveMember = onRemoveGroupMember,
          onNext = onProceedToGroupDetails,
        )
      }

      currentView == ChatView.NewGroupDetails -> {
        NewGroupDetailsScreen(
          groupName = composerState.newGroupName,
          description = composerState.newGroupDescription,
          memberCount = composerState.newGroupMembers.size,
          busy = composerState.newGroupBusy,
          error = composerState.newGroupError,
          onBack = onGroupDetailsBack,
          onNameChange = onGroupNameChange,
          onDescriptionChange = onGroupDescriptionChange,
          onCreate = onCreateGroup,
        )
      }

      else -> {
        ConversationList(
          conversations = conversations,
          assistantService = assistantService,
          isAuthenticated = isAuthenticated,
          xmtpConnecting = xmtpConnecting,
          onOpenAssistant = { onSetCurrentView(ChatView.Assistant) },
          onOpenComposer = onOpenComposer,
          onOpenConversation = onOpenConversation,
          onOpenDrawer = onOpenDrawer,
        )
      }
    }

    if (showSettingsSheet) {
      ChatSettingsSheet(
        isGroupConversation = activeConversation?.type == ConversationType.GROUP,
        groupMetaName = settingsState.groupMetaName,
        onGroupMetaNameChange = onGroupMetaNameChange,
        groupMetaDescription = settingsState.groupMetaDescription,
        onGroupMetaDescriptionChange = onGroupMetaDescriptionChange,
        groupMetaImageUrl = settingsState.groupMetaImageUrl,
        onGroupMetaImageUrlChange = onGroupMetaImageUrlChange,
        groupMetaAppData = settingsState.groupMetaAppData,
        onGroupMetaAppDataChange = onGroupMetaAppDataChange,
        groupMetaBusy = settingsState.groupMetaBusy,
        groupMetaError = settingsState.groupMetaError,
        onSaveGroupMetadata = onSaveGroupMetadata,
        groupPermissionAddMembers = settingsState.groupPermissionAddMembers,
        onGroupPermissionAddMembersChange = onGroupPermissionAddMembersChange,
        groupPermissionMetadata = settingsState.groupPermissionMetadata,
        onGroupPermissionMetadataChange = onGroupPermissionMetadataChange,
        groupPermissionBusy = settingsState.groupPermissionBusy,
        groupPermissionError = settingsState.groupPermissionError,
        onSaveGroupPermissions = onSaveGroupPermissions,
        disappearingRetentionSeconds = settingsState.disappearingRetentionSeconds,
        disappearingBusy = settingsState.disappearingBusy,
        onSelectDisappearing = onSelectDisappearing,
        onDismiss = onDismissSettings,
      )
    }
  }
}
