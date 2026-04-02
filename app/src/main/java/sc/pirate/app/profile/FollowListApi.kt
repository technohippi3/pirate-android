package sc.pirate.app.profile

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import sc.pirate.app.music.CoverRef
import sc.pirate.app.resolvePublicProfileIdentityWithRetry
import sc.pirate.app.util.shortAddress
import sc.pirate.app.util.baseProfilesSubgraphUrls

data class FollowListMember(
  val address: String,
  val name: String,
  val avatarUrl: String?,
  val followedAtSec: Long,
)

object FollowListApi {
  private enum class Mode {
    Followers,
    Following,
  }

  private data class EfpFollowListEntry(
    val address: String,
    val updatedAtSec: Long,
  )

  private const val EFP_API_URL = "https://data.ethfollow.xyz/api/v1"
  private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
  private val ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")
  private val http = OkHttpClient()

  suspend fun fetchFollowers(
    address: String,
    first: Int = 50,
    skip: Int = 0,
  ): List<FollowListMember> = withContext(Dispatchers.IO) {
    val normalizedAddress = normalizeAddress(address) ?: return@withContext emptyList()
    val entries = fetchFollowEntries(normalizedAddress, Mode.Followers, first, skip)
    resolveMembers(entries)
  }

  suspend fun fetchFollowing(
    address: String,
    first: Int = 50,
    skip: Int = 0,
  ): List<FollowListMember> = withContext(Dispatchers.IO) {
    val normalizedAddress = normalizeAddress(address) ?: return@withContext emptyList()
    val entries = fetchFollowEntries(normalizedAddress, Mode.Following, first, skip)
    resolveMembers(entries)
  }

  private fun fetchFollowEntries(
    address: String,
    mode: Mode,
    first: Int,
    skip: Int,
  ): List<EfpFollowListEntry> {
    val limit = first.coerceAtLeast(0)
    if (limit == 0) return emptyList()
    val offset = skip.coerceAtLeast(0)
    val collectionKey = if (mode == Mode.Followers) "followers" else "following"
    val path = "$EFP_API_URL/users/$address/$collectionKey?limit=$limit&offset=$offset&cache=fresh"
    val payload = runCatching { requestJson(path) }.getOrElse { return emptyList() }
    val rows = payload.optJSONArray(collectionKey) ?: return emptyList()
    return buildList(rows.length()) {
      for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val entryAddress = normalizeAddress(row.optString("address", "")) ?: continue
        val updatedAtSec = parseUpdatedAtSec(row.optString("updated_at", ""))
        add(EfpFollowListEntry(address = entryAddress, updatedAtSec = updatedAtSec))
      }
    }
  }

  private suspend fun resolveMembers(
    entries: List<EfpFollowListEntry>,
  ): List<FollowListMember> = coroutineScope {
    if (entries.isEmpty()) return@coroutineScope emptyList()

    val addresses = entries.map { it.address }
    val profileMap = runCatching { fetchProfilesBatch(addresses) }.getOrDefault(emptyMap())
    val identityJobs = addresses.take(50).map { addr ->
      async(Dispatchers.IO) {
        val identity = runCatching {
          resolvePublicProfileIdentityWithRetry(addr, attempts = 1)
        }.getOrNull()
        addr to identity
      }
    }
    val identityMap = identityJobs.awaitAll().toMap()

    entries.map { entry ->
      val profile = profileMap[entry.address]
      val resolvedIdentity = identityMap[entry.address]
      val resolvedName = resolvedIdentity?.first?.trim()?.takeIf { it.isNotBlank() }
      val displayName = profile?.first?.trim()?.takeIf { it.isNotBlank() }
      val name = resolvedName ?: displayName ?: shortAddress(entry.address, minLengthToShorten = 14)
      val photoUri = resolvedIdentity?.second?.trim()?.takeIf { it.isNotBlank() }
        ?: profile?.second?.trim()?.takeIf { it.isNotBlank() }
      val avatarUrl = photoUri?.let {
        CoverRef.resolveCoverUrl(ref = it, width = null, height = null, format = null, quality = null)
      }
      FollowListMember(
        address = entry.address,
        name = name,
        avatarUrl = avatarUrl,
        followedAtSec = entry.updatedAtSec,
      )
    }
  }

  private fun fetchProfilesBatch(addresses: List<String>): Map<String, Pair<String, String>> {
    if (addresses.isEmpty()) return emptyMap()
    val ids = addresses.joinToString(", ") { "\"$it\"" }
    val query = """
      {
        profiles(where: { id_in: [$ids] }) {
          id
          displayName
          photoURI
        }
      }
    """.trimIndent()

    val urls = profilesSubgraphUrls()
    for (url in urls) {
      val result = runCatching { postGraphQL(url, query) }.getOrNull() ?: continue
      return parseProfilesMap(result)
    }
    return emptyMap()
  }

  private fun requestJson(url: String): JSONObject {
    val req = Request.Builder().url(url).get().header("Accept", "application/json").build()
    http.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("EFP request failed (${res.code})")
      return JSONObject(res.body?.string().orEmpty())
    }
  }

  private fun postGraphQL(url: String, query: String): JSONObject {
    val body = JSONObject().put("query", query).toString()
    val req = Request.Builder()
      .url(url)
      .post(body.toRequestBody(JSON_TYPE))
      .build()
    http.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw IllegalStateException("GraphQL failed: ${res.code}")
      return JSONObject(res.body?.string().orEmpty())
    }
  }

  private fun normalizeAddress(address: String): String? {
    val trimmed = address.trim()
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    if (!ADDRESS_REGEX.matches(prefixed)) return null
    return "0x${prefixed.removePrefix("0x").removePrefix("0X").lowercase()}"
  }

  private fun parseUpdatedAtSec(value: String): Long {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return 0L
    return runCatching { Instant.parse(trimmed).epochSecond }.getOrDefault(0L)
  }

  private fun profilesSubgraphUrls(): List<String> = baseProfilesSubgraphUrls()
}

internal fun parseProfilesMap(graphqlResponse: JSONObject): Map<String, Pair<String, String>> {
  val data = graphqlResponse.optJSONObject("data") ?: return emptyMap()
  val profiles = data.optJSONArray("profiles") ?: return emptyMap()
  val map = mutableMapOf<String, Pair<String, String>>()
  for (i in 0 until profiles.length()) {
    val row = profiles.optJSONObject(i) ?: continue
    val address = row.optString("id", "").lowercase()
    val displayName = row.optString("displayName", "")
    val photoUri = row.optString("photoURI", "")
    if (address.isNotBlank()) map[address] = displayName to photoUri
  }
  return map
}
