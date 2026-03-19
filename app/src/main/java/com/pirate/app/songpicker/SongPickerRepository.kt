package com.pirate.app.songpicker

import android.content.Context
import com.pirate.app.learn.RecentStudySetCatalogStore
import com.pirate.app.music.TrackPreviewHistoryStore
import com.pirate.app.music.fetchPurchasedCloudLibraryTracks
import com.pirate.app.profile.ProfileMusicApi
import com.pirate.app.profile.ProfileScrobbleApi
import com.pirate.app.tempo.TempoClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SongPickerRepository {
  fun cachedSuggestedSongs(
    ownerAddress: String? = null,
    maxEntries: Int = 100,
  ): List<SongPickerSong> = emptyList()

  suspend fun suggestedSongs(
    context: Context,
    ownerAddress: String? = null,
    maxEntries: Int = 100,
  ): List<SongPickerSong>

  suspend fun searchPublishedSongs(
    context: Context,
    ownerAddress: String? = null,
    query: String,
    maxEntries: Int = 100,
  ): List<SongPickerSong>
}

object DefaultSongPickerRepository : SongPickerRepository {
  private const val SONG_CACHE_TTL_MS = 60_000L
  private const val PERSONALIZED_CANDIDATE_LIMIT = 36
  @Volatile private var suggestedSongsCacheKey: String = ""
  @Volatile private var suggestedSongsCache: List<SongPickerSong> = emptyList()
  @Volatile private var suggestedSongsFetchedAtMs: Long = 0L
  @Volatile private var latestSongsCache: List<SongPickerSong> = emptyList()
  @Volatile private var latestSongsFetchedAtMs: Long = 0L

  fun invalidateSuggestedSongsCache() {
    suggestedSongsCacheKey = ""
    suggestedSongsCache = emptyList()
    suggestedSongsFetchedAtMs = 0L
  }

  override fun cachedSuggestedSongs(ownerAddress: String?, maxEntries: Int): List<SongPickerSong> {
    val cacheKey = normalizeOwnerAddress(ownerAddress)
    return if (suggestedSongsCacheKey == cacheKey) suggestedSongsCache.take(maxEntries) else emptyList()
  }

  override suspend fun suggestedSongs(
    context: Context,
    ownerAddress: String?,
    maxEntries: Int,
  ): List<SongPickerSong> =
    withContext(Dispatchers.IO) {
      suggestedSongsCached(
        context = context,
        ownerAddress = ownerAddress,
        maxEntries = maxEntries,
      )
    }

  override suspend fun searchPublishedSongs(
    context: Context,
    ownerAddress: String?,
    query: String,
    maxEntries: Int,
  ): List<SongPickerSong> {
    val normalized = query.trim().lowercase()
    if (normalized.isBlank()) {
      return suggestedSongs(
        context = context,
        ownerAddress = ownerAddress,
        maxEntries = maxEntries,
      )
    }

    val personalized =
      searchSongs(
        songs = suggestedSongsCached(
          context = context,
          ownerAddress = ownerAddress,
          maxEntries = maxOf(PERSONALIZED_CANDIDATE_LIMIT, maxEntries),
        ),
        query = normalized,
        maxEntries = maxEntries,
      )
    if (personalized.size >= maxEntries || normalized.length < 2) return personalized

    val discovery =
      searchSongs(
        songs = latestPublishedSongsCached(maxEntries = maxOf(80, maxEntries * 2)),
        query = normalized,
        maxEntries = maxEntries * 2,
      )
    if (discovery.isEmpty()) return personalized

    val merged = LinkedHashMap<String, SongPickerSong>(personalized.size + discovery.size)
    personalized.forEach { merged[it.trackId] = it }
    discovery.forEach { if (!merged.containsKey(it.trackId)) merged[it.trackId] = it }
    return merged.values.take(maxEntries).toList()
  }

  suspend fun preloadSuggestedSongs(
    context: Context,
    ownerAddress: String? = null,
    maxEntries: Int = 24,
  ) {
    withContext(Dispatchers.IO) {
      suggestedSongsCached(
        context = context,
        ownerAddress = ownerAddress,
        maxEntries = maxEntries,
      )
    }
  }

  private fun normalizeOwnerAddress(ownerAddress: String?): String = ownerAddress?.trim()?.lowercase().orEmpty()

  private fun searchSongs(
    songs: List<SongPickerSong>,
    query: String,
    maxEntries: Int,
  ): List<SongPickerSong> {
    return songs
      .asSequence()
      .filter { song ->
        song.title.lowercase().contains(query) || song.artist.lowercase().contains(query)
      }
      .take(maxEntries)
      .toList()
  }

