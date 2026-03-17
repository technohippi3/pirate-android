package com.pirate.app.music

import android.os.SystemClock

internal data class SharedLibraryFetchResult(
  val playlists: List<PlaylistShareEntry>,
  val tracks: List<SharedCloudTrack>,
  val error: String?,
)

internal data class SharedPlaylistTracksFetchResult(
  val tracks: List<SharedCloudTrack>,
  val error: String?,
)

internal fun shouldRefreshSharedLibrary(
  hasData: Boolean,
  lastFetchAtMs: Long,
  nowMs: Long,
  ttlMs: Long,
): Boolean {
  return !hasData || lastFetchAtMs == 0L || (nowMs - lastFetchAtMs > ttlMs)
}

internal suspend fun fetchSharedLibrary(ownerEthAddress: String): SharedLibraryFetchResult {
  var error: String? = null
  val playlists =
    runCatching { SharedWithYouApi.fetchSharedPlaylists(ownerEthAddress) }
      .getOrElse { err ->
        error = err.message ?: "Failed to load shared playlists"
        emptyList()
      }
  val tracks =
    runCatching { SharedWithYouApi.fetchSharedTracks(ownerEthAddress) }
      .getOrElse { err ->
        if (error == null) error = err.message ?: "Failed to load shared tracks"
        emptyList()
      }
  return SharedLibraryFetchResult(
    playlists = playlists,
    tracks = tracks,
    error = error,
  )
}

internal fun sharedPlaylistCacheKey(share: PlaylistShareEntry): String {
  return "${share.id}:${share.playlistVersion}:${share.tracksHash}".lowercase()
}

internal fun shouldRefreshSharedPlaylistTracks(
  cached: Pair<Long, List<SharedCloudTrack>>?,
  force: Boolean,
  ttlMs: Long,
): Boolean {
  if (force) return true
  val now = SystemClock.elapsedRealtime()
  val hasData = cached?.second?.isNotEmpty() == true
  val cachedAt = cached?.first ?: 0L
  val stale = !hasData || cachedAt == 0L || (now - cachedAt > ttlMs)
  val hasMissingPointers =
    cached?.second?.any { track -> track.contentId.isBlank() || track.pieceCid.isBlank() } == true
  return stale || hasMissingPointers
}

internal suspend fun fetchSharedPlaylistTracks(
  share: PlaylistShareEntry,
): SharedPlaylistTracksFetchResult {
  val tracks =
    runCatching { SharedWithYouApi.fetchSharedPlaylistTracks(share) }
      .getOrElse { err ->
        return SharedPlaylistTracksFetchResult(
          tracks = emptyList(),
          error = err.message ?: "Failed to load playlist",
        )
      }
  return SharedPlaylistTracksFetchResult(
    tracks = tracks,
    error = null,
  )
}
