package sc.pirate.app.song

import android.util.Log
import sc.pirate.app.music.SongPublishService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object SongArtistApi {
  private const val TAG = "SongArtistApi"
  private const val GENIUS_WEB_URL = "https://genius.com"
  private const val GENIUS_PUBLIC_API_URL = "https://genius.com/api"
  private const val GENIUS_MIN_LINES_TOTAL = 6
  private const val GENIUS_HITS_PER_QUERY = 10

  suspend fun fetchSongStats(trackId: String): SongStats? =
    withContext(Dispatchers.IO) {
      val normalizedTrackId = normalizeBytes32(trackId) ?: return@withContext null

      var sawSuccessfulEmpty = false
      var lastError: Throwable? = null
      for (subgraphUrl in musicSocialSubgraphUrls()) {
        try {
          val row = fetchSongStatsFromSubgraph(subgraphUrl, normalizedTrackId)
          if (row != null) return@withContext row
          sawSuccessfulEmpty = true
        } catch (error: Throwable) {
          Log.w(TAG, "fetchSongStats failed for $subgraphUrl", error)
          lastError = error
        }
      }

      fetchSongStatsFromChain(normalizedTrackId)?.let { return@withContext it }

      if (sawSuccessfulEmpty) return@withContext null
      if (lastError != null && isSubgraphAvailabilityError(lastError)) return@withContext null
      if (lastError != null) throw lastError
      null
    }

  suspend fun fetchSongTopListeners(trackId: String, maxEntries: Int = 20): List<SongListenerRow> =
    withContext(Dispatchers.IO) {
      val normalizedTrackId = normalizeBytes32(trackId) ?: return@withContext emptyList()
      val first = maxEntries.coerceIn(1, 100)

      var sawSuccessfulEmpty = false
      var lastError: Throwable? = null
      for (subgraphUrl in musicSocialSubgraphUrls()) {
        try {
          val rows = fetchSongTopListenersFromSubgraph(subgraphUrl, normalizedTrackId, first)
          if (rows.isNotEmpty()) return@withContext rows
          sawSuccessfulEmpty = true
        } catch (error: Throwable) {
          Log.w(TAG, "fetchSongTopListeners failed for $subgraphUrl", error)
          lastError = error
        }
      }

      if (sawSuccessfulEmpty) return@withContext emptyList()
      if (lastError != null && isSubgraphAvailabilityError(lastError)) return@withContext emptyList()
      if (lastError != null) throw lastError
      emptyList()
    }

  suspend fun fetchSongRecentScrobbles(trackId: String, maxEntries: Int = 40): List<SongScrobbleRow> =
    withContext(Dispatchers.IO) {
      val normalizedTrackId = normalizeBytes32(trackId) ?: return@withContext emptyList()
      val first = maxEntries.coerceIn(1, 200)

      var sawSuccessfulEmpty = false
      var lastError: Throwable? = null
      for (subgraphUrl in musicSocialSubgraphUrls()) {
        try {
          val rows = fetchSongRecentScrobblesFromSubgraph(subgraphUrl, normalizedTrackId, first)
          if (rows.isNotEmpty()) return@withContext rows
          sawSuccessfulEmpty = true
        } catch (error: Throwable) {
          Log.w(TAG, "fetchSongRecentScrobbles failed for $subgraphUrl", error)
          lastError = error
        }
      }

      if (sawSuccessfulEmpty) return@withContext emptyList()
      if (lastError != null && isSubgraphAvailabilityError(lastError)) return@withContext emptyList()
      if (lastError != null) throw lastError
      emptyList()
    }

  suspend fun fetchArtistTopTracks(artistName: String, maxEntries: Int = 50): List<ArtistTrackRow> =
    withContext(Dispatchers.IO) {
      val artist = artistName.trim()
      if (artist.isBlank()) return@withContext emptyList()
      val first = maxEntries.coerceIn(1, 200)

      var sawSuccessfulEmpty = false
      var lastError: Throwable? = null
      for (subgraphUrl in musicSocialSubgraphUrls()) {
        try {
          val rows = fetchArtistTopTracksFromSubgraph(subgraphUrl, artist, first)
          if (rows.isNotEmpty()) return@withContext rows
          sawSuccessfulEmpty = true
        } catch (error: Throwable) {
          Log.w(TAG, "fetchArtistTopTracks failed for $subgraphUrl", error)
          lastError = error
        }
      }

      if (sawSuccessfulEmpty) return@withContext emptyList()
      if (lastError != null && isSubgraphAvailabilityError(lastError)) return@withContext emptyList()
      if (lastError != null) throw lastError
      emptyList()
    }

  suspend fun fetchArtistTopListeners(artistName: String, maxEntries: Int = 20): List<ArtistListenerRow> =
    withContext(Dispatchers.IO) {
      val artist = artistName.trim()
      if (artist.isBlank()) return@withContext emptyList()
      val first = maxEntries.coerceIn(1, 100)

      var sawSuccessfulEmpty = false
      var lastError: Throwable? = null
      for (subgraphUrl in musicSocialSubgraphUrls()) {
        try {
          val rows = fetchArtistTopListenersFromSubgraph(subgraphUrl, artist, first)
          if (rows.isNotEmpty()) return@withContext rows
          sawSuccessfulEmpty = true
        } catch (error: Throwable) {
          Log.w(TAG, "fetchArtistTopListeners failed for $subgraphUrl", error)
          lastError = error
        }
      }

      if (sawSuccessfulEmpty) return@withContext emptyList()
      if (lastError != null && isSubgraphAvailabilityError(lastError)) return@withContext emptyList()
      if (lastError != null) throw lastError
      emptyList()
    }

  suspend fun fetchArtistRecentScrobbles(artistName: String, maxEntries: Int = 40): List<ArtistScrobbleRow> =
    withContext(Dispatchers.IO) {
      val artist = artistName.trim()
      if (artist.isBlank()) return@withContext emptyList()
      val first = maxEntries.coerceIn(1, 200)

      var sawSuccessfulEmpty = false
      var lastError: Throwable? = null
      for (subgraphUrl in musicSocialSubgraphUrls()) {
        try {
          val rows = fetchArtistRecentScrobblesFromSubgraph(subgraphUrl, artist, first)
          if (rows.isNotEmpty()) return@withContext rows
          sawSuccessfulEmpty = true
        } catch (error: Throwable) {
          Log.w(TAG, "fetchArtistRecentScrobbles failed for $subgraphUrl", error)
          lastError = error
        }
      }

      if (sawSuccessfulEmpty) return@withContext emptyList()
      if (lastError != null && isSubgraphAvailabilityError(lastError)) return@withContext emptyList()
      if (lastError != null) throw lastError
      emptyList()
    }

  suspend fun fetchLatestTracksFromChain(maxEntries: Int = 100): List<SongStats> =
    withContext(Dispatchers.IO) {
      val first = maxEntries.coerceIn(1, 200)
      val trackIds = fetchRecentRegisteredTrackIdsFromChain(first)
      if (trackIds.isEmpty()) return@withContext emptyList()
      val meta = fetchTrackMetaFromChain(trackIds)
      if (meta.isEmpty()) return@withContext emptyList()
      meta.values
        .sortedByDescending { it.registeredAtSec }
        .take(first)
        .map {
          SongStats(
            trackId = it.trackId,
            title = it.title.ifBlank { it.trackId.take(14) },
            artist = it.artist.ifBlank { "Unknown Artist" },
            album = it.album,
            coverCid = it.coverCid,
            lyricsRef = null,
            scrobbleCountTotal = 0L,
            scrobbleCountVerified = 0L,
            registeredAtSec = it.registeredAtSec,
          )
        }
    }

  suspend fun resolveSongStatsByTitleArtist(
    title: String,
    artistName: String,
    maxEntries: Int = 120,
  ): SongStats? =
    withContext(Dispatchers.IO) {
      val titleNorm = normalizeSongTitleForMatch(title)
      if (titleNorm.isBlank()) return@withContext null

      val artistCandidates =
        buildList {
          val raw = artistName.trim()
          if (raw.isNotBlank()) add(raw)
          addAll(parseAllArtists(raw))
        }.map { it.trim() }
          .filter { it.isNotBlank() }
          .distinct()
      if (artistCandidates.isEmpty()) return@withContext null
      val artistTargets =
        artistCandidates
          .map { it to normalizeArtistName(it) }
          .filter { it.second.isNotBlank() }
      val artistCandidateNorms = artistTargets.map { it.second }.distinct()
      if (artistCandidateNorms.isEmpty()) return@withContext null

      var best: Pair<ArtistTrackRow, Double>? = null
      val first = maxEntries.coerceIn(20, 200)
      for ((artist, targetArtistNorm) in artistTargets) {
        val rows = runCatching { fetchArtistTopTracks(artist, first) }.getOrElse { emptyList() }
        for (row in rows) {
          val score = scoreTrackCandidate(titleNorm, targetArtistNorm, row)
          val currentBest = best
          if (currentBest == null || score > currentBest.second) {
            best = row to score
          }
        }
      }

      if ((best?.second ?: 0.0) < 0.58) {
        val broadFirst = (first * 2).coerceIn(40, 300)
        for (subgraphUrl in musicSocialSubgraphUrls()) {
          val rows = runCatching { fetchTracksByTitleFromSubgraph(subgraphUrl, title, broadFirst) }.getOrElse { emptyList() }
          for (row in rows) {
            var candidateBest = Double.NEGATIVE_INFINITY
            for (targetArtistNorm in artistCandidateNorms) {
              if (!artistMatchesTarget(row.artist, targetArtistNorm)) continue
              val score = scoreTrackCandidate(titleNorm, targetArtistNorm, row)
              if (score > candidateBest) candidateBest = score
            }
            if (candidateBest.isFinite()) {
              val currentBest = best
              if (currentBest == null || candidateBest > currentBest.second) {
                best = row to candidateBest
              }
            }
          }
        }
      }

      val winner = best?.takeIf { it.second >= 0.58 }?.first ?: return@withContext null
      SongStats(
        trackId = winner.trackId,
        title = winner.title.ifBlank { title },
        artist = winner.artist.ifBlank { artistName.ifBlank { "Unknown Artist" } },
        album = winner.album,
        coverCid = winner.coverCid,
        lyricsRef = winner.lyricsRef,
        scrobbleCountTotal = winner.scrobbleCountTotal,
        scrobbleCountVerified = winner.scrobbleCountVerified,
        registeredAtSec = 0L,
      )
    }

  internal fun buildGeniusSongSearchUrl(
    title: String,
    artist: String?,
  ): String {
    val query = listOfNotNull(artist?.trim(), title.trim()).filter { it.isNotBlank() }.joinToString(" ").trim()
    return "$GENIUS_WEB_URL/search?q=${encodeUrlComponent(query)}"
  }

  suspend fun fetchGeniusAnnotationCoverage(
    title: String,
    artist: String,
    minLineCount: Int = GENIUS_MIN_LINES_TOTAL,
  ): GeniusAnnotationCoverage =
    withContext(Dispatchers.IO) {
      val normalizedTitle = title.trim()
      val normalizedArtist = artist.trim()
      val fallbackSearchUrl = buildGeniusSongSearchUrl(title = normalizedTitle, artist = normalizedArtist)
      if (normalizedTitle.isBlank() || normalizedArtist.isBlank()) {
        return@withContext GeniusAnnotationCoverage(
          insufficientLyricLines = false,
          lineCount = null,
          minLineCount = minLineCount.coerceAtLeast(1),
          geniusSongId = null,
          geniusSongUrl = fallbackSearchUrl,
        )
      }

      val artistCandidates =
        linkedSetOf<String>().apply {
          add(normalizedArtist)
          add(primaryArtist(normalizedArtist))
          addAll(parseAllArtists(normalizedArtist))
        }.filter { it.isNotBlank() }
      val titleCandidates =
        linkedSetOf<String>().apply {
          add(normalizedTitle)
          add(normalizeTitleForLyricsLookup(normalizedTitle))
        }.filter { it.isNotBlank() }

      val queries =
        linkedSetOf<String>().apply {
          for (artistCandidate in artistCandidates) {
            for (titleCandidate in titleCandidates) {
              add("$artistCandidate $titleCandidate".trim())
              add("$titleCandidate $artistCandidate".trim())
            }
          }
        }.filter { it.isNotBlank() }

      val candidateHits = LinkedHashMap<Int, GeniusSongHit>()
      for (query in queries) {
        val hits = runCatching { fetchGeniusSongHits(query) }.getOrElse { emptyList() }
        for (hit in hits) {
          if (candidateHits.size >= GENIUS_HITS_PER_QUERY) break
          candidateHits.putIfAbsent(hit.id, hit)
        }
        if (candidateHits.size >= GENIUS_HITS_PER_QUERY) break
      }

      val bestHit = pickBestGeniusSongHit(normalizedTitle, normalizedArtist, candidateHits.values.toList())
      if (bestHit == null) {
        return@withContext GeniusAnnotationCoverage(
          insufficientLyricLines = false,
          lineCount = null,
          minLineCount = minLineCount.coerceAtLeast(1),
          geniusSongId = null,
          geniusSongUrl = fallbackSearchUrl,
        )
      }

      val referents = runCatching { fetchGeniusReferents(bestHit.id) }.getOrNull()
      if (referents == null) {
        return@withContext GeniusAnnotationCoverage(
          insufficientLyricLines = false,
          lineCount = null,
          minLineCount = minLineCount.coerceAtLeast(1),
          geniusSongId = bestHit.id,
          geniusSongUrl = bestHit.url ?: fallbackSearchUrl,
        )
      }

      val usableLineCount = countUsableGeniusLines(referents)
      val minRequired = minLineCount.coerceAtLeast(1)
      return@withContext GeniusAnnotationCoverage(
        insufficientLyricLines = usableLineCount < minRequired,
        lineCount = usableLineCount,
        minLineCount = minRequired,
        geniusSongId = bestHit.id,
        geniusSongUrl = bestHit.url ?: fallbackSearchUrl,
      )
    }

  suspend fun fetchStudySetStatus(trackId: String, language: String): StudySetStatus =
    withContext(Dispatchers.IO) {
      val normalizedTrackId =
        normalizeBytes32(trackId)
          ?: return@withContext StudySetStatus(
            ready = false,
            studySetRef = null,
            studySetHash = null,
            errorCode = "invalid_track_id",
            error = "trackId must be bytes32",
          )
      val apiBaseUrl = normalizedApiBaseUrl()
        ?: return@withContext StudySetStatus(
          ready = false,
          studySetRef = null,
          studySetHash = null,
          errorCode = "invalid_api_base_url",
          error = "API unavailable: set API_CORE_URL to an absolute http(s) URL",
        )

      val lang = language.trim().ifBlank { "en" }
      val url = "$apiBaseUrl/api/study-sets/$normalizedTrackId?lang=${encodeUrlComponent(lang)}&v=2"
      val req = Request.Builder().url(url).get().build()
      Log.d("StudySetApi", "status request trackId='$normalizedTrackId' lang='$lang' url='$url'")

      studySetGenerateClient.newCall(req).execute().use { res ->
        val text = res.body?.string().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()

        if (res.isSuccessful && json?.optBoolean("success") == true) {
          val registry = json.optJSONObject("registry")
          Log.d(
            "StudySetApi",
            "status success trackId='$normalizedTrackId' lang='$lang' ref='${registry?.optString("studySetRef")}' hash='${registry?.optString("studySetHash")}'",
          )
          return@withContext StudySetStatus(
            ready = true,
            studySetRef = registry?.optString("studySetRef")?.ifBlank { null },
            studySetHash = registry?.optString("studySetHash")?.ifBlank { null },
            errorCode = null,
            error = null,
          )
        }

        val responseError = json?.optString("error")?.ifBlank { "HTTP ${res.code}" } ?: "HTTP ${res.code}"
        Log.w(
          "StudySetApi",
          "status miss trackId='$normalizedTrackId' lang='$lang' http=${res.code} code='${json?.optString("code")}' err='$responseError'",
        )
        return@withContext StudySetStatus(
          ready = false,
          studySetRef = null,
          studySetHash = null,
          errorCode = json?.optString("code")?.ifBlank { null },
          error = responseError,
        )
      }
    }

  suspend fun generateStudySet(
    trackId: String,
    language: String,
    userAddress: String,
    title: String? = null,
    artist: String? = null,
    album: String? = null,
  ): StudySetGenerateResult =
    withContext(Dispatchers.IO) {
      val normalizedTrackId =
        normalizeBytes32(trackId)
          ?: return@withContext StudySetGenerateResult(
            success = false,
            cached = false,
            studySetRef = null,
            studySetHash = null,
            errorCode = "invalid_track_id",
            error = "trackId must be bytes32",
          )

      val normalizedUserAddress =
        normalizeAddress(userAddress)
          ?: return@withContext StudySetGenerateResult(
            success = false,
            cached = false,
            studySetRef = null,
            studySetHash = null,
            errorCode = "invalid_user_address",
            error = "userAddress must be 0x + 40 hex",
          )
      val apiBaseUrl = normalizedApiBaseUrl()
        ?: return@withContext StudySetGenerateResult(
          success = false,
          cached = false,
          studySetRef = null,
          studySetHash = null,
          errorCode = "invalid_api_base_url",
          error = "API unavailable: set API_CORE_URL to an absolute http(s) URL",
        )

      val normalizedLang = language.trim().ifBlank { "en" }
      val rawTitle = title?.trim()?.takeIf { it.isNotBlank() }
      val fallbackTitle = rawTitle?.let(::normalizeTitleForLyricsLookup)?.takeIf { it.isNotBlank() && it != rawTitle }

      fun requestGenerate(requestTitle: String?): StudySetGenerateResult {
        val body =
          JSONObject().apply {
            put("trackId", normalizedTrackId)
            put("language", normalizedLang)
            put("version", 2)
            requestTitle?.let { put("title", it) }
            artist?.trim()?.takeIf { it.isNotBlank() }?.let { put("artist", it) }
            album?.trim()?.takeIf { it.isNotBlank() }?.let { put("album", it) }
          }

        val req =
          Request.Builder()
            .url("$apiBaseUrl/api/study-sets/generate")
            .post(body.toString().toRequestBody(songArtistJsonMediaType))
            .header("Content-Type", "application/json")
            .header("X-User-Address", normalizedUserAddress)
            .build()
        Log.d(
          "StudySetApi",
          "generate request trackId='$normalizedTrackId' lang='$normalizedLang' user='$normalizedUserAddress' title='${requestTitle ?: "<none>"}' artist='${artist?.trim().orEmpty()}'",
        )

        studySetGenerateClient.newCall(req).execute().use { res ->
          val text = res.body?.string().orEmpty()
          val json = runCatching { JSONObject(text) }.getOrNull()

          val success = json?.optBoolean("success") == true
          val cached = json?.optBoolean("cached") == true
          val registry = json?.optJSONObject("registry")
          val studySetRef = registry?.optString("studySetRef")?.ifBlank { null }
          val studySetHash = registry?.optString("studySetHash")?.ifBlank { null }
          Log.d(
            "StudySetApi",
            "generate response trackId='$normalizedTrackId' http=${res.code} success=$success cached=$cached ref='$studySetRef' hash='$studySetHash' code='${json?.optString("code")}' err='${json?.optString("error")}'",
          )

          return StudySetGenerateResult(
            success = success,
            cached = cached,
            studySetRef = studySetRef,
            studySetHash = studySetHash,
            errorCode = if (success) null else json?.optString("code")?.ifBlank { null },
            error = if (success) null else json?.optString("error")?.ifBlank { "HTTP ${res.code}" },
            lineCount = if (success) null else json?.optInt("lineCount")?.takeIf { it >= 0 },
            minLineCount = if (success) null else json?.optInt("minLineCount")?.takeIf { it > 0 },
          )
        }
      }

      val primaryResult = requestGenerate(rawTitle)
      if (!primaryResult.success && primaryResult.errorCode.equals("lyrics_not_found", ignoreCase = true) && !fallbackTitle.isNullOrBlank()) {
        Log.d(
          "StudySetApi",
          "retrying generate with normalized title trackId='$normalizedTrackId' primaryTitle='${rawTitle.orEmpty()}' fallbackTitle='$fallbackTitle'",
        )
        return@withContext requestGenerate(fallbackTitle)
      }

      return@withContext primaryResult
    }

  suspend fun fetchSongCoverFallback(
    title: String,
    artist: String,
    album: String? = null,
  ): String? =
    withContext(Dispatchers.IO) {
      val normalizedTitle = title.trim().ifBlank { return@withContext null }
      val normalizedArtist = artist.trim().ifBlank { return@withContext null }
      val apiBaseUrl = normalizedApiBaseUrl() ?: return@withContext null

      val query =
        buildString {
          append("title=")
          append(java.net.URLEncoder.encode(normalizedTitle, "UTF-8"))
          append("&artist=")
          append(java.net.URLEncoder.encode(normalizedArtist, "UTF-8"))
          val normalizedAlbum = album?.trim().orEmpty()
          if (normalizedAlbum.isNotBlank()) {
            append("&album=")
            append(java.net.URLEncoder.encode(normalizedAlbum, "UTF-8"))
          }
        }

      val req =
        Request.Builder()
          .url("$apiBaseUrl/api/music/cover/song?$query")
          .get()
          .build()

      return@withContext runCatching {
        studySetGenerateClient.newCall(req).execute().use { res ->
          if (!res.isSuccessful) return@use null
          val text = res.body?.string().orEmpty()
          val json = runCatching { JSONObject(text) }.getOrNull() ?: return@use null
          json.optString("coverUrl", "").trim().ifBlank { null }
        }
      }.getOrNull()
    }

  private data class GeniusSongHit(
    val id: Int,
    val title: String,
    val artist: String,
    val url: String?,
  )

  private fun fetchGeniusSongHits(query: String): List<GeniusSongHit> {
    val url = "$GENIUS_PUBLIC_API_URL/search/song?q=${encodeUrlComponent(query)}"
    val req = Request.Builder().url(url).get().build()
    return songArtistClient.newCall(req).execute().use { res ->
      if (!res.isSuccessful) return emptyList()
      val payload = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrNull() ?: return emptyList()
      parseGeniusSongHits(payload)
    }
  }

  private fun parseGeniusSongHits(payload: JSONObject): List<GeniusSongHit> {
    val response = payload.optJSONObject("response")
    val directHits = response?.optJSONArray("hits")
    val hits =
      if (directHits != null && directHits.length() > 0) {
        directHits
      } else {
        val sections = response?.optJSONArray("sections") ?: JSONArray()
        var selected: JSONArray = JSONArray()
        for (i in 0 until sections.length()) {
          val section = sections.optJSONObject(i) ?: continue
          if (!section.optString("type").equals("song", ignoreCase = true)) continue
          val sectionHits = section.optJSONArray("hits")
          if (sectionHits != null && sectionHits.length() > 0) {
            selected = sectionHits
            break
          }
        }
        selected
      }

    val out = ArrayList<GeniusSongHit>(hits.length())
    for (i in 0 until hits.length()) {
      val hit = hits.optJSONObject(i)?.optJSONObject("result") ?: continue
      val id = hit.optInt("id", -1)
      if (id <= 0) continue
      val title = hit.optString("title", "").trim().ifBlank { "Unknown Title" }
      val artist = hit.optJSONObject("primary_artist")?.optString("name", "").orEmpty().trim().ifBlank { "Unknown Artist" }
      val songUrl = hit.optString("url", "").trim().ifBlank { null }
      out.add(
        GeniusSongHit(
          id = id,
          title = title,
          artist = artist,
          url = songUrl,
        ),
      )
      if (out.size >= GENIUS_HITS_PER_QUERY) break
    }
    return out
  }

  private fun pickBestGeniusSongHit(
    title: String,
    artist: String,
    hits: List<GeniusSongHit>,
  ): GeniusSongHit? {
    if (hits.isEmpty()) return null
    val targetTitleNorm = normalizeSongTitleForMatch(title)
    val targetArtistNorm = normalizeArtistName(artist)
    return hits.maxByOrNull { hit ->
      val titleScore = titleSimilarityScore(targetTitleNorm, normalizeSongTitleForMatch(hit.title))
      val artistScore =
        if (artistMatchesTarget(hit.artist, targetArtistNorm)) {
          1.0
        } else {
          titleSimilarityScore(targetArtistNorm, normalizeArtistName(hit.artist))
        }
      (titleScore * 0.8) + (artistScore * 0.2)
    } ?: hits.firstOrNull()
  }

  private fun fetchGeniusReferents(songId: Int): JSONArray {
    val url = "$GENIUS_PUBLIC_API_URL/referents?song_id=$songId&per_page=50&text_format=plain"
    val req = Request.Builder().url(url).get().build()
    return songArtistClient.newCall(req).execute().use { res ->
      if (!res.isSuccessful) return JSONArray()
      val payload = runCatching { JSONObject(res.body?.string().orEmpty()) }.getOrNull() ?: return JSONArray()
      payload.optJSONObject("response")?.optJSONArray("referents") ?: JSONArray()
    }
  }

  private fun countUsableGeniusLines(referents: JSONArray): Int {
    val dedupe = LinkedHashSet<String>()
    for (i in 0 until referents.length()) {
      val row = referents.optJSONObject(i) ?: continue
      val fragment = row.optString("fragment", "").trim()
      if (fragment.isBlank()) continue
      val annotations = row.optJSONArray("annotations") ?: JSONArray()
      val hasAnnotation =
        (0 until annotations.length()).any { idx ->
          val ann = annotations.optJSONObject(idx) ?: return@any false
          val body = ann.optJSONObject("body")?.optString("plain", "").orEmpty().trim()
          body.isNotBlank()
        }
      if (!hasAnnotation) continue

      for (rawLine in fragment.split(Regex("\\r?\\n"))) {
        val normalized = sanitizeLyricLineForCoverage(rawLine)
        if (!isUsefulLyricLineForCoverage(normalized)) continue
        dedupe.add(normalized.lowercase())
      }
    }
    return dedupe.size
  }

  private fun sanitizeLyricLineForCoverage(line: String): String {
    var value = line.trim()
    value = value.replace(Regex("^[-*\\d.\\)\\s]+"), "").trim()
    value = value.replace(
      Regex(
        "\\s*\\((?:[Oo]oh|[Oo]h|[Yy]eah|[Aa]h|[Uu]h|[Ww]oo+|[Ll]a+|[Nn]a+)(?:[,\\s]+(?:[Oo]oh|[Oo]h|[Yy]eah|[Aa]h|[Uu]h|[Ww]oo+|[Ll]a+|[Nn]a+|[a-z]+\\s+[a-z]+))*\\)\\s*$",
      ),
      "",
    ).trim()
    value = value.replace(Regex("\\s*\\[(?:x\\d+|repeat.*?)\\]\\s*$", RegexOption.IGNORE_CASE), "").trim()
    value = value.replace(Regex("\\s+"), " ").trim()
    return value
  }

  private fun isUsefulLyricLineForCoverage(line: String): Boolean {
    if (line.isBlank()) return false
    if (line.length > 180) return false
    if (Regex("^\\[[^\\]]+\\]$").matches(line)) return false
    val words = line.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.size < 3) return false
    return true
  }

  private fun normalizeTitleForLyricsLookup(rawTitle: String): String {
    val trimmed = rawTitle.trim()
    if (trimmed.isBlank()) return trimmed
    val stripped =
      trimmed
        .replace(Regex("""\s*\([^)]*\)\s*"""), " ")
        .replace(Regex("""\s*\[[^\]]*]\s*"""), " ")
        .replace(Regex("""\s+-\s+(original version|version|remaster(?:ed)?(?: \d{4})?|live|mono|stereo|radio edit|single version|album version)$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return stripped.ifBlank { trimmed }
  }

  private fun scoreTrackCandidate(
    targetTitleNorm: String,
    targetArtistNorm: String,
    candidate: ArtistTrackRow,
  ): Double {
    val candidateTitleNorm = normalizeSongTitleForMatch(candidate.title)
    if (candidateTitleNorm.isBlank()) return 0.0
    val titleScore = titleSimilarityScore(targetTitleNorm, candidateTitleNorm)
    val artistScore = if (artistMatchesTarget(candidate.artist, targetArtistNorm)) 1.0 else 0.0
    return (titleScore * 0.85) + (artistScore * 0.15)
  }

  private fun titleSimilarityScore(left: String, right: String): Double {
    if (left.isBlank() || right.isBlank()) return 0.0
    if (left == right) return 1.0
    if (left.contains(right) || right.contains(left)) return 0.92

    val leftTokens = left.split(" ").filter { it.length > 1 }.toSet()
    val rightTokens = right.split(" ").filter { it.length > 1 }.toSet()
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0

    val overlap = leftTokens.intersect(rightTokens).size.toDouble()
    val union = leftTokens.union(rightTokens).size.toDouble().coerceAtLeast(1.0)
    val jaccard = overlap / union
    val containment = overlap / minOf(leftTokens.size, rightTokens.size).toDouble().coerceAtLeast(1.0)
    return maxOf(jaccard, containment * 0.9)
  }

  private fun normalizeSongTitleForMatch(raw: String): String {
    return raw
      .lowercase()
      .replace(Regex("\\([^)]*\\)|\\[[^]]*\\]|\\{[^}]*\\}"), " ")
      .replace(Regex("\\b(remaster(?:ed)?|version|edit|mix|live|demo|mono|stereo|explicit|clean)\\b"), " ")
      .replace("&", " and ")
      .replace(Regex("[^a-z0-9]+"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  private fun normalizedApiBaseUrl(): String? {
    val raw = SongPublishService.API_CORE_URL.trim().trimEnd('/')
    if (raw.startsWith("https://") || raw.startsWith("http://")) return raw
    return null
  }
}