  private suspend fun suggestedSongsCached(
    context: Context,
    ownerAddress: String?,
    maxEntries: Int,
  ): List<SongPickerSong> {
    val cacheKey = normalizeOwnerAddress(ownerAddress)
    val now = System.currentTimeMillis()
    val cached = suggestedSongsCache
    val cacheFresh = suggestedSongsCacheKey == cacheKey && (now - suggestedSongsFetchedAtMs) < SONG_CACHE_TTL_MS
    if (cacheFresh && cached.size >= maxEntries) return cached.take(maxEntries)

    val fetched =
      fetchSuggestedSongs(
        context = context,
        ownerAddress = ownerAddress,
        maxEntries = maxOf(maxEntries, PERSONALIZED_CANDIDATE_LIMIT),
      )
    suggestedSongsCacheKey = cacheKey
    suggestedSongsCache = fetched
    suggestedSongsFetchedAtMs = now
    return fetched.take(maxEntries)
  }

  private suspend fun latestPublishedSongsCached(maxEntries: Int): List<SongPickerSong> {
    val now = System.currentTimeMillis()
    val cached = latestSongsCache
    val cacheFresh = (now - latestSongsFetchedAtMs) < SONG_CACHE_TTL_MS
    if (cacheFresh && cached.size >= maxEntries) return cached.take(maxEntries)

    val fetched = fetchLatestPublishedSongs(maxEntries = maxOf(maxEntries, 80))
    latestSongsCache = fetched
    latestSongsFetchedAtMs = now
    return fetched.take(maxEntries)
  }

