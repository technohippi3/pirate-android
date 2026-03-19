package sc.pirate.app.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import sc.pirate.app.arweave.ArweaveTurboConfig
import sc.pirate.app.player.PlayerController
import sc.pirate.app.songpicker.DefaultSongPickerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PLAYLIST_CREATE_APPEARANCE_RETRIES = 30
private const val PLAYLIST_CREATE_APPEARANCE_DELAY_MS = 3_000L

internal fun openTurboTopUpUrl(
  context: Context,
  onShowMessage: (String) -> Unit,
) {
  val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ArweaveTurboConfig.TOP_UP_URL))
  runCatching { context.startActivity(intent) }
    .onFailure {
      onShowMessage("Unable to open browser. Visit ${ArweaveTurboConfig.TOP_UP_URL}")
    }
}

private suspend fun resolveDownloadedEntryByKeys(
  context: Context,
  downloadedEntries: Map<String, DownloadedTrackEntry>,
  lookupKeys: List<String>,
): Pair<DownloadedTrackEntry?, Map<String, DownloadedTrackEntry>> {
  if (lookupKeys.isEmpty()) return Pair(null, downloadedEntries)
  var updatedEntries = downloadedEntries
  var changed = false
  for (key in lookupKeys) {
    val normalizedKey = key.trim().lowercase()
    if (normalizedKey.isBlank()) continue
    val entry = updatedEntries[normalizedKey] ?: continue
    val exists = runCatching { MediaStoreAudioDownloads.uriExists(context, entry.mediaUri) }.getOrDefault(false)
    if (exists) return Pair(entry, updatedEntries)
    updatedEntries = DownloadedTracksStore.remove(context, normalizedKey)
    changed = true
  }
  return Pair(null, if (changed) updatedEntries else downloadedEntries)
}

