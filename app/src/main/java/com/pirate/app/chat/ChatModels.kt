package com.pirate.app.chat

enum class ConversationType {
  DM,
  GROUP,
}

enum class GroupPermissionMode {
  ALL_MEMBERS,
  ADMIN_ONLY,
  CUSTOM,
}

data class ConversationItem(
  val id: String,
  val type: ConversationType,
  val displayName: String,
  val avatarUri: String?,
  val lastMessage: String,
  val lastMessageTimestampMs: Long,
  val subtitle: String? = null,
  val peerAddress: String? = null,
  val peerInboxId: String? = null,
  val groupDescription: String? = null,
  val groupImageUrl: String? = null,
  val groupAppData: String? = null,
  val unreadCount: Int = 0,
)

data class GroupMetadata(
  val name: String,
  val description: String,
  val imageUrl: String,
  val appData: String,
)

data class ChatMessage(
  val id: String,
  val senderAddress: String,
  val senderInboxId: String,
  val senderDisplayName: String? = null,
  val senderAvatarUri: String? = null,
  val text: String,
  val timestampMs: Long,
  val isFromMe: Boolean,
)
