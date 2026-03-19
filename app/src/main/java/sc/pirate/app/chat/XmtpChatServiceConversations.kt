package sc.pirate.app.chat

import android.util.Log
import org.xmtp.android.library.Client
import org.xmtp.android.library.Dm
import org.xmtp.android.library.Group

internal suspend fun toDmConversationItem(
  client: Client,
  dm: Dm,
  identityResolver: XmtpIdentityResolver,
  tag: String,
): ConversationItem? {
  return try {
    val last = dm.lastMessage()
    val peerInboxId = dm.peerInboxId
    val peerAddress = identityResolver.resolvePeerAddress(client, peerInboxId)
    val peerIdentity = identityResolver.resolvePeerIdentityByInboxId(client, peerInboxId)
    val displayName = peerIdentity.displayName.ifBlank { peerAddress }
    val hasResolvedName = !displayName.equals(peerAddress, ignoreCase = true)
    ConversationItem(
      id = dm.id,
      type = ConversationType.DM,
      displayName = displayName,
      avatarUri = peerIdentity.avatarUri,
      lastMessage = last?.let { sanitizeXmtpBody(it) } ?: "",
      lastMessageTimestampMs = last?.sentAtNs?.div(1_000_000) ?: 0L,
      subtitle = if (hasResolvedName) peerAddress else peerInboxId,
      peerAddress = peerAddress,
      peerInboxId = peerInboxId,
    )
  } catch (error: Exception) {
    Log.w(tag, "Failed to read DM conversation", error)
    null
  }
}

internal suspend fun toGroupConversationItem(
  group: Group,
  tag: String,
): ConversationItem? {
  return try {
    val last = group.lastMessage()
    val name = group.name().trim().ifBlank { "Untitled group" }
    val description = group.description().trim()
    val imageUrl = group.imageUrl().trim()
    val appData = group.appData().trim()
    val memberCount = runCatching { group.members().size }.getOrNull()
    val subtitle =
      when {
        description.isNotBlank() -> description
        memberCount != null -> "$memberCount member${if (memberCount == 1) "" else "s"}"
        else -> "Group chat"
      }
    ConversationItem(
      id = group.id,
      type = ConversationType.GROUP,
      displayName = name,
      avatarUri = imageUrl.ifBlank { null },
      lastMessage = last?.let { sanitizeXmtpBody(it) } ?: "",
      lastMessageTimestampMs = last?.sentAtNs?.div(1_000_000) ?: 0L,
      subtitle = subtitle,
      groupDescription = description.ifBlank { null },
      groupImageUrl = imageUrl.ifBlank { null },
      groupAppData = appData.ifBlank { null },
    )
  } catch (error: Exception) {
    Log.w(tag, "Failed to read group conversation", error)
    null
  }
}

internal suspend fun activeGroupForConversation(
  client: Client?,
  activeConversationId: String?,
): Group? {
  val safeClient = client ?: return null
  val activeId = activeConversationId ?: return null
  return runCatching { safeClient.conversations.findGroup(activeId) }.getOrNull()
}