internal suspend fun playNewReleaseWithUi(
  context: Context,
  release: AlbumCardModel,
  player: PlayerController,
  ownerEthAddress: String?,
  purchaseIdByCanonicalTrackId: Map<String, String>,
  downloadedTracksByContentId: Map<String, DownloadedTrackEntry>,
  onSetDownloadedTracksByContentId: (Map<String, DownloadedTrackEntry>) -> Unit,
  onOpenPlayer: () -> Unit,
  onShowMessage: (String) -> Unit,
  hostActivity: androidx.fragment.app.FragmentActivity? = null,
  tempoAccount: sc.pirate.app.tempo.TempoPasskeyManager.PasskeyAccount? = null,
) {
  val canonicalTrackId = CanonicalTrackId.parse(release.trackId)?.value
  val normalizedCanonicalTrackId = canonicalTrackId?.lowercase()
  val normalizedContentId = release.contentId?.trim()?.lowercase()?.ifBlank { null }
  val stableTrackId = normalizedCanonicalTrackId ?: "release:${release.trackId ?: "${release.title}-${release.artist}"}"
  val coverUrl = resolvePlaybackCoverUrl(release.coverRef)
  val purchaseRef =
    normalizedCanonicalTrackId?.let { canonicalId ->
      purchaseIdByCanonicalTrackId[canonicalId]?.trim()?.ifBlank { null }?.let { canonicalId to it }
    }
  val purchaseId = purchaseRef?.second

  val localLookupKeys = LinkedHashSet<String>()
  normalizedContentId?.let { localLookupKeys.add(it) }
  if (purchaseId != null) {
    localLookupKeys.add("purchase:${purchaseId.lowercase()}")
  }
  val (localEntry, updatedEntries) =
    resolveDownloadedEntryByKeys(
      context = context,
      downloadedEntries = downloadedTracksByContentId,
      lookupKeys = localLookupKeys.toList(),
    )
  if (updatedEntries !== downloadedTracksByContentId) {
    onSetDownloadedTracksByContentId(updatedEntries)
  }
  if (localEntry != null) {
    val localTrack =
      MusicTrack(
        id = stableTrackId,
        canonicalTrackId = canonicalTrackId,
        title = release.title,
        artist = release.artist,
        album = "",
        durationSec = 0,
        uri = localEntry.mediaUri,
        filename = localEntry.filename.ifBlank { "${release.title}.mp3" },
        artworkUri = coverUrl,
        artworkFallbackUri = release.coverRef,
        contentId = normalizedContentId,
        lyricsRef = release.lyricsRef,
        purchaseId = purchaseId,
        isCloudOnly = true,
        isPreviewOnly = false,
      )
    player.playTrack(localTrack, listOf(localTrack))
    onOpenPlayer()
    return
  }

  if (purchaseRef != null) {
    val (purchaseTrackId, resolvedPurchaseId) = purchaseRef
    val owner = ownerEthAddress?.trim().orEmpty()
    if (owner.isBlank()) {
      onShowMessage("Sign in to play purchased songs")
      return
    }
    onShowMessage("Downloading purchased song...")
    val downloadSourceTrack =
      MusicTrack(
        id = stableTrackId,
        canonicalTrackId = canonicalTrackId,
        title = release.title,
        artist = release.artist,
        album = "",
        durationSec = 0,
        uri = "",
        filename = "${release.title}.mp3",
        artworkUri = coverUrl,
        artworkFallbackUri = release.coverRef,
        contentId = normalizedContentId,
        lyricsRef = release.lyricsRef,
        purchaseId = purchaseId,
        isCloudOnly = true,
        isPreviewOnly = false,
      )
    val downloadResult =
      downloadPurchasedTrackToDevice(
        context = context,
        track = downloadSourceTrack,
        ownerEthAddress = owner,
        purchaseIdsByTrackId = mapOf(purchaseTrackId to resolvedPurchaseId),
        activity = hostActivity,
        tempoAccount = tempoAccount,
      )
    if (!downloadResult.success || downloadResult.mediaUri.isNullOrBlank()) {
      onShowMessage("Download failed: ${downloadResult.error ?: "unknown error"}")
      return
    }
    onSetDownloadedTracksByContentId(DownloadedTracksStore.load(context))
    val localTrack =
      downloadSourceTrack.copy(
        uri = downloadResult.mediaUri,
        isPreviewOnly = false,
      )
    player.playTrack(localTrack, listOf(localTrack))
    onOpenPlayer()
    return
  }

  val previewUrl = resolveTrackPreviewUrl(canonicalTrackId ?: release.trackId)
  if (previewUrl.isNullOrBlank()) {
    onShowMessage("This release is not playable yet")
    return
  }
  val previewTrack =
    MusicTrack(
      id = stableTrackId,
      canonicalTrackId = canonicalTrackId,
      title = release.title,
      artist = release.artist,
      album = "",
      durationSec = 0,
      uri = previewUrl,
      filename = "${release.title}.mp3",
      artworkUri = coverUrl,
      artworkFallbackUri = release.coverRef,
      contentId = normalizedContentId,
      lyricsRef = release.lyricsRef,
      isCloudOnly = true,
      isPreviewOnly = true,
    )
  (canonicalTrackId ?: release.trackId)?.let { trackId ->
    TrackPreviewHistoryStore.recordPreview(
      context = context,
      trackId = trackId,
      title = release.title,
      artist = release.artist,
    )
    DefaultSongPickerRepository.invalidateSuggestedSongsCache()
  }
  player.playTrack(previewTrack, listOf(previewTrack))
  onOpenPlayer()
}