  private suspend fun fetchSuggestedSongs(
    context: Context,
    ownerAddress: String?,
    maxEntries: Int,
  ): List<SongPickerSong> = coroutineScope {
    data class CandidateSong(
      val trackId: String,
      val title: String,
      val artist: String,
      val coverCid: String? = null,
      val durationSec: Int = 0,
      val pieceCid: String? = null,
      val score: Int = 0,
      val recencySec: Long = 0L,
    )

    fun mergeCandidate(
      sink: LinkedHashMap<String, CandidateSong>,
      candidate: CandidateSong,
    ) {
      val normalizedTrackId = candidate.trackId.trim().lowercase()
      if (normalizedTrackId.isBlank()) return
      val incoming = candidate.copy(
        trackId = normalizedTrackId,
        title = candidate.title.ifBlank { normalizedTrackId.take(14) },
        artist = candidate.artist.ifBlank { "Unknown Artist" },
      )
      val existing = sink[normalizedTrackId]
      if (existing == null) {
        sink[normalizedTrackId] = incoming
        return
      }
      sink[normalizedTrackId] =
        CandidateSong(
          trackId = normalizedTrackId,
          title = incoming.title.takeIf { it.isNotBlank() } ?: existing.title,
          artist = incoming.artist.takeIf { it.isNotBlank() } ?: existing.artist,
          coverCid = existing.coverCid ?: incoming.coverCid,
          durationSec = maxOf(existing.durationSec, incoming.durationSec),
          pieceCid = existing.pieceCid ?: incoming.pieceCid,
          score = existing.score + incoming.score,
          recencySec = maxOf(existing.recencySec, incoming.recencySec),
        )
    }

    val normalizedOwner = normalizeOwnerAddress(ownerAddress).ifBlank { null }
    val studyEntries =
      normalizedOwner?.let {
        runCatching { RecentStudySetCatalogStore.listForUser(context, it) }.getOrDefault(emptyList())
      }.orEmpty()
    val previewEntries = TrackPreviewHistoryStore.listRecent(context, limit = 16)
    val scrobblesDeferred =
      async {
        normalizedOwner?.let {
          runCatching { ProfileScrobbleApi.fetchScrobbles(userAddress = it, max = 24) }.getOrDefault(emptyList())
        }.orEmpty()
      }
    val purchasesDeferred =
      async {
        normalizedOwner?.let {
          runCatching { fetchPurchasedCloudLibraryTracks(ownerEthAddress = it, limit = 48) }.getOrDefault(emptyList())
        }.orEmpty()
      }

    val nowSec = System.currentTimeMillis() / 1000L
    val candidates = LinkedHashMap<String, CandidateSong>()

    previewEntries.forEachIndexed { index, preview ->
      mergeCandidate(
        candidates,
        CandidateSong(
          trackId = preview.trackId,
          title = preview.title,
          artist = preview.artist,
          score = 80 - index * 3 + (preview.previewCount.coerceAtMost(10) * 4),
          recencySec = preview.lastPreviewAtSec,
        ),
      )
    }

    studyEntries.forEachIndexed { index, (_, entry) ->
      val trackId = entry.trackId?.trim()?.lowercase().orEmpty()
      if (trackId.isBlank()) return@forEachIndexed
      mergeCandidate(
        candidates,
        CandidateSong(
          trackId = trackId,
          title = entry.title,
          artist = entry.artist,
          score = 70 - index * 2 + entry.totalAttempts.coerceAtMost(20) + (entry.streakDays.coerceAtMost(14) * 2),
          recencySec = nowSec - index.toLong(),
        ),
      )
    }

    purchasesDeferred.await().forEachIndexed { index, track ->
      val trackId = track.canonicalTrackId?.trim()?.lowercase().orEmpty()
      if (trackId.isBlank()) return@forEachIndexed
      mergeCandidate(
        candidates,
        CandidateSong(
          trackId = trackId,
          title = track.title,
          artist = track.artist,
          score = 60 - index,
          recencySec = nowSec - (index.toLong() * 60L),
        ),
      )
    }

    scrobblesDeferred.await().forEachIndexed { index, scrobble ->
      val trackId = scrobble.trackId?.trim()?.lowercase().orEmpty()
      if (trackId.isBlank()) return@forEachIndexed
      mergeCandidate(
        candidates,
        CandidateSong(
          trackId = trackId,
          title = scrobble.title,
          artist = scrobble.artist,
          score = 55 - index,
          recencySec = scrobble.playedAtSec,
        ),
      )
    }

    val prioritizedCandidates =
      candidates.values
        .sortedWith(
          compareByDescending<CandidateSong> { it.score }
            .thenByDescending { it.recencySec }
            .thenBy { it.trackId },
        ).take(maxOf(maxEntries * 2, PERSONALIZED_CANDIDATE_LIMIT))

    if (prioritizedCandidates.isEmpty()) {
      return@coroutineScope latestPublishedSongsCached(maxEntries = maxEntries)
    }

    val trackIds = prioritizedCandidates.map { it.trackId }
    val termsByTrack =
      runCatching { TempoClient.fetchSongTermsBatch(trackIds) }
        .getOrDefault(emptyMap<String, TempoClient.SongTermsResponse>())
    val suggested =
      prioritizedCandidates.mapNotNull { candidate ->
        val terms = termsByTrack[candidate.trackId]
        val remixable = terms?.remixable == true
        if (!remixable) return@mapNotNull null
        SongPickerSong(
          trackId = candidate.trackId,
          songStoryIpId = null,
          title = candidate.title,
          artist = candidate.artist,
          coverCid = candidate.coverCid,
          durationSec = candidate.durationSec,
          pieceCid = candidate.pieceCid,
          commercialUse = terms?.commercialUse ?: true,
          commercialRevSharePpm8 = terms?.commercialRevSharePpm8 ?: 0,
          approvalMode = terms?.approvalMode ?: "auto",
          approvalSlaSec = terms?.approvalSlaSec ?: 259200,
          remixable = remixable,
        )
      }
    if (suggested.isEmpty()) {
      return@coroutineScope latestPublishedSongsCached(maxEntries = maxEntries)
    }

    val merged = LinkedHashMap<String, SongPickerSong>(maxEntries)
    suggested.forEach { merged[it.trackId] = it }
    if (merged.size < maxEntries) {
      latestPublishedSongsCached(maxEntries = maxEntries).forEach { song ->
        if (!merged.containsKey(song.trackId)) {
          merged[song.trackId] = song
        }
      }
    }
    merged.values.take(maxEntries).toList()
  }

  private suspend fun fetchLatestPublishedSongs(maxEntries: Int): List<SongPickerSong> {
    val rows = ProfileMusicApi.fetchLatestPublishedSongs(maxEntries = maxEntries)
    val trackIds = rows.map { it.trackId.trim().lowercase() }.filter { it.isNotBlank() }
    val termsByTrack =
      runCatching { TempoClient.fetchSongTermsBatch(trackIds) }
        .getOrDefault(emptyMap<String, TempoClient.SongTermsResponse>())
    return rows.mapNotNull { row ->
      val normalizedTrackId = row.trackId.trim().lowercase()
      val terms = termsByTrack[normalizedTrackId]
      val remixable = terms?.remixable == true
      if (!remixable) return@mapNotNull null
      SongPickerSong(
        trackId = normalizedTrackId,
        songStoryIpId = null,
        title = row.title,
        artist = row.artist,
        coverCid = row.coverCid,
        durationSec = row.durationSec,
        pieceCid = row.pieceCid,
        commercialUse = terms?.commercialUse ?: true,
        commercialRevSharePpm8 = terms?.commercialRevSharePpm8 ?: 0,
        approvalMode = terms?.approvalMode ?: "auto",
        approvalSlaSec = terms?.approvalSlaSec ?: 259200,
        remixable = remixable,
      )
    }
  }
}
