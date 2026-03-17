package com.pirate.app.chat

import org.xmtp.android.library.libxmtp.PermissionOption

internal fun mergeGroupMembers(
  existingMembers: List<String>,
  rawInput: String,
): List<String> {
  val inputs = parseGroupMembersInput(rawInput)
  if (inputs.isEmpty()) return existingMembers
  val deduped = existingMembers.associateBy { it.lowercase() }.toMutableMap()
  inputs.forEach { member ->
    deduped.putIfAbsent(member.lowercase(), member)
  }
  return deduped.values.toList()
}

internal suspend fun openDmConversation(
  chatService: XmtpChatService,
  isAuthenticated: Boolean,
  userAddress: String?,
  targetInput: String,
) {
  val target = targetInput.trim()
  if (target.isBlank()) return
  if (!isAuthenticated || userAddress.isNullOrBlank()) {
    throw IllegalStateException("Sign in to start a DM")
  }
  if (!chatService.connected.value) {
    chatService.connect(userAddress)
  }
  val conversationId = chatService.newDm(target)
  chatService.openConversation(conversationId)
}

internal suspend fun createGroupConversation(
  chatService: XmtpChatService,
  isAuthenticated: Boolean,
  userAddress: String?,
  members: List<String>,
  name: String,
  description: String,
) {
  if (members.isEmpty()) return
  if (!isAuthenticated || userAddress.isNullOrBlank()) {
    throw IllegalStateException("Sign in to create a group")
  }
  if (!chatService.connected.value) {
    chatService.connect(userAddress)
  }
  val groupId =
    chatService.newGroup(
      memberAddressesOrInboxIds = members,
      name = name,
      description = description,
      imageUrl = "",
      appData = "",
      permissionMode = GroupPermissionMode.ALL_MEMBERS,
    )
  chatService.openConversation(groupId)
}

internal data class ChatGroupSettingsLoadResult(
  val groupMetaName: String,
  val groupMetaDescription: String,
  val groupMetaImageUrl: String,
  val groupMetaAppData: String,
  val groupMetaError: String?,
  val groupPermissionAddMembers: PermissionOption?,
  val groupPermissionMetadata: PermissionOption?,
  val groupPermissionError: String?,
)

internal suspend fun loadActiveGroupSettings(
  chatService: XmtpChatService,
): ChatGroupSettingsLoadResult {
  var groupMetaName = ""
  var groupMetaDescription = ""
  var groupMetaImageUrl = ""
  var groupMetaAppData = ""
  var groupMetaError: String? = null
  var groupPermissionAddMembers: PermissionOption? = null
  var groupPermissionMetadata: PermissionOption? = null
  var groupPermissionError: String? = null

  runCatching { chatService.getActiveGroupMetadata() }
    .onSuccess { meta ->
      if (meta != null) {
        groupMetaName = meta.name
        groupMetaDescription = meta.description
        groupMetaImageUrl = meta.imageUrl
        groupMetaAppData = meta.appData
      }
    }
    .onFailure { error ->
      groupMetaError = error.message ?: "Failed to load group settings"
    }

  runCatching { chatService.getActiveGroupPermissionPolicySet() }
    .onSuccess { policy ->
      if (policy != null) {
        groupPermissionAddMembers = policy.addMemberPolicy
        groupPermissionMetadata = policy.updateGroupNamePolicy
      }
    }
    .onFailure { error ->
      groupPermissionError = error.message ?: "Failed to load group permissions"
    }

  return ChatGroupSettingsLoadResult(
    groupMetaName = groupMetaName,
    groupMetaDescription = groupMetaDescription,
    groupMetaImageUrl = groupMetaImageUrl,
    groupMetaAppData = groupMetaAppData,
    groupMetaError = groupMetaError,
    groupPermissionAddMembers = groupPermissionAddMembers,
    groupPermissionMetadata = groupPermissionMetadata,
    groupPermissionError = groupPermissionError,
  )
}

internal suspend fun saveActiveGroupMetadata(
  chatService: XmtpChatService,
  name: String,
  description: String,
  imageUrl: String,
  appData: String,
): Result<Unit> {
  return runCatching {
    chatService.updateActiveGroupMetadata(
      name = name,
      description = description,
      imageUrl = imageUrl,
      appData = appData,
    )
  }
}

internal suspend fun saveActiveGroupPermissions(
  chatService: XmtpChatService,
  addMemberPolicy: PermissionOption,
  metadataPolicy: PermissionOption,
): Result<Unit> {
  return runCatching {
    chatService.updateActiveGroupPermissions(
      addMemberPolicy = addMemberPolicy,
      removeMemberPolicy = addMemberPolicy,
      updateNamePolicy = metadataPolicy,
      updateDescriptionPolicy = metadataPolicy,
      updateImagePolicy = metadataPolicy,
    )
  }
}

internal suspend fun saveDisappearingSeconds(
  chatService: XmtpChatService,
  seconds: Long?,
): Result<Unit> {
  return runCatching {
    chatService.setActiveDisappearingSeconds(seconds)
  }
}
