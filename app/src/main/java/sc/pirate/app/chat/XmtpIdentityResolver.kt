package sc.pirate.app.chat

import android.content.Context
import android.util.Log
import sc.pirate.app.BuildConfig
import sc.pirate.app.resolveProfileIdentityWithRetry
import sc.pirate.app.profile.PirateNameRecordsApi
import kotlinx.coroutines.delay
import org.xmtp.android.library.Client
import org.xmtp.android.library.libxmtp.IdentityKind
import org.xmtp.android.library.libxmtp.PublicIdentity

private const val TAG = "XmtpIdentityResolver"

internal data class ResolvedPeerIdentity(
  val displayName: String,
  val avatarUri: String?,
)

internal class XmtpIdentityResolver(
  appContext: Context,
) {
  private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val peerAddressByInboxId = mutableMapOf<String, String>()
  private val peerIdentityByInboxId = mutableMapOf<String, ResolvedPeerIdentity>()
  private val peerIdentityByAddress = mutableMapOf<String, ResolvedPeerIdentity>()

  companion object {
    private const val PREFS_NAME = "xmtp_identity_resolver"
    private const val KEY_INBOX_ADDRESS = "inbox_address"
    private const val KEY_INBOX_NAME = "inbox_name"
    private const val KEY_INBOX_AVATAR = "inbox_avatar"
    private const val KEY_ADDR_NAME = "addr_name"
    private const val KEY_ADDR_AVATAR = "addr_avatar"
  }

  fun clearCaches() {
    debugLog("clearCaches")
    peerAddressByInboxId.clear()
    peerIdentityByInboxId.clear()
    peerIdentityByAddress.clear()
  }

  suspend fun resolveInboxId(
    client: Client,
    rawAddressOrInboxId: String,
  ): String {
    val trimmed = rawAddressOrInboxId.trim()
    require(trimmed.isNotBlank()) { "Missing address or inbox ID" }

    val normalizedAddress =
      when {
        trimmed.startsWith("0x", ignoreCase = true) -> normalizeEthAddress(trimmed)
        trimmed.length == 40 && trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ->
          normalizeEthAddress("0x$trimmed")
        else -> null
      }

    if (normalizedAddress != null) {
      debugLog("resolveInboxId input=${shortForLog(trimmed)} treatedAsAddress=${shortForLog(normalizedAddress)}")
      val byIdentity = client.inboxIdFromIdentity(PublicIdentity(IdentityKind.ETHEREUM, normalizedAddress))
      if (!byIdentity.isNullOrBlank()) {
        debugLog("resolveInboxId viaIdentity address=${shortForLog(normalizedAddress)} inbox=${shortForLog(byIdentity)}")
        return byIdentity
      }

      val byNameRecord = PirateNameRecordsApi.getXmtpInboxIdForAddress(normalizedAddress)
      if (!byNameRecord.isNullOrBlank()) {
        debugLog("resolveInboxId viaNameRecord address=${shortForLog(normalizedAddress)} inbox=${shortForLog(byNameRecord)}")
        return byNameRecord
      }

      warnLog("resolveInboxId failed address=${shortForLog(normalizedAddress)} noInboxId")
      throw IllegalStateException("No XMTP inboxId for address=$normalizedAddress")
    }

    val normalizedName = trimmed.lowercase().removePrefix("@")
    debugLog("resolveInboxId input=${shortForLog(trimmed)} treatedAsName=$normalizedName")
    val resolvedAddress = PirateNameRecordsApi.resolveAddressForName(trimmed)
    if (!resolvedAddress.isNullOrBlank()) {
      val normalizedResolved = normalizeEthAddress(resolvedAddress)
      val seededIdentity = cacheResolvedNameIdentity(normalizedResolved, normalizedName)
      val byIdentity = client.inboxIdFromIdentity(PublicIdentity(IdentityKind.ETHEREUM, normalizedResolved))
      if (!byIdentity.isNullOrBlank()) {
        debugLog("resolveInboxId name->address->identity name=$normalizedName address=${shortForLog(normalizedResolved)} inbox=${shortForLog(byIdentity)}")
        cacheInboxResolution(byIdentity, normalizedResolved, seededIdentity)
        return byIdentity
      }

      val byNameRecord = PirateNameRecordsApi.getXmtpInboxIdForName(trimmed)
      if (!byNameRecord.isNullOrBlank()) {
        debugLog("resolveInboxId name->address->nameRecord name=$normalizedName address=${shortForLog(normalizedResolved)} inbox=${shortForLog(byNameRecord)}")
        cacheInboxResolution(byNameRecord, normalizedResolved, seededIdentity)
        return byNameRecord
      }

      warnLog("resolveInboxId failed name=$normalizedName address=${shortForLog(normalizedResolved)} noInboxId")
      throw IllegalStateException("No XMTP inboxId for name=$trimmed")
    }

    warnLog("resolveInboxId passthrough input=${shortForLog(trimmed)} (not address/name)")
    return trimmed
  }

  suspend fun resolvePeerIdentityByInboxId(
    client: Client,
    inboxId: String,
  ): ResolvedPeerIdentity {
    peerIdentityByInboxId[inboxId]?.let {
      debugLog("resolvePeerIdentityByInboxId memoryHit inbox=${shortForLog(inboxId)} name=${it.displayName}")
      return it
    }
    loadPersistedIdentityForInbox(inboxId)?.let { persisted ->
      debugLog("resolvePeerIdentityByInboxId persistedHit inbox=${shortForLog(inboxId)} name=${persisted.displayName} avatar=${!persisted.avatarUri.isNullOrBlank()}")
      peerIdentityByInboxId[inboxId] = persisted
      return persisted
    }
    val address = resolvePeerAddress(client, inboxId)
    val identity = resolvePeerIdentity(address)
    debugLog(
      "resolvePeerIdentityByInboxId resolved inbox=${shortForLog(inboxId)} " +
        "address=${shortForLog(address)} name=${identity.displayName} avatar=${!identity.avatarUri.isNullOrBlank()}",
    )
    peerIdentityByInboxId[inboxId] = identity
    persistInboxIdentity(inboxId, identity)
    return identity
  }

  suspend fun resolvePeerAddress(
    client: Client,
    peerInboxId: String,
  ): String {
    peerAddressByInboxId[peerInboxId]?.let {
      debugLog("resolvePeerAddress memoryHit inbox=${shortForLog(peerInboxId)} address=${shortForLog(it)}")
      return it
    }
    loadPersistedAddressForInbox(peerInboxId)?.let { persisted ->
      debugLog("resolvePeerAddress persistedHit inbox=${shortForLog(peerInboxId)} address=${shortForLog(persisted)}")
      peerAddressByInboxId[peerInboxId] = persisted
      return persisted
    }
    return runCatching {
      val localState = client.inboxStatesForInboxIds(false, listOf(peerInboxId)).firstOrNull()
      val state = localState ?: client.inboxStatesForInboxIds(true, listOf(peerInboxId)).firstOrNull()
      val identity =
        state
          ?.identities
          ?.firstOrNull { it.kind == IdentityKind.ETHEREUM }
          ?.identifier
      val normalized = identity?.let(::normalizeEthAddressOrNull)
      if (normalized != null) {
        peerAddressByInboxId[peerInboxId] = normalized
        persistInboxAddress(peerInboxId, normalized)
        debugLog(
          "resolvePeerAddress networkResolved inbox=${shortForLog(peerInboxId)} address=${shortForLog(normalized)} " +
            "localState=${localState != null}",
        )
        normalized
      } else {
        warnLog(
          "resolvePeerAddress noEthIdentity inbox=${shortForLog(peerInboxId)} " +
            "localState=${localState != null} fallback=inboxId",
        )
        peerInboxId
      }
    }.getOrElse {
      Log.w(TAG, "resolvePeerAddress exception inbox=${shortForLog(peerInboxId)} fallback=inboxId", it)
      peerInboxId
    }
  }

  suspend fun resolvePeerIdentity(addressOrInboxId: String): ResolvedPeerIdentity {
    val normalized = normalizeEthAddressOrNull(addressOrInboxId)
      ?: return ResolvedPeerIdentity(displayName = addressOrInboxId, avatarUri = null)

    peerIdentityByAddress[normalized]?.let {
      debugLog("resolvePeerIdentity memoryHit address=${shortForLog(normalized)} name=${it.displayName}")
      return it
    }
    loadPersistedIdentityForAddress(normalized)?.let { persisted ->
      debugLog(
        "resolvePeerIdentity persistedHit address=${shortForLog(normalized)} " +
          "name=${persisted.displayName} avatar=${!persisted.avatarUri.isNullOrBlank()}",
      )
      peerIdentityByAddress[normalized] = persisted
      return persisted
    }

    val primaryName = resolvePrimaryNameWithRetry(normalized)
    if (!primaryName.isNullOrBlank()) {
      val node = PirateNameRecordsApi.computeNode(primaryName)
      val avatarUri =
        PirateNameRecordsApi.getTextRecord(node, "avatar")
          ?.trim()
          ?.takeIf { it.isNotBlank() }
      val fallbackAvatar =
        if (!avatarUri.isNullOrBlank()) null else {
          resolveProfileIdentityWithRetry(normalized, attempts = 1).second
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        }
      val resolved = ResolvedPeerIdentity(displayName = primaryName, avatarUri = avatarUri ?: fallbackAvatar)
      peerIdentityByAddress[normalized] = resolved
      persistAddressIdentity(normalized, resolved)
      debugLog(
        "resolvePeerIdentity primaryNameResolved address=${shortForLog(normalized)} " +
          "name=$primaryName avatarRecord=${!avatarUri.isNullOrBlank()} avatarFallback=${!fallbackAvatar.isNullOrBlank()}",
      )
      return resolved
    }

    val fallbackProfile = resolveProfileIdentityWithRetry(normalized, attempts = 2, retryDelayMs = 600)
    val fallbackAvatar = fallbackProfile.second?.trim()?.takeIf { it.isNotBlank() }
    val fallback = ResolvedPeerIdentity(displayName = normalized, avatarUri = fallbackAvatar)
    peerIdentityByAddress[normalized] = fallback
    if (!fallbackAvatar.isNullOrBlank()) {
      persistAddressIdentity(normalized, fallback)
    }
    warnLog(
      "resolvePeerIdentity fallback address=${shortForLog(normalized)} " +
        "fallbackName=false fallbackAvatar=${!fallbackAvatar.isNullOrBlank()}",
    )
    return fallback
  }

  private suspend fun cacheResolvedNameIdentity(
    normalizedAddress: String,
    normalizedName: String,
  ): ResolvedPeerIdentity {
    val fallbackProfile = resolveProfileIdentityWithRetry(normalizedAddress, attempts = 1)
    val avatarUri =
      runCatching {
        if (normalizedName.isBlank()) null else {
          val node = PirateNameRecordsApi.computeNode(normalizedName)
          PirateNameRecordsApi.getTextRecord(node, "avatar")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        }
      }.getOrNull() ?: fallbackProfile.second?.trim()?.takeIf { it.isNotBlank() }
    val fallbackName = fallbackProfile.first?.trim()?.takeIf { it.isNotBlank() }
    val identity =
      ResolvedPeerIdentity(
        displayName = normalizedName.ifBlank { fallbackName ?: normalizedAddress },
        avatarUri = avatarUri,
      )
    peerIdentityByAddress[normalizedAddress] = identity
    persistAddressIdentity(normalizedAddress, identity)
    debugLog(
      "cacheResolvedNameIdentity address=${shortForLog(normalizedAddress)} name=${identity.displayName} " +
        "avatar=${!identity.avatarUri.isNullOrBlank()}",
    )
    return identity
  }

  private fun cacheInboxResolution(
    inboxId: String,
    normalizedAddress: String,
    identity: ResolvedPeerIdentity,
  ) {
    peerAddressByInboxId[inboxId] = normalizedAddress
    peerIdentityByInboxId[inboxId] = identity
    persistInboxAddress(inboxId, normalizedAddress)
    persistInboxIdentity(inboxId, identity)
    persistAddressIdentity(normalizedAddress, identity)
  }

  private fun normalizeKey(value: String): String = value.trim().lowercase()

  private fun loadPersistedAddressForInbox(inboxId: String): String? {
    val key = "$KEY_INBOX_ADDRESS:${normalizeKey(inboxId)}"
    return prefs.getString(key, null)?.trim()?.takeIf { it.isNotBlank() }
  }

  private fun persistInboxAddress(inboxId: String, address: String) {
    val key = "$KEY_INBOX_ADDRESS:${normalizeKey(inboxId)}"
    debugLog("persistInboxAddress inbox=${shortForLog(inboxId)} address=${shortForLog(address)}")
    prefs.edit().putString(key, normalizeKey(address)).apply()
  }

  private fun loadPersistedIdentityForAddress(address: String): ResolvedPeerIdentity? {
    val normalizedAddress = normalizeKey(address)
    val nameKey = "$KEY_ADDR_NAME:$normalizedAddress"
    val avatarKey = "$KEY_ADDR_AVATAR:$normalizedAddress"
    val persistedName = prefs.getString(nameKey, null)?.trim().orEmpty()
    val persistedAvatar = prefs.getString(avatarKey, null)?.trim().orEmpty()
    if (persistedName.isBlank() && persistedAvatar.isBlank()) return null
    return ResolvedPeerIdentity(
      displayName = persistedName.ifBlank { normalizedAddress },
      avatarUri = persistedAvatar.ifBlank { null },
    )
  }

  private fun persistAddressIdentity(address: String, identity: ResolvedPeerIdentity) {
    val normalizedAddress = normalizeKey(address)
    val nameKey = "$KEY_ADDR_NAME:$normalizedAddress"
    val avatarKey = "$KEY_ADDR_AVATAR:$normalizedAddress"
    val editor = prefs.edit()
    val name = identity.displayName.trim()
    val avatar = identity.avatarUri?.trim().orEmpty()
    if (name.isNotBlank() && !name.equals(normalizedAddress, ignoreCase = true)) {
      editor.putString(nameKey, name)
    }
    if (avatar.isNotBlank()) {
      editor.putString(avatarKey, avatar)
    }
    editor.apply()
    debugLog(
      "persistAddressIdentity address=${shortForLog(normalizedAddress)} " +
        "nameSaved=${name.isNotBlank() && !name.equals(normalizedAddress, ignoreCase = true)} avatarSaved=${avatar.isNotBlank()}",
    )
  }

  private fun loadPersistedIdentityForInbox(inboxId: String): ResolvedPeerIdentity? {
    val normalizedInbox = normalizeKey(inboxId)
    val nameKey = "$KEY_INBOX_NAME:$normalizedInbox"
    val avatarKey = "$KEY_INBOX_AVATAR:$normalizedInbox"
    val persistedName = prefs.getString(nameKey, null)?.trim().orEmpty()
    val persistedAvatar = prefs.getString(avatarKey, null)?.trim().orEmpty()
    if (persistedName.isBlank() && persistedAvatar.isBlank()) return null
    return ResolvedPeerIdentity(
      displayName = persistedName.ifBlank { inboxId },
      avatarUri = persistedAvatar.ifBlank { null },
    )
  }

  private fun persistInboxIdentity(inboxId: String, identity: ResolvedPeerIdentity) {
    val normalizedInbox = normalizeKey(inboxId)
    val nameKey = "$KEY_INBOX_NAME:$normalizedInbox"
    val avatarKey = "$KEY_INBOX_AVATAR:$normalizedInbox"
    val name = identity.displayName.trim()
    val avatar = identity.avatarUri?.trim().orEmpty()
    val editor = prefs.edit()
    if (name.isNotBlank() && !looksLikeOpaqueIdentity(name) && !name.equals(normalizedInbox, ignoreCase = true)) {
      editor.putString(nameKey, name)
    }
    if (avatar.isNotBlank()) {
      editor.putString(avatarKey, avatar)
    }
    editor.apply()
    debugLog(
      "persistInboxIdentity inbox=${shortForLog(normalizedInbox)} " +
        "nameSaved=${name.isNotBlank() && !looksLikeOpaqueIdentity(name) && !name.equals(normalizedInbox, ignoreCase = true)} " +
        "avatarSaved=${avatar.isNotBlank()}",
    )
  }

  private fun looksLikeOpaqueIdentity(value: String): Boolean {
    val normalized = value.trim()
    if (normalized.isBlank()) return true
    if (normalizeEthAddressOrNull(normalized) != null) return true
    val withoutPrefix = normalized.removePrefix("0x")
    if (withoutPrefix.length >= 24 &&
      withoutPrefix.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    ) {
      return true
    }
    return false
  }

  private suspend fun resolvePrimaryNameWithRetry(
    address: String,
    attempts: Int = 3,
    retryDelayMs: Long = 350,
  ): String? {
    var last: String? = null
    repeat(attempts.coerceAtLeast(1)) { attempt ->
      val resolved =
        runCatching { PirateNameRecordsApi.getPrimaryName(address) }
          .getOrNull()
          ?.trim()
          ?.takeIf { it.isNotBlank() }
      if (!resolved.isNullOrBlank()) return resolved
      last = resolved
      if (attempt < attempts - 1) delay(retryDelayMs)
    }
    return last
  }

  private fun shortForLog(value: String?, head: Int = 8, tail: Int = 4): String {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return "(blank)"
    if (raw.length <= head + tail + 1) return raw
    return "${raw.take(head)}...${raw.takeLast(tail)}"
  }

  private fun debugLog(message: String) {
    if (BuildConfig.DEBUG) {
      Log.d(TAG, message)
    }
  }

  private fun warnLog(message: String) {
    Log.w(TAG, message)
  }
}
