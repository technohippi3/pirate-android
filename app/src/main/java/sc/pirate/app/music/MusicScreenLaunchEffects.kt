package sc.pirate.app.music

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import sc.pirate.app.profile.ProfileMusicApi
import sc.pirate.app.resolvePublicProfileIdentity
import sc.pirate.app.util.shortAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Composable
internal fun MusicScreenLaunchEffects(
  context: Context,
  newReleasesMax: Int,
  view: MusicView,
  ownerEthAddress: String?,
  isAuthenticated: Boolean,
  hasPermission: Boolean,
  sharedSelectedPlaylist: PlaylistShareEntry?,
  sharedPlaylists: List<PlaylistShareEntry>,
  sharedItemIds: Set<String>,
  sharedSeenItemIds: Set<String>,
  sharedOwnerLabels: MutableMap<String, String>,
  onSetTracks: (List<MusicTrack>) -> Unit,
  onSetDownloadedTracksByContentId: (Map<String, DownloadedTrackEntry>) -> Unit,
  onSetSharedSeenItemIds: (Set<String>) -> Unit,
  onSetRecentPublishedReleases: (List<AlbumCardModel>) -> Unit,
  onSetRecentPublishedReleasesLoading: (Boolean) -> Unit,
  onSetRecentPublishedReleasesError: (String?) -> Unit,
  recentPublishedReleases: List<AlbumCardModel>,
  recentPublishedLastFetchAtMs: Long,
  homeRefreshNonce: Int,
  onSetRecentPublishedLastFetchAtMs: (Long) -> Unit,
  onSetLiveRooms: (List<LiveRoomCardModel>) -> Unit,
  onSetLiveRoomsLoading: (Boolean) -> Unit,
  onSetLiveRoomsError: (String?) -> Unit,
  liveRooms: List<LiveRoomCardModel>,
  liveRoomsLastFetchAtMs: Long,
  onSetLiveRoomsLastFetchAtMs: (Long) -> Unit,
  onLoadPlaylists: suspend () -> Unit,
  onLoadShared: suspend (Boolean) -> Unit,
  onLoadPurchasedCloudLibrary: suspend () -> Unit,
  onLoadSharedPlaylistTracks: suspend (PlaylistShareEntry, Boolean) -> Unit,
  onRunSilentScan: suspend () -> Unit,
) {
  LaunchedEffect(Unit) {
    onSetTracks(MusicLibrary.loadCachedTracks(context))
    onSetDownloadedTracksByContentId(DownloadedTracksStore.load(context))
    onLoadPlaylists()
    if (hasPermission) {
      onRunSilentScan()
    }
  }

  LaunchedEffect(ownerEthAddress, isAuthenticated) {
    val seenItemIds =
      if (isAuthenticated && !ownerEthAddress.isNullOrBlank()) {
        withContext(Dispatchers.IO) { loadSeenSharedItemIds(context, ownerEthAddress) }
      } else {
        emptySet()
      }
    onSetSharedSeenItemIds(seenItemIds)
    onLoadPlaylists()
    onLoadShared(false)
    onLoadPurchasedCloudLibrary()
  }

  LaunchedEffect(view, ownerEthAddress, isAuthenticated) {
    if (view != MusicView.Shared) return@LaunchedEffect
    onLoadShared(false)
  }

  LaunchedEffect(view, homeRefreshNonce) {
    if (view != MusicView.Home) return@LaunchedEffect
    val nowMs = System.currentTimeMillis()
    if (
      recentPublishedReleases.isNotEmpty() &&
      (nowMs - recentPublishedLastFetchAtMs) < HOME_NEW_RELEASES_TTL_MS
    ) {
      return@LaunchedEffect
    }
    onSetRecentPublishedReleases(emptyList())
    onSetRecentPublishedReleasesLoading(true)
    onSetRecentPublishedReleasesError(null)

    try {
      val rows = ProfileMusicApi.fetchLatestPublishedSongs(maxEntries = newReleasesMax)
      val releases =
        rows.map { row ->
          AlbumCardModel(
            trackId = row.trackId,
            contentId = row.contentId,
            title = row.title,
            artist = row.artist,
            coverRef = row.coverCid,
            lyricsRef = row.lyricsRef,
          )
        }
      onSetRecentPublishedReleases(releases)
      onSetRecentPublishedReleasesLoading(false)
      onSetRecentPublishedLastFetchAtMs(System.currentTimeMillis())
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      onSetRecentPublishedReleasesError(error.message ?: "Failed to load new releases")
      onSetRecentPublishedReleasesLoading(false)
      onSetRecentPublishedLastFetchAtMs(System.currentTimeMillis())
    }
  }

  LaunchedEffect(view, homeRefreshNonce, ownerEthAddress, isAuthenticated) {
    if (view != MusicView.Home) return@LaunchedEffect
    val nowMs = System.currentTimeMillis()
    if (
      liveRooms.isNotEmpty() &&
      (nowMs - liveRoomsLastFetchAtMs) < HOME_LIVE_ROOMS_TTL_MS
    ) {
      return@LaunchedEffect
    }
    onSetLiveRooms(emptyList())
    onSetLiveRoomsLoading(true)
    onSetLiveRoomsError(null)

    try {
      val rooms =
        fetchDiscoverableLiveRooms(
          context = context,
          ownerEthAddress = ownerEthAddress,
          isAuthenticated = isAuthenticated,
          maxEntries = HOME_LIVE_ROOMS_MAX,
        )
      onSetLiveRooms(rooms)
      onSetLiveRoomsLoading(false)
      onSetLiveRoomsLastFetchAtMs(System.currentTimeMillis())
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      onSetLiveRoomsError(error.message ?: "Failed to load rooms")
      onSetLiveRoomsLoading(false)
      onSetLiveRoomsLastFetchAtMs(System.currentTimeMillis())
    }
  }

  LaunchedEffect(view, sharedSelectedPlaylist) {
    if (view != MusicView.SharedPlaylistDetail) return@LaunchedEffect
    val share = sharedSelectedPlaylist ?: return@LaunchedEffect
    onLoadSharedPlaylistTracks(share, false)
  }

  LaunchedEffect(view, sharedItemIds, ownerEthAddress, isAuthenticated) {
    if (view != MusicView.Shared || !isAuthenticated || ownerEthAddress.isNullOrBlank()) return@LaunchedEffect
    if (sharedItemIds.isEmpty()) return@LaunchedEffect
    val merged = sharedSeenItemIds + sharedItemIds
    if (merged.size == sharedSeenItemIds.size) return@LaunchedEffect
    onSetSharedSeenItemIds(merged)
    withContext(Dispatchers.IO) { saveSeenSharedItemIds(context, ownerEthAddress, merged) }
  }

  LaunchedEffect(sharedPlaylists) {
    val owners =
      sharedPlaylists
        .map { it.owner.trim().lowercase() }
        .filter { it.startsWith("0x") && it.length == 42 }
        .distinct()

    for (owner in owners) {
      if (sharedOwnerLabels.containsKey(owner)) continue
      sharedOwnerLabels[owner] = shortAddress(owner, minLengthToShorten = 10)
      val handle =
        runCatching {
          withContext(Dispatchers.IO) {
            resolvePublicProfileIdentity(owner).first
          }
        }
          .getOrNull()
          ?.trim()
          .orEmpty()
      if (handle.isNotBlank()) {
        sharedOwnerLabels[owner] = handle
      }
    }
  }
}
