package sc.pirate.app.music

import sc.pirate.app.song.encodeUrlComponent
import sc.pirate.app.util.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

private const val DISCOVER_SEARCH_LIMIT_DEFAULT = 12
private const val DISCOVER_SEARCH_LIMIT_MAX = 20

private data class RankedDiscoveryResult(
  val result: MusicDiscoveryResult,
  val bestRank: Int,
)

internal data class MusicDiscoverSearchPayload(
  val results: List<MusicDiscoveryResult>,
  val warning: String? = null,
)

private fun normalizedDiscoverSearchApiBaseUrl(): String? {
  val raw = runCatching { SongPublishService.API_CORE_URL }.getOrNull()?.trim()?.trimEnd('/') ?: return null
  return raw.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

private fun clampDiscoverSearchLimit(limit: Int): Int =
  limit.coerceIn(1, DISCOVER_SEARCH_LIMIT_MAX)

private fun parseLearnAvailability(value: String?): MusicDiscoveryLearnAvailability? =
  when (value?.trim()?.lowercase()) {
    "available" -> MusicDiscoveryLearnAvailability.Available
    "insufficient_lines" -> MusicDiscoveryLearnAvailability.InsufficientLines
    "no_referents" -> MusicDiscoveryLearnAvailability.NoReferents
    "error" -> MusicDiscoveryLearnAvailability.Error
    else -> null
  }

private fun parseOptionalString(value: String?): String? =
  value?.trim()?.takeIf { it.isNotBlank() }

private fun sourceRank(source: MusicDiscoverySource): Int =
  when (source) {
    MusicDiscoverySource.Both -> 0
    MusicDiscoverySource.Published -> 1
    MusicDiscoverySource.Catalog -> 2
  }

private fun learnRank(availability: MusicDiscoveryLearnAvailability?): Int =
  when (availability) {
    MusicDiscoveryLearnAvailability.Available -> 0
    MusicDiscoveryLearnAvailability.InsufficientLines,
    MusicDiscoveryLearnAvailability.NoReferents,
    MusicDiscoveryLearnAvailability.Error,
    -> 1
    null -> 2
  }

private fun mergeDiscoveryResults(
  catalogResults: List<MusicDiscoveryResult>,
  publishedResults: List<MusicDiscoveryResult>,
  limit: Int,
): List<MusicDiscoveryResult> {
  val merged = LinkedHashMap<String, RankedDiscoveryResult>()

  for ((index, result) in catalogResults.withIndex()) {
    merged[result.trackId] = RankedDiscoveryResult(result = result, bestRank = index)
  }

  for ((index, result) in publishedResults.withIndex()) {
    val existing = merged[result.trackId]
    if (existing == null) {
      merged[result.trackId] = RankedDiscoveryResult(result = result, bestRank = index)
      continue
    }

    merged[result.trackId] =
      RankedDiscoveryResult(
        result =
          existing.result.copy(
            album = existing.result.album ?: result.album,
            artworkUrl = existing.result.artworkUrl ?: result.artworkUrl,
            source =
              if (existing.result.source == MusicDiscoverySource.Catalog) {
                MusicDiscoverySource.Both
              } else {
                existing.result.source
              },
            isPublished = true,
            ownerAddress = existing.result.ownerAddress ?: result.ownerAddress,
          ),
        bestRank = minOf(existing.bestRank, index),
      )
  }

  return merged.values
    .sortedWith(
      compareBy<RankedDiscoveryResult> { it.bestRank }
        .thenBy { sourceRank(it.result.source) }
        .thenBy { learnRank(it.result.learnAvailability) }
        .thenBy { it.result.title.lowercase() }
        .thenBy { it.result.artist.lowercase() },
    )
    .take(limit)
    .map { it.result }
}

private suspend fun fetchCatalogSearchResults(
  query: String,
  limit: Int,
): List<MusicDiscoveryResult> = withContext(Dispatchers.IO) {
  val baseUrl = normalizedDiscoverSearchApiBaseUrl()
    ?: throw IllegalStateException("API unavailable: set API_CORE_URL to an absolute http(s) URL")
  val request =
    Request.Builder()
      .url("$baseUrl/api/study-sets/catalog/search?q=${encodeUrlComponent(query)}&limit=${clampDiscoverSearchLimit(limit)}")
      .get()
      .build()

  HttpClients.Api.newCall(request).execute().use { response ->
    val body = response.body?.string().orEmpty()
    val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
    if (!response.isSuccessful || !json.optBoolean("success", false)) {
      val error = json.optString("error", "").trim().ifBlank { "Catalog search failed (HTTP ${response.code})" }
      throw IllegalStateException(error)
    }

    val rows = json.optJSONArray("songs") ?: return@withContext emptyList()
    val out = ArrayList<MusicDiscoveryResult>(rows.length())
    for (index in 0 until rows.length()) {
      val row = rows.optJSONObject(index) ?: continue
      val trackId = row.optString("trackId", "").trim()
      val title = row.optString("title", "").trim()
      val artist =
        row.optString("artist", "").trim().ifBlank {
          row.optString("publisherDisplayName", "").trim()
        }
      if (trackId.isBlank() || title.isBlank() || artist.isBlank()) continue

      out +=
        MusicDiscoveryResult(
          trackId = trackId,
          title = title,
          artist = artist,
          album = parseOptionalString(row.optString("album", "")),
          artworkUrl = parseOptionalString(row.optString("artworkUrl", "")),
          source = MusicDiscoverySource.Catalog,
          isPublished = false,
          learnAvailability = parseLearnAvailability(row.optString("availability", "")),
          ownerAddress = null,
        )
    }
    out
  }
}

private suspend fun fetchPublishedSearchResults(
  query: String,
  limit: Int,
): List<MusicDiscoveryResult> = withContext(Dispatchers.IO) {
  val baseUrl = normalizedDiscoverSearchApiBaseUrl()
    ?: throw IllegalStateException("API unavailable: set API_CORE_URL to an absolute http(s) URL")
  val request =
    Request.Builder()
      .url("$baseUrl/api/music/search?q=${encodeUrlComponent(query)}&limit=${clampDiscoverSearchLimit(limit)}")
      .get()
      .build()

  HttpClients.Api.newCall(request).execute().use { response ->
    val body = response.body?.string().orEmpty()
    val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
    if (!response.isSuccessful || !json.optBoolean("success", false)) {
      val error = json.optString("error", "").trim().ifBlank { "Published song search failed (HTTP ${response.code})" }
      throw IllegalStateException(error)
    }

    val rows = json.optJSONArray("songs") ?: return@withContext emptyList()
    val out = ArrayList<MusicDiscoveryResult>(rows.length())
    for (index in 0 until rows.length()) {
      val row = rows.optJSONObject(index) ?: continue
      val trackId = row.optString("trackId", "").trim()
      val title = row.optString("title", "").trim()
      val artist = row.optString("publisherDisplayName", "").trim()
      if (trackId.isBlank() || title.isBlank() || artist.isBlank()) continue

      out +=
        MusicDiscoveryResult(
          trackId = trackId,
          title = title,
          artist = artist,
          album = parseOptionalString(row.optString("album", "")),
          artworkUrl = resolvePlaybackCoverUrl(parseOptionalString(row.optString("coverRef", ""))),
          source = MusicDiscoverySource.Published,
          isPublished = true,
          learnAvailability = null,
          ownerAddress = parseOptionalString(row.optString("ownerAddress", "")),
        )
    }
    out
  }
}

internal suspend fun searchMusicDiscovery(
  query: String,
  limit: Int = DISCOVER_SEARCH_LIMIT_DEFAULT,
): MusicDiscoverSearchPayload {
  val normalizedQuery = query.trim()
  if (normalizedQuery.isBlank()) {
    return MusicDiscoverSearchPayload(results = emptyList(), warning = null)
  }

  val safeLimit = clampDiscoverSearchLimit(limit)
  return coroutineScope {
    val catalogDeferred = async { runCatching { fetchCatalogSearchResults(normalizedQuery, safeLimit) } }
    val publishedDeferred = async { runCatching { fetchPublishedSearchResults(normalizedQuery, safeLimit) } }

    val catalogResult = catalogDeferred.await()
    val publishedResult = publishedDeferred.await()

    if (catalogResult.isFailure && publishedResult.isFailure) {
      val error = catalogResult.exceptionOrNull()?.message ?: publishedResult.exceptionOrNull()?.message
      throw IllegalStateException(error ?: "Search failed")
    }

    val warnings = ArrayList<String>(2)
    if (catalogResult.isFailure) warnings += "Genius results are temporarily unavailable."
    if (publishedResult.isFailure) warnings += "Pirate-published results are temporarily unavailable."

    MusicDiscoverSearchPayload(
      results =
        mergeDiscoveryResults(
          catalogResults = catalogResult.getOrDefault(emptyList()),
          publishedResults = publishedResult.getOrDefault(emptyList()),
          limit = safeLimit,
        ),
      warning = warnings.joinToString(" ").ifBlank { null },
    )
  }
}
