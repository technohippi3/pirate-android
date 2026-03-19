package sc.pirate.app.song

import android.util.Log
import sc.pirate.app.music.SongPublishService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val MUSICBRAINZ_BASE_URL = "https://musicbrainz.org"
private const val MUSICBRAINZ_USER_AGENT = "pirate-android/1.0 (+https://pirate.sc; contact@pirate.sc)"
private const val ARTIST_IMAGE_LOG_TAG = "ArtistImageApi"
private const val DEFAULT_API_CORE_URL = "https://api.pirate.sc"

private val MBID_REGEX =
  Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

private data class ResolveTriggerResult(
  val readyUrl: String? = null,
  val pending: Boolean = false,
)

object ArtistImageApi {
  private val recordingToArtistMbid = ConcurrentHashMap<String, String>()
  private val artistNameToArtistMbid = ConcurrentHashMap<String, String>()
  private val artistMbidToImageUrl = ConcurrentHashMap<String, String>()

  suspend fun resolveArtistImageUrl(recordingMbid: String?, artistName: String?, userAddress: String?): String? =
    withContext(Dispatchers.IO) {
      val apiBase = normalizedApiBaseUrl() ?: return@withContext null
      val normalizedRecordingMbid = recordingMbid?.let(::normalizeMbid)
      val normalizedArtistName = artistName?.trim().orEmpty().ifBlank { null }
      Log.d(
        ARTIST_IMAGE_LOG_TAG,
        "resolve:start recordingMbid=$normalizedRecordingMbid artistName=$normalizedArtistName hasUserAddress=${!userAddress.isNullOrBlank()} apiBase=$apiBase",
      )

      val artistMbid =
        normalizedRecordingMbid?.let { mbid ->
          recordingToArtistMbid[mbid]
            ?: resolveArtistMbidFromRecording(mbid)?.also { resolved ->
              recordingToArtistMbid[mbid] = resolved
            }
        }
          ?: normalizedArtistName?.let { name ->
            val cacheKey = normalizeArtistName(name)
            if (cacheKey.isBlank()) {
              null
            } else {
              artistNameToArtistMbid[cacheKey]
                ?: resolveArtistMbidFromArtistNameFuzzy(name)?.also { resolved ->
                  artistNameToArtistMbid[cacheKey] = resolved
                }
            }
          }
          ?: return@withContext null
      Log.d(ARTIST_IMAGE_LOG_TAG, "resolve:artistMbid recordingMbid=$normalizedRecordingMbid artistMbid=$artistMbid")

      artistMbidToImageUrl[artistMbid]?.let {
        Log.d(ARTIST_IMAGE_LOG_TAG, "resolve:cache-hit artistMbid=$artistMbid url=$it")
        return@withContext it
      }

      fetchReadyArtistImageUrl(apiBase, artistMbid)?.let { ready ->
        artistMbidToImageUrl[artistMbid] = ready
        Log.d(ARTIST_IMAGE_LOG_TAG, "resolve:backend-ready artistMbid=$artistMbid url=$ready")
        return@withContext ready
      }

      val trigger = triggerResolve(apiBase, artistMbid, userAddress)
      trigger.readyUrl?.let { ready ->
        artistMbidToImageUrl[artistMbid] = ready
        Log.d(ARTIST_IMAGE_LOG_TAG, "resolve:trigger-ready artistMbid=$artistMbid url=$ready")
        return@withContext ready
      }
      if (!trigger.pending) {
        Log.d(ARTIST_IMAGE_LOG_TAG, "resolve:trigger-not-pending artistMbid=$artistMbid")
        return@withContext null
      }

      repeat(3) { attempt ->
        delay((attempt + 1) * 1_000L)
        fetchReadyArtistImageUrl(apiBase, artistMbid)?.let { ready ->
          artistMbidToImageUrl[artistMbid] = ready
          Log.d(ARTIST_IMAGE_LOG_TAG, "resolve:poll-ready artistMbid=$artistMbid attempt=${attempt + 1} url=$ready")
          return@withContext ready
        }
        Log.d(ARTIST_IMAGE_LOG_TAG, "resolve:poll-miss artistMbid=$artistMbid attempt=${attempt + 1}")
      }

      Log.d(ARTIST_IMAGE_LOG_TAG, "resolve:timeout artistMbid=$artistMbid")
      null
    }