internal suspend fun refreshSharedLibraryWithUi(
  force: Boolean,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
  sharedLoading: Boolean,
  sharedPlaylists: List<PlaylistShareEntry>,
  sharedTracks: List<SharedCloudTrack>,
  sharedLastFetchAtMs: Long,
  ttlMs: Long,
  onSetSharedError: (String?) -> Unit,
  onSetSharedLoading: (Boolean) -> Unit,
  onSetSharedPlaylists: (List<PlaylistShareEntry>) -> Unit,
  onSetSharedTracks: (List<SharedCloudTrack>) -> Unit,
  onSetSharedLastFetchAtMs: (Long) -> Unit,
) {
  onSetSharedError(null)
  if (!isAuthenticated || ownerEthAddress.isNullOrBlank()) {
    onSetSharedPlaylists(emptyList())
    onSetSharedTracks(emptyList())
    onSetSharedLastFetchAtMs(0L)
    SharedWithYouCache.playlists = emptyList()
    SharedWithYouCache.tracks = emptyList()
    SharedWithYouCache.lastFetchAtMs = 0L
    return
  }
  if (sharedLoading) return

  val now = SystemClock.elapsedRealtime()
  val hasData = sharedPlaylists.isNotEmpty() || sharedTracks.isNotEmpty()
  val shouldRefresh =
    shouldRefreshSharedLibrary(
      hasData = hasData,
      lastFetchAtMs = sharedLastFetchAtMs,
      nowMs = now,
      ttlMs = ttlMs,
    )
  if (!force && !shouldRefresh) return

  // Avoid flashing loaders when navigating between tabs; keep stale data visible while refreshing.
  onSetSharedLoading(!hasData)
  try {
    val fetched = fetchSharedLibrary(ownerEthAddress)
    onSetSharedError(fetched.error)
    onSetSharedPlaylists(fetched.playlists)
    onSetSharedTracks(fetched.tracks)
    onSetSharedLastFetchAtMs(now)
    SharedWithYouCache.playlists = fetched.playlists
    SharedWithYouCache.tracks = fetched.tracks
    SharedWithYouCache.lastFetchAtMs = now
  } finally {
    onSetSharedLoading(false)
  }
}

internal suspend fun refreshSharedPlaylistTracksWithUi(
  share: PlaylistShareEntry,
  force: Boolean,
  sharedPlaylistLoading: Boolean,
  sharedPlaylistRefreshing: Boolean,
  ttlMs: Long,
  currentSharedPlaylistKey: () -> String?,
  onSetSharedPlaylistError: (String?) -> Unit,
  onSetSharedPlaylistKey: (String?) -> Unit,
  onSetSharedPlaylistTracks: (List<SharedCloudTrack>) -> Unit,
  onSetSharedPlaylistLoading: (Boolean) -> Unit,
  onSetSharedPlaylistRefreshing: (Boolean) -> Unit,
) {
  onSetSharedPlaylistError(null)

  val key = sharedPlaylistCacheKey(share)
  onSetSharedPlaylistKey(key)

  val cached = SharedWithYouCache.getPlaylistTracks(key)
  // Show cached tracks instantly; otherwise clear to avoid showing stale tracks from a
  // previously-opened playlist.
  onSetSharedPlaylistTracks(cached?.second ?: emptyList())

  if (sharedPlaylistLoading || sharedPlaylistRefreshing) return

  val hasData = cached?.second?.isNotEmpty() == true
  if (!shouldRefreshSharedPlaylistTracks(cached = cached, force = force, ttlMs = ttlMs)) return

  if (hasData) {
    onSetSharedPlaylistRefreshing(true)
  } else {
    onSetSharedPlaylistLoading(true)
  }
  try {
    val fetched = fetchSharedPlaylistTracks(share)
    onSetSharedPlaylistError(fetched.error)
    // Only apply if we're still on this same playlist key (avoid race when switching fast).
    if (currentSharedPlaylistKey() == key) {
      onSetSharedPlaylistTracks(fetched.tracks)
    }
    SharedWithYouCache.putPlaylistTracks(key, SystemClock.elapsedRealtime(), fetched.tracks)
  } finally {
    onSetSharedPlaylistLoading(false)
    onSetSharedPlaylistRefreshing(false)
  }
}

internal suspend fun downloadAllSharedPlaylistTracksWithUi(
  tracks: List<SharedCloudTrack>,
  onSetCloudPlayLabel: (String?) -> Unit,
  onDownloadTrack: suspend (SharedCloudTrack) -> Boolean,
  onShowMessage: (String) -> Unit,
) {
  var ok = 0
  for ((idx, track) in tracks.withIndex()) {
    onSetCloudPlayLabel("Downloading ${idx + 1}/${tracks.size}: ${track.title}")
    if (onDownloadTrack(track)) ok += 1
  }
  onShowMessage("Downloaded $ok/${tracks.size} tracks")
}

