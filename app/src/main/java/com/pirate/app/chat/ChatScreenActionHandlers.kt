package com.pirate.app.chat

import org.xmtp.android.library.libxmtp.PermissionOption

internal fun resetChatComposerDraft(
  onSetNewDmAddress: (String) -> Unit,
  onSetNewDmError: (String?) -> Unit,
  onSetDmDirectorySuggestions: (List<DmSuggestion>) -> Unit,
  onSetDmDirectoryBusy: (Boolean) -> Unit,
  onSetDmDirectoryError: (String?) -> Unit,
  onSetNewGroupMemberQuery: (String) -> Unit,
  onSetNewGroupMembers: (List<String>) -> Unit,
  onSetNewGroupName: (String) -> Unit,
  onSetNewGroupDescription: (String) -> Unit,
  onSetNewGroupError: (String?) -> Unit,
  onSetGroupDirectorySuggestions: (List<DmSuggestion>) -> Unit,
  onSetGroupDirectoryBusy: (Boolean) -> Unit,
  onSetGroupDirectoryError: (String?) -> Unit,
) {
  onSetNewDmAddress("")
  onSetNewDmError(null)
  onSetDmDirectorySuggestions(emptyList())
  onSetDmDirectoryBusy(false)
  onSetDmDirectoryError(null)
  onSetNewGroupMemberQuery("")
  onSetNewGroupMembers(emptyList())
  onSetNewGroupName("")
  onSetNewGroupDescription("")
  onSetNewGroupError(null)
  onSetGroupDirectorySuggestions(emptyList())
  onSetGroupDirectoryBusy(false)
  onSetGroupDirectoryError(null)
}

internal fun resetChatGroupDraft(
  onSetNewGroupMemberQuery: (String) -> Unit,
  onSetNewGroupMembers: (List<String>) -> Unit,
  onSetNewGroupName: (String) -> Unit,
  onSetNewGroupDescription: (String) -> Unit,
  onSetNewGroupError: (String?) -> Unit,
  onSetGroupDirectorySuggestions: (List<DmSuggestion>) -> Unit,
  onSetGroupDirectoryBusy: (Boolean) -> Unit,
  onSetGroupDirectoryError: (String?) -> Unit,
) {
  onSetNewGroupMemberQuery("")
  onSetNewGroupMembers(emptyList())
  onSetNewGroupName("")
  onSetNewGroupDescription("")
  onSetNewGroupError(null)
  onSetGroupDirectorySuggestions(emptyList())
  onSetGroupDirectoryBusy(false)
  onSetGroupDirectoryError(null)
}

internal suspend fun openDmWithUi(
  newDmBusy: Boolean,
  chatService: XmtpChatService,
  isAuthenticated: Boolean,
  userAddress: String?,
  targetInput: String,
  onSetNewDmBusy: (Boolean) -> Unit,
  onSetNewDmError: (String?) -> Unit,
  onSetNewDmAddress: (String) -> Unit,
  onShowMessage: (String) -> Unit,
) {
  if (newDmBusy) return
  try {
    onSetNewDmBusy(true)
    onSetNewDmError(null)
    openDmConversation(
      chatService = chatService,
      isAuthenticated = isAuthenticated,
      userAddress = userAddress,
      targetInput = targetInput,
    )
    onSetNewDmAddress("")
  } catch (error: Exception) {
    val message = error.message ?: "Unknown error"
    onSetNewDmError(message)
    onShowMessage("New DM failed: $message")
  } finally {
    onSetNewDmBusy(false)
  }
}

internal suspend fun createGroupWithUi(
  newGroupBusy: Boolean,
  newGroupMembers: List<String>,
  chatService: XmtpChatService,
  isAuthenticated: Boolean,
  userAddress: String?,
  newGroupName: String,
  newGroupDescription: String,
  onSetNewGroupBusy: (Boolean) -> Unit,
  onSetNewGroupError: (String?) -> Unit,
  onSetNewGroupName: (String) -> Unit,
  onSetNewGroupDescription: (String) -> Unit,
  onSetNewGroupMemberQuery: (String) -> Unit,
  onSetNewGroupMembers: (List<String>) -> Unit,
  onShowMessage: (String) -> Unit,
) {
  if (newGroupBusy || newGroupMembers.isEmpty()) return
  try {
    onSetNewGroupBusy(true)
    onSetNewGroupError(null)
    createGroupConversation(
      chatService = chatService,
      isAuthenticated = isAuthenticated,
      userAddress = userAddress,
      members = newGroupMembers,
      name = newGroupName,
      description = newGroupDescription,
    )
    onSetNewGroupName("")
    onSetNewGroupDescription("")
    onSetNewGroupMemberQuery("")
    onSetNewGroupMembers(emptyList())
  } catch (error: Exception) {
    val message = error.message ?: "Unknown error"
    onSetNewGroupError(message)
    onShowMessage("Create group failed: $message")
  } finally {
    onSetNewGroupBusy(false)
  }
}