  private fun normalizedApiBaseUrl(): String? {
    val raw = runCatching { SongPublishService.API_CORE_URL }
      .getOrDefault(DEFAULT_API_CORE_URL)
      .trim()
      .trimEnd('/')
    if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
    return null
  }

  private fun normalizeMbid(raw: String): String? {
    val trimmed = raw.trim()
    if (!MBID_REGEX.matches(trimmed)) return null
    return trimmed.lowercase()
  }

  private fun resolveArtistMbidFromRecording(recordingMbid: String): String? {
    val url =
      "$MUSICBRAINZ_BASE_URL/ws/2/recording/${encodeUrlComponent(recordingMbid)}?inc=artists&fmt=json"
    val req =
      Request.Builder()
        .url(url)
        .get()
        .header("Accept", "application/json")
        .header("User-Agent", MUSICBRAINZ_USER_AGENT)
        .build()

    return songArtistClient.newCall(req).execute().use { res ->
      if (!res.isSuccessful) {
        Log.w(ARTIST_IMAGE_LOG_TAG, "mb:recording-lookup-failed recordingMbid=$recordingMbid code=${res.code}")
        return@use null
      }
      val json = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrNull() ?: return@use null
      val credits = json.optJSONArray("artist-credit") ?: return@use null
      for (i in 0 until credits.length()) {
        val artistObj = credits.optJSONObject(i)?.optJSONObject("artist") ?: continue
        val mbid = normalizeMbid(artistObj.optString("id", "")) ?: continue
        Log.d(ARTIST_IMAGE_LOG_TAG, "mb:recording-lookup-ok recordingMbid=$recordingMbid artistMbid=$mbid")
        return@use mbid
      }
      Log.d(ARTIST_IMAGE_LOG_TAG, "mb:recording-lookup-no-artist recordingMbid=$recordingMbid")
      null
    }
  }

  private fun resolveArtistMbidFromArtistNameFuzzy(artistName: String): String? {
    val targetNorm = normalizeArtistName(artistName)
    if (targetNorm.isBlank()) return null
    val url =
      "$MUSICBRAINZ_BASE_URL/ws/2/artist?query=artist:${encodeUrlComponent(artistName)}&fmt=json&limit=8"
    val req =
      Request.Builder()
        .url(url)
        .get()
        .header("Accept", "application/json")
        .header("User-Agent", MUSICBRAINZ_USER_AGENT)
        .build()

    return songArtistClient.newCall(req).execute().use { res ->
      if (!res.isSuccessful) {
        Log.w(ARTIST_IMAGE_LOG_TAG, "mb:artist-search-failed artistName=$artistName code=${res.code}")
        return@use null
      }
      val json = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrNull() ?: return@use null
      val artists = json.optJSONArray("artists") ?: return@use null
      var bestScore = Int.MIN_VALUE
      var bestMbid: String? = null
      var bestName: String? = null
      for (i in 0 until artists.length()) {
        val row = artists.optJSONObject(i) ?: continue
        val candidateName = row.optString("name", "").trim()
        val candidateMbid = normalizeMbid(row.optString("id", "")) ?: continue
        val score = fuzzyArtistNameScore(targetNorm, candidateName)
        if (score > bestScore) {
          bestScore = score
          bestMbid = candidateMbid
          bestName = candidateName
        }
      }
      if (bestMbid != null) {
        Log.d(
          ARTIST_IMAGE_LOG_TAG,
          "mb:artist-search-best artistName=$artistName matchedName=$bestName score=$bestScore artistMbid=$bestMbid",
        )
      } else {
        Log.d(ARTIST_IMAGE_LOG_TAG, "mb:artist-search-none artistName=$artistName")
      }
      if (bestScore >= 70) bestMbid else null
    }
  }

