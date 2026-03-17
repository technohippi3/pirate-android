package com.pirate.app

import android.util.Log
import com.pirate.app.profile.ProfileContractApi
import com.pirate.app.profile.TempoNameRecordsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "ProfileIdentity"
private const val IDENTITY_CACHE_TTL_MS = 3 * 60 * 1000L
private data class CachedIdentity(
  val name: String?,
  val avatar: String?,
  val cachedAtMs: Long,
)

private val identityCache = LinkedHashMap<String, CachedIdentity>()

internal suspend fun resolveProfileIdentity(address: String, forceRefresh: Boolean = false): Pair<String?, String?> = withContext(Dispatchers.IO) {
  val normalizedAddress = address.trim().lowercase()
  if (normalizedAddress.isBlank()) return@withContext null to null

  if (!forceRefresh) {
    val cached = synchronized(identityCache) { identityCache[normalizedAddress] }
    val now = System.currentTimeMillis()
    if (cached != null && now - cached.cachedAtMs <= IDENTITY_CACHE_TTL_MS) {
      Log.d(TAG, "resolveProfileIdentity: cache hit address=${normalizedAddress.take(10)}")
      return@withContext cached.name to cached.avatar
    }
  } else {
    synchronized(identityCache) { identityCache.remove(normalizedAddress) }
  }

  Log.d(TAG, "resolveProfileIdentity: address=${address.take(10)}")
  var contractProfileLoaded = false
  var cachedContractProfile: com.pirate.app.profile.ContractProfileData? = null

  suspend fun loadContractProfile(): com.pirate.app.profile.ContractProfileData? {
    if (contractProfileLoaded) return cachedContractProfile
    contractProfileLoaded = true
    cachedContractProfile = runCatching {
      ProfileContractApi.fetchProfile(address)
    }.getOrElse { err ->
      Log.w(TAG, "loadContractProfile failed: ${err.message}")
      null
    }
    Log.d(
      TAG,
      "loadContractProfile: displayName=${cachedContractProfile?.displayName} photoUri=${cachedContractProfile?.photoUri}",
    )
    return cachedContractProfile
  }

  val tempoName = TempoNameRecordsApi.getPrimaryName(address)
  Log.d(TAG, "resolveProfileIdentity: tempoName=$tempoName")
  if (!tempoName.isNullOrBlank()) {
    val node = TempoNameRecordsApi.computeNode(tempoName)
    val tempoAvatar = TempoNameRecordsApi.getTextRecord(node, "avatar")
    Log.d(TAG, "resolveProfileIdentity: tempoAvatar=$tempoAvatar")
    val profile = loadContractProfile()
    val contractAvatar = profile?.photoUri?.trim()?.ifBlank { null }
    val avatar = tempoAvatar ?: contractAvatar
    synchronized(identityCache) {
      identityCache[normalizedAddress] = CachedIdentity(name = tempoName, avatar = avatar, cachedAtMs = System.currentTimeMillis())
    }
    Log.i(TAG, "resolveProfileIdentity: result name=$tempoName avatar=$avatar")
    return@withContext tempoName to avatar
  }

  val profile = loadContractProfile()
  val fallbackName = profile?.displayName?.trim()?.ifBlank { null }
  val fallbackContractAvatar = profile?.photoUri?.trim()?.ifBlank { null }
  synchronized(identityCache) {
    identityCache[normalizedAddress] =
      CachedIdentity(name = fallbackName, avatar = fallbackContractAvatar, cachedAtMs = System.currentTimeMillis())
  }
  Log.i(
    TAG,
    "resolveProfileIdentity: no tempo name found, contractName=$fallbackName contractAvatar=$fallbackContractAvatar",
  )
  return@withContext fallbackName to fallbackContractAvatar
}

internal suspend fun resolveProfileIdentityWithRetry(
  address: String,
  attempts: Int = 6,
  retryDelayMs: Long = 1_500,
  forceRefresh: Boolean = false,
): Pair<String?, String?> {
  val totalAttempts = attempts.coerceAtLeast(1)
  var last: Pair<String?, String?> = null to null
  repeat(totalAttempts) { attempt ->
    last = resolveProfileIdentity(address, forceRefresh = forceRefresh && attempt == 0)
    val name = last.first
    val avatar = last.second
    if (!name.isNullOrBlank() || !avatar.isNullOrBlank() || attempt == totalAttempts - 1) {
      return last
    }
    delay(retryDelayMs)
  }
  return last
}
