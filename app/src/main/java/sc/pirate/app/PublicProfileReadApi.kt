package sc.pirate.app

import android.util.Log
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.profile.ContractProfileData
import sc.pirate.app.profile.ProfileLanguageEntry
import sc.pirate.app.util.HttpClients
import sc.pirate.app.util.shortAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val PUBLIC_PROFILE_LOG_TAG = "PublicProfileReadApi"
private const val PUBLIC_PROFILE_CACHE_TTL_MS = 30_000L
private const val PUBLIC_PROFILE_CACHE_MAX_ENTRIES = 256
private val PUBLIC_PROFILE_ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")

private data class CachedPublicProfile(
  val value: PublicProfileReadModel?,
  val cachedAtMs: Long,
)

internal data class PublicProfileRecordData(
  val primaryName: String?,
  val avatarRef: String?,
  val coverRef: String?,
  val location: String?,
  val school: String?,
)

internal data class PublicProfileReadModel(
  val exists: Boolean,
  val walletAddress: String,
  val handle: String?,
  val displayName: String?,
  val avatarRef: String?,
  val contractProfile: ContractProfileData?,
  val records: PublicProfileRecordData,
)

private val publicProfileCacheLock = Any()
private val publicProfileCache = LinkedHashMap<String, CachedPublicProfile>(16, 0.75f, true)
private val publicProfileInFlight = LinkedHashMap<String, CompletableDeferred<PublicProfileReadModel?>>()

internal suspend fun fetchPublicProfileReadModel(
  address: String,
  forceRefresh: Boolean = false,
): PublicProfileReadModel? = withContext(Dispatchers.IO) {
  val normalizedAddress = normalizePublicProfileAddress(address) ?: return@withContext null

  if (forceRefresh) {
    synchronized(publicProfileCacheLock) { publicProfileCache.remove(normalizedAddress) }
  } else {
    val cached = synchronized(publicProfileCacheLock) { publicProfileCache[normalizedAddress] }
    if (cached != null && isFreshCachedProfile(cached)) {
      return@withContext cached.value
    }
  }

  val apiBaseUrl = normalizedApiBaseUrl() ?: return@withContext null
  val request =
    Request.Builder()
      .url("$apiBaseUrl/api/music/profiles/${encodeUrlComponent(normalizedAddress)}/public")
      .get()
      .header("Accept", "application/json")
      .build()

  val (requestDeferred, shouldFetch) =
    synchronized(publicProfileCacheLock) {
      val cached = publicProfileCache[normalizedAddress]
      if (!forceRefresh && cached != null && isFreshCachedProfile(cached)) {
        return@withContext cached.value
      }
      val existingRequest = publicProfileInFlight[normalizedAddress]
      if (existingRequest != null) {
        existingRequest to false
      } else {
        val created = CompletableDeferred<PublicProfileReadModel?>()
        publicProfileInFlight[normalizedAddress] = created
        created to true
      }
    }

  if (!shouldFetch) {
    return@withContext requestDeferred.await()
  }

  try {
    val resolved =
      HttpClients.Api.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          if (response.code == 404) return@use null
          throw IllegalStateException("Public profile lookup failed: HTTP ${response.code}")
        }
        val body = response.body?.string().orEmpty()
        val payload = runCatching { JSONObject(body) }.getOrNull() ?: return@use null
        parsePublicProfileReadModel(payload.optJSONObject("profile"))
      }

    synchronized(publicProfileCacheLock) {
      publicProfileCache[normalizedAddress] = CachedPublicProfile(value = resolved, cachedAtMs = System.currentTimeMillis())
      trimPublicProfileCacheLocked()
    }
    requestDeferred.complete(resolved)
    return@withContext resolved
  } catch (error: Throwable) {
    requestDeferred.completeExceptionally(error)
    throw error
  } finally {
    synchronized(publicProfileCacheLock) {
      if (publicProfileInFlight[normalizedAddress] === requestDeferred) {
        publicProfileInFlight.remove(normalizedAddress)
      }
    }
  }
}