  private fun fuzzyArtistNameScore(targetNormalizedName: String, candidateRawName: String): Int {
    val candidateNorm = normalizeArtistName(candidateRawName)
    if (candidateNorm.isBlank()) return Int.MIN_VALUE
    if (candidateNorm == targetNormalizedName) return 1000

    var score = 0
    if (candidateNorm.startsWith(targetNormalizedName) || targetNormalizedName.startsWith(candidateNorm)) score += 120
    if (candidateNorm.contains(targetNormalizedName) || targetNormalizedName.contains(candidateNorm)) score += 80

    val targetTokens = targetNormalizedName.split(" ").filter { it.isNotBlank() }.toSet()
    val candidateTokens = candidateNorm.split(" ").filter { it.isNotBlank() }.toSet()
    val intersection = targetTokens.intersect(candidateTokens).size
    val union = targetTokens.union(candidateTokens).size
    if (union > 0) {
      score += (intersection * 100) / union
    }

    score -= kotlin.math.abs(targetNormalizedName.length - candidateNorm.length)
    return score
  }

  private fun fetchReadyArtistImageUrl(apiBase: String, artistMbid: String): String? {
    val url = "$apiBase/api/artist-image/${encodeUrlComponent(artistMbid)}"
    val req =
      Request.Builder()
        .url(url)
        .get()
        .header("Accept", "application/json")
        .build()
    return songArtistClient.newCall(req).execute().use { res ->
      if (!res.isSuccessful) {
        Log.d(ARTIST_IMAGE_LOG_TAG, "backend:get-miss artistMbid=$artistMbid code=${res.code}")
        return@use null
      }
      val json = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrNull() ?: return@use null
      val ready = parseReadyUrl(json)
      Log.d(ARTIST_IMAGE_LOG_TAG, "backend:get-ok artistMbid=$artistMbid hasReadyUrl=${!ready.isNullOrBlank()}")
      ready
    }
  }

  private fun triggerResolve(apiBase: String, artistMbid: String, userAddress: String?): ResolveTriggerResult {
    val normalizedUserAddress = userAddress?.let(::normalizeAddress)
    if (normalizedUserAddress == null) {
      Log.d(ARTIST_IMAGE_LOG_TAG, "backend:put-skipped-no-user-address artistMbid=$artistMbid")
      return ResolveTriggerResult()
    }
    val url = "$apiBase/api/artist-image/${encodeUrlComponent(artistMbid)}"
    val req =
      Request.Builder()
        .url(url)
        .put("{}".toRequestBody(songArtistJsonMediaType))
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("X-User-Address", normalizedUserAddress)
        .build()
    return songArtistClient.newCall(req).execute().use { res ->
      val json = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrNull()
      Log.d(ARTIST_IMAGE_LOG_TAG, "backend:put-response artistMbid=$artistMbid code=${res.code}")
      if (res.code == 200) {
        return@use ResolveTriggerResult(readyUrl = parseReadyUrl(json), pending = false)
      }
      if (res.code == 202) {
        return@use ResolveTriggerResult(readyUrl = parseReadyUrl(json), pending = true)
      }
      ResolveTriggerResult()
    }
  }

  private fun parseReadyUrl(json: JSONObject?): String? {
    val payload = json ?: return null
    val status = payload.optString("status", "").trim().lowercase()
    val url = payload.optString("imageUrl", "").trim().ifBlank { null } ?: return null
    if (status.isNotBlank() && status != "ready") return null
    if (!url.startsWith("https://") && !url.startsWith("http://")) return null
    return url
  }
}
