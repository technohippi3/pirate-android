package com.pirate.app.music

/**
 * Process-lifetime cache for the "Shared With You" feature.
 *
 * This prevents loader flicker when navigating away/back to the music screen by keeping the
 * most recent fetched payloads in memory.
 *
 * Note: This is intentionally small + best-effort. A future cleanup would move this into a
 * dedicated ViewModel/repository layer.
 */
object SharedWithYouCache {
  @Volatile
  var lastFetchAtMs: Long = 0L

  @Volatile
  var playlists: List<PlaylistShareEntry> = emptyList()

  @Volatile
  var tracks: List<SharedCloudTrack> = emptyList()

  private val playlistTracksByKey = HashMap<String, Pair<Long, List<SharedCloudTrack>>>()

  @Synchronized
  fun getPlaylistTracks(key: String): Pair<Long, List<SharedCloudTrack>>? {
    return playlistTracksByKey[key]
  }

  @Synchronized
  fun putPlaylistTracks(key: String, atMs: Long, tracks: List<SharedCloudTrack>) {
    playlistTracksByKey[key] = Pair(atMs, tracks)
  }
}