internal suspend fun resolvePublicProfileIdentity(
  address: String,
  forceRefresh: Boolean = false,
): Pair<String?, String?> {
  val normalizedAddress = normalizePublicProfileAddress(address) ?: return null to null
  val publicProfile = runCatching { fetchPublicProfileReadModel(normalizedAddress, forceRefresh = forceRefresh) }
    .onFailure { error ->
      Log.w(PUBLIC_PROFILE_LOG_TAG, "resolvePublicProfileIdentity failed for ${normalizedAddress.take(10)}", error)
    }
    .getOrNull()
    ?: return null to null

  val fallbackLabel = shortAddress(normalizedAddress, minLengthToShorten = 14)
  val handle = publicProfile.handle?.trim().ifNullOrBlank()
  val displayName = publicProfile.displayName?.trim().ifNullOrBlank()
  val label =
    when {
      handle != null && !handle.equals(fallbackLabel, ignoreCase = true) -> handle
      displayName != null && !displayName.equals(fallbackLabel, ignoreCase = true) -> displayName
      handle != null -> handle
      else -> displayName
    }
  return label to publicProfile.avatarRef
}

internal suspend fun resolvePublicProfileIdentityWithRetry(
  address: String,
  attempts: Int = 3,
  retryDelayMs: Long = 1_000,
  forceRefresh: Boolean = false,
): Pair<String?, String?> {
  val totalAttempts = attempts.coerceAtLeast(1)
  var last: Pair<String?, String?> = null to null
  repeat(totalAttempts) { attempt ->
    last = resolvePublicProfileIdentity(address, forceRefresh = forceRefresh && attempt == 0)
    val name = last.first
    val avatar = last.second
    if (!name.isNullOrBlank() || !avatar.isNullOrBlank() || attempt == totalAttempts - 1) {
      return last
    }
    delay(retryDelayMs)
  }
  return last
}

private fun parsePublicProfileReadModel(payload: JSONObject?): PublicProfileReadModel? {
  if (payload == null) return null

  val walletAddress = normalizePublicProfileAddress(payload.optString("walletAddress", "")) ?: return null
  val contract = parseContractProfile(payload.optJSONObject("contract"))
  val recordsPayload = payload.optJSONObject("records")
  val topLevelAvatarRef = payload.optString("avatarUrl", "").trim().ifNullish()

  val records =
    PublicProfileRecordData(
      primaryName = recordsPayload?.optString("primaryName", "").orEmpty().trim().ifNullish(),
      avatarRef = recordsPayload?.optString("avatar", "").orEmpty().trim().ifNullish() ?: topLevelAvatarRef ?: contract?.photoUri?.trim().ifNullOrBlank(),
      coverRef = recordsPayload?.optString("cover", "").orEmpty().trim().ifNullish(),
      location = recordsPayload?.optString("location", "").orEmpty().trim().ifNullish() ?: payload.optString("location", "").trim().ifNullish(),
      school = recordsPayload?.optString("school", "").orEmpty().trim().ifNullish(),
    )

  val exists = payload.optBoolean("exists", false)
  return PublicProfileReadModel(
    exists = exists,
    walletAddress = walletAddress,
    handle = payload.optString("handle", "").trim().ifNullish() ?: records.primaryName,
    displayName = payload.optString("displayName", "").trim().ifNullish() ?: contract?.displayName?.trim().ifNullOrBlank(),
    avatarRef = records.avatarRef ?: contract?.photoUri?.trim().ifNullOrBlank(),
    contractProfile = if (exists) contract else null,
    records = records,
  )
}