internal suspend fun sendMessageWithUi(
  chatService: XmtpChatService,
  text: String,
  onShowMessage: (String) -> Unit,
) {
  try {
    chatService.sendMessage(text)
  } catch (error: Exception) {
    onShowMessage("Send failed: ${error.message}")
  }
}

internal suspend fun openThreadSettingsWithUi(
  chatService: XmtpChatService,
  isGroupConversation: Boolean,
  onSetShowSettingsSheet: (Boolean) -> Unit,
  onSetGroupMetaBusy: (Boolean) -> Unit,
  onSetGroupMetaError: (String?) -> Unit,
  onSetGroupPermissionBusy: (Boolean) -> Unit,
  onSetGroupPermissionError: (String?) -> Unit,
  onSetGroupMetaName: (String) -> Unit,
  onSetGroupMetaDescription: (String) -> Unit,
  onSetGroupMetaImageUrl: (String) -> Unit,
  onSetGroupMetaAppData: (String) -> Unit,
  onSetGroupPermissionAddMembers: (PermissionOption) -> Unit,
  onSetGroupPermissionMetadata: (PermissionOption) -> Unit,
) {
  onSetShowSettingsSheet(true)
  if (!isGroupConversation) return

  onSetGroupMetaBusy(true)
  onSetGroupMetaError(null)
  onSetGroupPermissionBusy(true)
  onSetGroupPermissionError(null)
  val loaded = loadActiveGroupSettings(chatService)
  onSetGroupMetaName(loaded.groupMetaName)
  onSetGroupMetaDescription(loaded.groupMetaDescription)
  onSetGroupMetaImageUrl(loaded.groupMetaImageUrl)
  onSetGroupMetaAppData(loaded.groupMetaAppData)
  onSetGroupMetaError(loaded.groupMetaError)
  loaded.groupPermissionAddMembers?.let { onSetGroupPermissionAddMembers(it) }
  loaded.groupPermissionMetadata?.let { onSetGroupPermissionMetadata(it) }
  onSetGroupPermissionError(loaded.groupPermissionError)
  onSetGroupMetaBusy(false)
  onSetGroupPermissionBusy(false)
}

internal suspend fun saveGroupMetadataWithUi(
  chatService: XmtpChatService,
  name: String,
  description: String,
  imageUrl: String,
  appData: String,
  onSetGroupMetaBusy: (Boolean) -> Unit,
  onSetGroupMetaError: (String?) -> Unit,
  onShowMessage: (String) -> Unit,
) {
  onSetGroupMetaBusy(true)
  onSetGroupMetaError(null)
  saveActiveGroupMetadata(
    chatService = chatService,
    name = name,
    description = description,
    imageUrl = imageUrl,
    appData = appData,
  ).onFailure { error ->
    val message = error.message ?: "Failed to update group metadata"
    onSetGroupMetaError(message)
    onShowMessage(message)
  }
  onSetGroupMetaBusy(false)
}

internal suspend fun saveGroupPermissionsWithUi(
  chatService: XmtpChatService,
  groupPermissionAddMembers: PermissionOption,
  groupPermissionMetadata: PermissionOption,
  onSetGroupPermissionBusy: (Boolean) -> Unit,
  onSetGroupPermissionError: (String?) -> Unit,
  onShowMessage: (String) -> Unit,
) {
  onSetGroupPermissionBusy(true)
  onSetGroupPermissionError(null)
  saveActiveGroupPermissions(
    chatService = chatService,
    addMemberPolicy = groupPermissionAddMembers,
    metadataPolicy = groupPermissionMetadata,
  ).onFailure { error ->
    val message = error.message ?: "Failed to update group permissions"
    onSetGroupPermissionError(message)
    onShowMessage(message)
  }
  onSetGroupPermissionBusy(false)
}

internal suspend fun saveDisappearingWithUi(
  chatService: XmtpChatService,
  seconds: Long?,
  onSetDisappearingBusy: (Boolean) -> Unit,
  onSetDisappearingRetentionSeconds: (Long?) -> Unit,
  onSetShowSettingsSheet: (Boolean) -> Unit,
  onShowMessage: (String) -> Unit,
) {
  onSetDisappearingBusy(true)
  saveDisappearingSeconds(chatService = chatService, seconds = seconds)
    .onSuccess {
      onSetDisappearingRetentionSeconds(seconds)
      onSetShowSettingsSheet(false)
    }
    .onFailure { error ->
      onShowMessage("Failed to update disappearing messages: ${error.message}")
    }
  onSetDisappearingBusy(false)
}