internal fun findOnChainPlaylistById(
  playlistId: String,
  displayPlaylists: List<PlaylistDisplayItem>,
): PlaylistDisplayItem? {
  if (!playlistId.startsWith("0x")) return null
  return displayPlaylists.firstOrNull { it.id.equals(playlistId, ignoreCase = true) }
}

internal fun openPlaylistDetailWithUi(
  playlist: PlaylistDisplayItem,
  onSetSelectedPlaylist: (PlaylistDisplayItem?) -> Unit,
  onSetSelectedPlaylistId: (String?) -> Unit,
  onSetView: (MusicView) -> Unit,
  onLoadPlaylistDetail: suspend (PlaylistDisplayItem) -> Unit,
  scope: kotlinx.coroutines.CoroutineScope,
) {
  onSetSelectedPlaylist(playlist)
  onSetSelectedPlaylistId(playlist.id)
  onSetView(MusicView.PlaylistDetail)
  scope.launch {
    onLoadPlaylistDetail(playlist)
  }
}

internal suspend fun handleCreatePlaylistSuccessWithUi(
  playlistId: String,
  successMessage: String,
  onLoadPlaylists: suspend () -> Unit,
  currentDisplayPlaylists: () -> List<PlaylistDisplayItem>,
  onSetSelectedPlaylistId: (String?) -> Unit,
  onSetSelectedPlaylist: (PlaylistDisplayItem?) -> Unit,
  onSetView: (MusicView) -> Unit,
  onLoadPlaylistDetail: suspend (PlaylistDisplayItem) -> Unit,
  onShowMessage: (String) -> Unit,
) {
  onShowMessage(successMessage)
  onSetSelectedPlaylistId(playlistId)

  suspend fun reloadAndFind(): PlaylistDisplayItem? {
    onLoadPlaylists()
    return findOnChainPlaylistById(
      playlistId = playlistId,
      displayPlaylists = currentDisplayPlaylists(),
    )
  }

  var selected = reloadAndFind()
  if (selected != null) {
    onSetSelectedPlaylist(selected)
    onSetView(MusicView.PlaylistDetail)
    onLoadPlaylistDetail(selected)
    return
  }

  onSetSelectedPlaylist(null)
  if (!playlistId.startsWith("0x", ignoreCase = true)) return

  repeat(PLAYLIST_CREATE_APPEARANCE_RETRIES) {
    delay(PLAYLIST_CREATE_APPEARANCE_DELAY_MS)
    selected = reloadAndFind()
    if (selected == null) return@repeat
    onSetSelectedPlaylist(selected)
    onSetView(MusicView.PlaylistDetail)
    onLoadPlaylistDetail(selected)
    return
  }

  onShowMessage("Playlist created on-chain. Indexing may take a minute; tap refresh on Playlists.")
}

internal suspend fun handleAddToPlaylistSuccessWithUi(
  playlistId: String,
  currentView: MusicView,
  currentDisplayPlaylists: () -> List<PlaylistDisplayItem>,
  onLoadPlaylists: suspend () -> Unit,
  onSetSelectedPlaylistId: (String?) -> Unit,
  onSetSelectedPlaylist: (PlaylistDisplayItem?) -> Unit,
  selectedPlaylistId: String?,
  onLoadPlaylistDetail: suspend (PlaylistDisplayItem) -> Unit,
) {
  onLoadPlaylists()
  onSetSelectedPlaylistId(playlistId)
  val selected =
    findOnChainPlaylistById(
      playlistId = playlistId,
      displayPlaylists = currentDisplayPlaylists(),
    )
  onSetSelectedPlaylist(selected)
  if (selected != null && currentView == MusicView.PlaylistDetail && selectedPlaylistId == selected.id) {
    onLoadPlaylistDetail(selected)
  }
}
