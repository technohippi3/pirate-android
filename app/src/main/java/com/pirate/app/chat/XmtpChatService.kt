package com.pirate.app.chat

import android.content.Context
import android.util.Log
import com.pirate.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmtp.android.library.Client
import org.xmtp.android.library.ClientOptions
import org.xmtp.android.library.Conversation
import org.xmtp.android.library.ConsentState
import org.xmtp.android.library.Group
import org.xmtp.android.library.XMTPEnvironment
import org.xmtp.android.library.libxmtp.DisappearingMessageSettings
import org.xmtp.android.library.libxmtp.GroupPermissionPreconfiguration
import org.xmtp.android.library.libxmtp.PermissionOption
import org.xmtp.android.library.libxmtp.PermissionPolicySet

class XmtpChatService(private val appContext: Context) {

  companion object {
    private const val TAG = "XmtpChatService"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val identityResolver = XmtpIdentityResolver(appContext)

  private var client: Client? = null

  private val _connected = MutableStateFlow(false)
  val connected: StateFlow<Boolean> = _connected

  private val _conversations = MutableStateFlow<List<ConversationItem>>(emptyList())
  val conversations: StateFlow<List<ConversationItem>> = _conversations

  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages: StateFlow<List<ChatMessage>> = _messages

  private val _activeConversationId = MutableStateFlow<String?>(null)
  val activeConversationId: StateFlow<String?> = _activeConversationId

  private var activeConversation: Conversation? = null

  /**
   * Connect to XMTP using a local secp256k1 identity key tied to the user's address.
   * The identity key is generated once and persisted per-address.
   */
  suspend fun connect(address: String) {
    if (client != null) return

    try {
      withContext(Dispatchers.IO) {
      val normalizedAddress = normalizeEthAddress(address)
      val signer = getOrCreateLocalSigner(appContext, normalizedAddress)

      // If the signing identity changed (e.g. migrated from a previous key to local key),
      // the old XMTP DB may be incompatible. Regenerate the DB key per-identity.
      val dbKey = getOrCreateXmtpDbKey(appContext, signer.publicIdentity.identifier)

      val options = ClientOptions(
        api = ClientOptions.Api(
          env = if (BuildConfig.DEBUG) XMTPEnvironment.DEV else XMTPEnvironment.PRODUCTION,
          isSecure = true,
        ),
        appContext = appContext,
        dbEncryptionKey = dbKey,
      )

      client = createXmtpClientWithDbRecovery(appContext, signer, options)
      _connected.value = true
      Log.i(TAG, "XMTP connected for $normalizedAddress")

      refreshConversations()
      startMessageStream()
      } // withContext
    } catch (e: Exception) {
      Log.e(TAG, "XMTP connect failed", e)
      throw e
    }
  }

  fun disconnect() {
    client = null
    _connected.value = false
    _conversations.value = emptyList()
    _messages.value = emptyList()
    _activeConversationId.value = null
    activeConversation = null
    identityResolver.clearCaches()
  }

  fun currentInboxId(): String? = client?.inboxId

  suspend fun refreshConversations() {
    val c = client ?: return
    try {
      c.conversations.syncAllConversations()
      val dms = c.conversations.listDms()
      val groups = c.conversations.listGroups()
      val dmItems = dms.mapNotNull { dm -> toDmConversationItem(c, dm, identityResolver, TAG) }
      val groupItems = groups.mapNotNull { group -> toGroupConversationItem(group, TAG) }
      _conversations.value = (dmItems + groupItems).sortedByDescending { it.lastMessageTimestampMs }
    } catch (e: Exception) {
      Log.e(TAG, "refreshConversations failed", e)
    }
  }

  suspend fun openConversation(conversationId: String) {
    val c = client ?: return
    _activeConversationId.value = conversationId
    try {
      val conversation = c.conversations.findConversation(conversationId) ?: return
      activeConversation = conversation
      conversation.sync()
      loadMessages(conversation)
    } catch (e: Exception) {
      Log.e(TAG, "openConversation failed", e)
    }
  }

  fun closeConversation() {
    _activeConversationId.value = null
    activeConversation = null
    _messages.value = emptyList()
  }

  suspend fun activeDisappearingSeconds(): Long? {
    val conversation = activeConversation ?: return null
    val settings = runCatching { conversation.disappearingMessageSettings() }.getOrNull() ?: return null
    val seconds = settings.retentionDurationInNs / 1_000_000_000L
    return seconds.takeIf { it > 0L }
  }

  suspend fun setActiveDisappearingSeconds(retentionSeconds: Long?) {
    val conversation = activeConversation ?: return
    try {
      if (retentionSeconds == null || retentionSeconds <= 0L) {
        conversation.clearDisappearingMessageSettings()
      } else {
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val retentionNs = retentionSeconds * 1_000_000_000L
        conversation.updateDisappearingMessageSettings(DisappearingMessageSettings(nowNs, retentionNs))
      }
      conversation.sync()
      loadMessages(conversation)
      refreshConversations()
    } catch (e: Exception) {
      Log.e(TAG, "updateDisappearingMessageSettings failed", e)
      throw e
    }
  }

  suspend fun sendMessage(text: String) {
    val conversation = activeConversation ?: return
    try {
      conversation.send(text)
      conversation.sync()
      loadMessages(conversation)
      refreshConversations()
    } catch (e: Exception) {
      Log.e(TAG, "sendMessage failed", e)
      throw e
    }
  }

  suspend fun newDm(peerAddressOrInboxId: String): String {
    val c = client ?: throw IllegalStateException("XMTP is not connected")
    return try {
      val inboxId = identityResolver.resolveInboxId(c, peerAddressOrInboxId)
      val dm = c.conversations.findOrCreateDm(inboxId)
      refreshConversations()
      dm.id
    } catch (e: Exception) {
      Log.e(TAG, "newDm failed", e)
      throw e
    }
  }

  suspend fun newGroup(
    memberAddressesOrInboxIds: List<String>,
    name: String?,
    description: String?,
    imageUrl: String?,
    appData: String?,
    permissionMode: GroupPermissionMode,
    customPermissions: PermissionPolicySet? = null,
  ): String {
    val c = client ?: throw IllegalStateException("XMTP is not connected")
    return try {
      val memberInboxIds = mutableListOf<String>()
      for (raw in memberAddressesOrInboxIds) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) continue
        val inboxId = identityResolver.resolveInboxId(c, trimmed)
        if (inboxId == c.inboxId) continue
        if (!memberInboxIds.contains(inboxId)) {
          memberInboxIds.add(inboxId)
        }
      }
      require(memberInboxIds.isNotEmpty()) { "Add at least one valid member" }

      val normalizedName = name?.trim().orEmpty()
      val normalizedDescription = description?.trim().orEmpty()
      val normalizedImageUrl = imageUrl?.trim().orEmpty()
      val normalizedAppData = appData?.trim().orEmpty()

      val group =
        when (permissionMode) {
          GroupPermissionMode.ALL_MEMBERS ->
            c.conversations.newGroup(
              inboxIds = memberInboxIds,
              permissions = GroupPermissionPreconfiguration.ALL_MEMBERS,
              groupName = normalizedName,
              groupImageUrlSquare = normalizedImageUrl,
              groupDescription = normalizedDescription,
              appData = normalizedAppData,
            )
          GroupPermissionMode.ADMIN_ONLY ->
            c.conversations.newGroup(
              inboxIds = memberInboxIds,
              permissions = GroupPermissionPreconfiguration.ADMIN_ONLY,
              groupName = normalizedName,
              groupImageUrlSquare = normalizedImageUrl,
              groupDescription = normalizedDescription,
              appData = normalizedAppData,
            )
          GroupPermissionMode.CUSTOM ->
            c.conversations.newGroupCustomPermissions(
              inboxIds = memberInboxIds,
              permissionPolicySet =
                customPermissions
                  ?: throw IllegalArgumentException("Custom permissions are required"),
              groupName = normalizedName,
              groupImageUrlSquare = normalizedImageUrl,
              groupDescription = normalizedDescription,
              appData = normalizedAppData,
            )
        }

      refreshConversations()
      group.id
    } catch (e: Exception) {
      Log.e(TAG, "newGroup failed", e)
      throw e
    }
  }

  suspend fun getActiveGroupMetadata(): GroupMetadata? {
    val group = activeGroup() ?: return null
    val name = runCatching { group.name() }.getOrDefault("")
    val description = runCatching { group.description() }.getOrDefault("")
    val imageUrl = runCatching { group.imageUrl() }.getOrDefault("")
    val appData = runCatching { group.appData() }.getOrDefault("")
    return GroupMetadata(
      name = name,
      description = description,
      imageUrl = imageUrl,
      appData = appData,
    )
  }

  suspend fun updateActiveGroupMetadata(
    name: String?,
    description: String?,
    imageUrl: String?,
    appData: String?,
  ) {
    val group = activeGroup() ?: throw IllegalStateException("Active conversation is not a group")
    try {
      val currentName = runCatching { group.name() }.getOrDefault("")
      val currentDescription = runCatching { group.description() }.getOrDefault("")
      val currentImageUrl = runCatching { group.imageUrl() }.getOrDefault("")
      val currentAppData = runCatching { group.appData() }.getOrDefault("")
      if (name != null && name != currentName) group.updateName(name)
      if (description != null && description != currentDescription) group.updateDescription(description)
      if (imageUrl != null && imageUrl != currentImageUrl) group.updateImageUrl(imageUrl)
      if (appData != null && appData != currentAppData) group.updateAppData(appData)
      group.sync()
      refreshConversations()
      val conversation = activeConversation
      if (conversation != null) {
        conversation.sync()
        loadMessages(conversation)
      }
    } catch (e: Exception) {
      Log.e(TAG, "updateActiveGroupMetadata failed", e)
      throw e
    }
  }

  suspend fun getActiveGroupPermissionPolicySet(): PermissionPolicySet? {
    val group = activeGroup() ?: return null
    return runCatching { group.permissionPolicySet() }.getOrNull()
  }

  suspend fun updateActiveGroupPermissions(
    addMemberPolicy: PermissionOption? = null,
    removeMemberPolicy: PermissionOption? = null,
    updateNamePolicy: PermissionOption? = null,
    updateDescriptionPolicy: PermissionOption? = null,
    updateImagePolicy: PermissionOption? = null,
  ) {
    val group = activeGroup() ?: throw IllegalStateException("Active conversation is not a group")
    try {
      if (addMemberPolicy != null) group.updateAddMemberPermission(addMemberPolicy)
      if (removeMemberPolicy != null) group.updateRemoveMemberPermission(removeMemberPolicy)
      if (updateNamePolicy != null) group.updateNamePermission(updateNamePolicy)
      if (updateDescriptionPolicy != null) group.updateDescriptionPermission(updateDescriptionPolicy)
      if (updateImagePolicy != null) group.updateImageUrlPermission(updateImagePolicy)
      group.sync()
      refreshConversations()
    } catch (e: Exception) {
      Log.e(TAG, "updateActiveGroupPermissions failed", e)
      throw e
    }
  }

  private suspend fun loadMessages(conversation: Conversation) {
    try {
      val c = client ?: return
      val myInboxId = c.inboxId
      val msgs = conversation.messages(limit = 100)
      _messages.value = msgs.mapNotNull { msg ->
        val senderInboxId = msg.senderInboxId
        val isFromMe = senderInboxId == myInboxId
        val senderAddress = if (isFromMe) senderInboxId else identityResolver.resolvePeerAddress(c, senderInboxId)
        val senderIdentity = if (isFromMe) null else identityResolver.resolvePeerIdentityByInboxId(c, senderInboxId)
        val text = sanitizeXmtpBody(msg)
        if (text.isBlank()) return@mapNotNull null
        ChatMessage(
          id = msg.id,
          senderAddress = senderAddress,
          senderInboxId = senderInboxId,
          senderDisplayName = senderIdentity?.displayName,
          senderAvatarUri = senderIdentity?.avatarUri,
          text = text,
          timestampMs = msg.sentAtNs / 1_000_000,
          isFromMe = isFromMe,
        )
      }.sortedBy { it.timestampMs }
    } catch (e: Exception) {
      Log.e(TAG, "loadMessages failed", e)
    }
  }

  private fun startMessageStream() {
    scope.launch {
      try {
        client?.conversations?.streamAllMessages(
          consentStates = listOf(ConsentState.ALLOWED),
        )?.collect { _ ->
          refreshConversations()
          val conversation = activeConversation
          if (conversation != null) {
            conversation.sync()
            loadMessages(conversation)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Message stream failed", e)
      }
    }
  }

  private suspend fun activeGroup(): Group? {
    return activeGroupForConversation(client = client, activeConversationId = _activeConversationId.value)
  }
}
