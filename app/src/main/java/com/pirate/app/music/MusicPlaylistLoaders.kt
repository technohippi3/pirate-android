package com.pirate.app.music

import android.content.Context

internal data class PlaylistsLoadResult(
  val localPlaylists: List<LocalPlaylist>,
  val onChainPlaylists: List<OnChainPlaylist>,
)

internal data class PlaylistDetailLoadResult(
  val tracks: List<MusicTrack>,
  val error: String?,
)

internal suspend fun loadPlaylistsData(
  context: Context,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
): PlaylistsLoadResult {
  val local = runCatching { LocalPlaylistsStore.getLocalPlaylists(context) }.getOrElse { emptyList() }
  val onChain =
    if (ONCHAIN_PLAYLISTS_ENABLED && isAuthenticated && !ownerEthAddress.isNullOrBlank()) {
      runCatching { OnChainPlaylistsApi.fetchUserPlaylists(ownerEthAddress) }.getOrElse { emptyList() }
    } else {
      emptyList()
    }
  return PlaylistsLoadResult(
    localPlaylists = local,
    onChainPlaylists = onChain,
  )
}

internal suspend fun loadPlaylistDetailTracks(
  playlist: PlaylistDisplayItem,
  localPlaylists: List<LocalPlaylist>,
  libraryTracks: List<MusicTrack>,
): PlaylistDetailLoadResult {
  return runCatching {
    if (playlist.isLocal) {
      val local = localPlaylists.firstOrNull { it.id == playlist.id }
        ?: return PlaylistDetailLoadResult(
          tracks = emptyList(),
          error = "Playlist not found",
        )
      val fallbackArt = playlist.coverUri
      val tracks =
        local.tracks.mapIndexed { idx, track ->
          val stable = track.uri ?: "${track.artist}-${track.title}-${idx}"
          MusicTrack(
            id = "localpl:${local.id}:$stable",
            title = track.title.ifBlank { "Track ${idx + 1}" },
            artist = track.artist.ifBlank { "Unknown Artist" },
            album = track.album.orEmpty(),
            durationSec = track.durationSec ?: 0,
            uri = track.uri.orEmpty(),
            filename = track.title.ifBlank { "track-${idx + 1}" },
            artworkUri = track.artworkUri ?: fallbackArt,
            artworkFallbackUri = track.artworkFallbackUri,
          )
        }
      PlaylistDetailLoadResult(tracks = tracks, error = null)
    } else {
      if (!ONCHAIN_PLAYLISTS_ENABLED) {
        return PlaylistDetailLoadResult(
          tracks = emptyList(),
          error = "Playlist not found",
        )
      }
      val trackIds = OnChainPlaylistsApi.fetchPlaylistTrackIds(playlist.id)
      val normalizedTrackIds =
        trackIds
          .map { it.trim().lowercase() }
          .filter { it.isNotBlank() }
      val metaByTrackId = runCatching { fetchTrackMeta(normalizedTrackIds) }.getOrElse { emptyMap() }
      val byContentId =
        libraryTracks
          .mapNotNull { track ->
            val key = track.contentId?.trim()?.lowercase().orEmpty()
            if (key.isBlank()) null else key to track
          }
          .toMap()
      val byTrackId = HashMap<String, MusicTrack>(libraryTracks.size * 2)
      for (track in libraryTracks) {
        val directId = track.id.trim().lowercase()
        if (directId.isNotBlank()) {
          byTrackId.putIfAbsent(directId, track)
        }
        // Keep metadata-derived IDs as a fallback for legacy/offline rows that may not carry
        // canonical track IDs through every path yet.
        val metaTrackId =
          runCatching {
            TrackIds.computeMetaTrackId(
              title = track.title,
              artist = track.artist,
              album = track.album.ifBlank { null },
            ).trim().lowercase()
          }.getOrNull().orEmpty()
        if (metaTrackId.isNotBlank()) {
          byTrackId.putIfAbsent(metaTrackId, track)
        }
      }
      val fallbackArt = playlist.coverUri
      val tracks =
        trackIds.mapIndexed { idx, trackId ->
          val key = trackId.trim().lowercase()
          val match = byContentId[key] ?: byTrackId[key]
          if (match != null) {
            match.copy(
              id = "onchain:${playlist.id}:$idx:${match.id}",
              canonicalTrackId = match.canonicalTrackId ?: key.ifBlank { null },
              artworkUri = match.artworkUri ?: fallbackArt,
              lyricsRef = match.lyricsRef ?: metaByTrackId[key]?.lyricsRef,
            )
          } else {
            val meta = metaByTrackId[key]
            val coverUri =
              CoverRef.resolveCoverUrl(meta?.coverCid, width = 192, height = 192, format = "webp", quality = 80)
            val resolvedTitle = meta?.title?.trim().orEmpty()
            val resolvedArtist = meta?.artist?.trim().orEmpty()
            val resolvedAlbum = meta?.album?.trim().orEmpty()
            MusicTrack(
              id = "onchain:${playlist.id}:$idx:$key",
              canonicalTrackId = key.ifBlank { null },
              title = resolvedTitle.ifBlank { "Track ${idx + 1}" },
              artist = resolvedArtist.ifBlank { "Unknown Artist" },
              album = resolvedAlbum.ifBlank { playlist.name },
              durationSec = meta?.durationSec?.coerceAtLeast(0) ?: 0,
              uri = "",
              filename = resolvedTitle.ifBlank { key.ifBlank { "track-${idx + 1}" } },
              artworkUri = coverUri ?: fallbackArt,
              contentId = key.ifBlank { null },
              lyricsRef = meta?.lyricsRef,
            )
          }
        }
      PlaylistDetailLoadResult(tracks = tracks, error = null)
    }
  }.getOrElse { err ->
    PlaylistDetailLoadResult(
      tracks = emptyList(),
      error = err.message ?: "Failed to load playlist",
    )
  }
}

internal fun toDisplayItems(
  localPlaylists: List<LocalPlaylist>,
  onChainPlaylists: List<OnChainPlaylist>,
): List<PlaylistDisplayItem> {
  val out = ArrayList<PlaylistDisplayItem>(localPlaylists.size + onChainPlaylists.size)
  for (playlist in localPlaylists) {
    out.add(
      PlaylistDisplayItem(
        id = playlist.id,
        name = playlist.name,
        trackCount = playlist.tracks.size,
        coverUri = playlist.coverUri ?: playlist.tracks.firstOrNull()?.artworkUri,
        isLocal = true,
      ),
    )
  }
  for (playlist in onChainPlaylists) {
    out.add(
      PlaylistDisplayItem(
        id = playlist.id,
        name = playlist.name,
        trackCount = playlist.trackCount,
        coverUri = CoverRef.resolveCoverUrl(playlist.coverCid, width = 140, height = 140, format = "webp", quality = 80),
        isLocal = false,
        version = playlist.version,
        tracksHash = playlist.tracksHash,
      ),
    )
  }
  return out
}
