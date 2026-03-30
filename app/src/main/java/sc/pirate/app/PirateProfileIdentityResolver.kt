package sc.pirate.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sc.pirate.app.profile.HeavenNamesApi

private const val TAG = "ProfileIdentity"
private const val IDENTITY_CACHE_TTL_MS = 3 * 60 * 1000L

private data class CachedIdentity(
  val name: String?,
  val avatar: String?,
  val cachedAtMs: Long,
)

private val identityCache = LinkedHashMap<String, CachedIdentity>()

internal suspend fun resolveProfileIdentity(
  address: String,
  forceRefresh: Boolean = false,
): Pair<String?, String?> = withContext(Dispatchers.IO) {
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
  val publicProfile = runCatching { fetchPublicProfileReadModel(address, forceRefresh = forceRefresh) }
    .onFailure { err -> Log.w(TAG, "loadPublicProfile failed: ${err.message}") }
    .getOrNull()
  val publicPrimaryName = publicProfile?.records?.primaryName?.trim()?.ifBlank { null }
  val publicAvatar = publicProfile?.avatarRef?.trim()?.ifBlank { null }

  if (!publicPrimaryName.isNullOrBlank() || !publicAvatar.isNullOrBlank()) {
    synchronized(identityCache) {
      identityCache[normalizedAddress] =
        CachedIdentity(name = publicPrimaryName, avatar = publicAvatar, cachedAtMs = System.currentTimeMillis())
    }
    Log.i(TAG, "resolveProfileIdentity: publicProfile result name=$publicPrimaryName avatar=$publicAvatar")
    return@withContext publicPrimaryName to publicAvatar
  }

  val heavenPrimaryName = runCatching { HeavenNamesApi.reverse(address) }.getOrNull()
  if (heavenPrimaryName != null) {
    val contractAvatar = publicProfile?.contractProfile?.photoUri?.trim()?.ifBlank { null }
    synchronized(identityCache) {
      identityCache[normalizedAddress] =
        CachedIdentity(name = heavenPrimaryName.fullName, avatar = contractAvatar, cachedAtMs = System.currentTimeMillis())
    }
    Log.i(
      TAG,
      "resolveProfileIdentity: heaven result name=${heavenPrimaryName.fullName} avatar=$contractAvatar",
    )
    return@withContext heavenPrimaryName.fullName to contractAvatar
  }

  val fallbackName = publicProfile?.displayName?.trim()?.ifBlank { null }
  val fallbackAvatar = publicProfile?.contractProfile?.photoUri?.trim()?.ifBlank { null }
  synchronized(identityCache) {
    identityCache[normalizedAddress] =
      CachedIdentity(name = fallbackName, avatar = fallbackAvatar, cachedAtMs = System.currentTimeMillis())
  }
  Log.i(
    TAG,
    "resolveProfileIdentity: fallback result name=$fallbackName avatar=$fallbackAvatar",
  )
  return@withContext fallbackName to fallbackAvatar
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
