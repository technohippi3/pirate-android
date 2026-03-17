package com.pirate.app.music

internal const val MUSIC_LIBRARY_SILENT_SCAN_TTL_MS = 120_000L
internal const val MUSIC_PLAYLISTS_REFRESH_TTL_MS = 120_000L
internal const val MUSIC_PURCHASED_LIBRARY_REFRESH_TTL_MS = 120_000L

/**
 * Process-lifetime cache for the music route.
 *
 * Compose navigation disposes the `MusicScreen` destination while deeper routes are open, so
 * plain `remember` state gets rebuilt on every return. Keeping the latest resolved payloads here
 * lets the route restore instantly and only refresh when the data is stale.
 */
internal object MusicScreenCache {
  @Volatile
  var recentPublishedReleases: List<AlbumCardModel> = emptyList()

  @Volatile
  var recentPublishedReleasesError: String? = null

  @Volatile
  var recentPublishedLastFetchAtMs: Long = 0L

  @Volatile
  var liveRooms: List<LiveRoomCardModel> = emptyList()

  @Volatile
  var liveRoomsError: String? = null

  @Volatile
  var liveRoomsLastFetchAtMs: Long = 0L

  @Volatile
  var tracks: List<MusicTrack> = emptyList()

  @Volatile
  var libraryError: String? = null

  @Volatile
  var lastLibraryScanAtMs: Long = 0L

  @Volatile
  var downloadedTracksByContentId: Map<String, DownloadedTrackEntry> = emptyMap()

  @Volatile
  var localPlaylists: List<LocalPlaylist> = emptyList()

  @Volatile
  var onChainPlaylists: List<OnChainPlaylist> = emptyList()

  @Volatile
  var lastPlaylistsLoadAtMs: Long = 0L

  @Volatile
  var optimisticOnChainTrackCounts: Map<String, Int> = emptyMap()

  @Volatile
  var purchasedCloudTracks: List<MusicTrack> = emptyList()

  @Volatile
  var lastPurchasedLibraryLoadAtMs: Long = 0L

  @Volatile
  var sharedOwnerLabels: Map<String, String> = emptyMap()

  @Volatile
  var sharedSeenItemIds: Set<String> = emptySet()

  @Volatile
  var sharedSelectedPlaylist: PlaylistShareEntry? = null

  @Volatile
  var sharedPlaylistKey: String? = null

  @Volatile
  var sharedPlaylistError: String? = null

  private val playlistDetailById = HashMap<String, PlaylistDetailLoadResult>()

  @Synchronized
  fun getPlaylistDetail(playlistId: String?): PlaylistDetailLoadResult? {
    val key = playlistId?.trim().orEmpty()
    if (key.isBlank()) return null
    return playlistDetailById[key]
  }

  @Synchronized
  fun putPlaylistDetail(
    playlistId: String,
    result: PlaylistDetailLoadResult,
  ) {
    val key = playlistId.trim()
    if (key.isBlank()) return
    playlistDetailById[key] = result
  }

  @Synchronized
  fun removePlaylistDetail(playlistId: String?) {
    val key = playlistId?.trim().orEmpty()
    if (key.isBlank()) return
    playlistDetailById.remove(key)
  }

  fun shouldRefreshLibrary(
    hasData: Boolean,
    nowMs: Long = System.currentTimeMillis(),
  ): Boolean {
    if (!hasData) return true
    if (lastLibraryScanAtMs == 0L) return true
    return nowMs - lastLibraryScanAtMs > MUSIC_LIBRARY_SILENT_SCAN_TTL_MS
  }

  fun shouldRefreshPlaylists(
    hasData: Boolean,
    nowMs: Long = System.currentTimeMillis(),
  ): Boolean {
    if (!hasData) return true
    if (lastPlaylistsLoadAtMs == 0L) return true
    return nowMs - lastPlaylistsLoadAtMs > MUSIC_PLAYLISTS_REFRESH_TTL_MS
  }

  fun shouldRefreshPurchasedLibrary(
    hasData: Boolean,
    nowMs: Long = System.currentTimeMillis(),
  ): Boolean {
    if (!hasData) return true
    if (lastPurchasedLibraryLoadAtMs == 0L) return true
    return nowMs - lastPurchasedLibraryLoadAtMs > MUSIC_PURCHASED_LIBRARY_REFRESH_TTL_MS
  }
}