private fun parseContractProfile(payload: JSONObject?): ContractProfileData? {
  if (payload == null) return null
  return ContractProfileData(
    profileVersion = payload.optInt("profileVersion", 2),
    displayName = payload.optString("displayName", "").trim(),
    nameHash = payload.optString("nameHash", "").trim(),
    age = payload.optInt("age", 0),
    heightCm = payload.optInt("heightCm", 0),
    nationality = payload.optString("nationality", "").trim(),
    languages = parseProfileLanguages(payload.optJSONArray("languages")),
    friendsOpenToMask = payload.optInt("friendsOpenToMask", 0),
    locationCityId = payload.optString("locationCityId", "").trim(),
    locationLatE6 = payload.optInt("locationLatE6", 0),
    locationLngE6 = payload.optInt("locationLngE6", 0),
    schoolId = payload.optString("schoolId", "").trim(),
    skillsCommit = payload.optString("skillsCommit", "").trim(),
    hobbiesCommit = payload.optString("hobbiesCommit", "").trim(),
    photoUri = payload.optString("photoUri", "").trim(),
    gender = payload.optInt("gender", 0),
    relocate = payload.optInt("relocate", 0),
    degree = payload.optInt("degree", 0),
    fieldBucket = payload.optInt("fieldBucket", 0),
    profession = payload.optInt("profession", 0),
    industry = payload.optInt("industry", 0),
    relationshipStatus = payload.optInt("relationshipStatus", 0),
    sexuality = payload.optInt("sexuality", 0),
    ethnicity = payload.optInt("ethnicity", 0),
    datingStyle = payload.optInt("datingStyle", 0),
    children = payload.optInt("children", 0),
    wantsChildren = payload.optInt("wantsChildren", 0),
    drinking = payload.optInt("drinking", 0),
    smoking = payload.optInt("smoking", 0),
    drugs = payload.optInt("drugs", 0),
    lookingFor = payload.optInt("lookingFor", 0),
    religion = payload.optInt("religion", 0),
    pets = payload.optInt("pets", 0),
    diet = payload.optInt("diet", 0),
  )
}

private fun parseProfileLanguages(payload: JSONArray?): List<ProfileLanguageEntry> {
  if (payload == null || payload.length() == 0) return emptyList()
  return buildList {
    for (index in 0 until payload.length()) {
      val row = payload.optJSONObject(index) ?: continue
      val code = row.optString("code", "").trim().lowercase()
      if (code.length != 2) continue
      add(
        ProfileLanguageEntry(
          code = code,
          proficiency = row.optInt("proficiency", 0),
        ),
      )
    }
  }
}

private fun normalizePublicProfileAddress(value: String): String? {
  val normalized = value.trim().lowercase()
  if (!PUBLIC_PROFILE_ADDRESS_REGEX.matches(normalized)) return null
  return normalized
}

private fun isFreshCachedProfile(cached: CachedPublicProfile, nowMs: Long = System.currentTimeMillis()): Boolean =
  nowMs - cached.cachedAtMs <= PUBLIC_PROFILE_CACHE_TTL_MS

private fun trimPublicProfileCacheLocked() {
  while (publicProfileCache.size > PUBLIC_PROFILE_CACHE_MAX_ENTRIES) {
    val oldestKey = publicProfileCache.entries.firstOrNull()?.key ?: return
    publicProfileCache.remove(oldestKey)
  }
}

private fun normalizedApiBaseUrl(): String? {
  val raw = runCatching { SongPublishService.API_CORE_URL }.getOrNull()?.trim()?.trimEnd('/') ?: return null
  if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
  return null
}

private fun encodeUrlComponent(value: String): String =
  URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

private fun String?.ifNullOrBlank(): String? {
  val trimmed = this?.trim().orEmpty()
  if (trimmed.isBlank()) return null
  return trimmed
}

private fun String?.ifNullish(): String? {
  val trimmed = this?.trim().orEmpty()
  if (trimmed.isBlank()) return null
  if (trimmed.equals("null", ignoreCase = true)) return null
  if (trimmed.equals("undefined", ignoreCase = true)) return null
  return trimmed
}
