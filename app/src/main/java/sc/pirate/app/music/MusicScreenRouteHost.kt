package sc.pirate.app.music

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import sc.pirate.app.player.PlayerController
import kotlinx.coroutines.launch

@Composable
internal fun MusicScreenRouteHost(
  view: MusicView,
  onViewChange: (MusicView) -> Unit,
  sharedPlaylists: List<PlaylistShareEntry>,
  sharedTracks: List<SharedCloudTrack>,
  sharedUnreadCount: Int,
  displayPlaylists: List<PlaylistDisplayItem>,
  newReleases: List<AlbumCardModel>,
  newReleasesLoading: Boolean,
  newReleasesError: String?,
  liveRooms: List<LiveRoomCardModel>,
  liveRoomsLoading: Boolean,
  liveRoomsError: String?,
  onOpenDrawer: () -> Unit,
  onShowMessage: (String) -> Unit,
  onPlayRelease: (AlbumCardModel) -> Unit,
  onOpenRoom: (LiveRoomCardModel) -> Unit,
  player: PlayerController,
  currentTrackId: String?,
  currentTrackUri: String?,
  isPlaying: Boolean,
  onOpenPlayer: () -> Unit,
  hasPermission: Boolean,
  tracks: List<MusicTrack>,
  librarySearchQuery: String,
  scanning: Boolean,
  libraryError: String?,
  onRequestPermission: () -> Unit,
  showSpotifyAccessPrompt: Boolean,
  onOpenSpotifyAccessSettings: () -> Unit,
  onScan: () -> Unit,
  onOpenTrackMenu: (MusicTrack) -> Unit,
  onLibrarySearchQueryChange: (String) -> Unit,
  discoverSearchQuery: String,
  discoverSearchResults: List<MusicDiscoveryResult>,
  discoverSearchLoading: Boolean,
  discoverSearchError: String?,
  discoverSearchWarning: String?,
  onDiscoverSearchQueryChange: (String) -> Unit,
  onOpenDiscoveryResult: (MusicDiscoveryResult) -> Unit,
  sharedLoading: Boolean,
  sharedError: String?,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
  primaryName: String?,
  avatarUri: String?,
  ownerLabelFor: (String) -> String,
  onRefreshShared: () -> Unit,
  onOpenSharedPlaylist: (PlaylistShareEntry) -> Unit,
  onPlaySharedTrack: (SharedCloudTrack) -> Unit,
  onDownloadSharedTrack: (SharedCloudTrack) -> Unit,
  playlistsLoading: Boolean,
  onRefreshPlaylists: () -> Unit,
  onCreatePlaylist: () -> Unit,
  onOpenPlaylist: (PlaylistDisplayItem) -> Unit,
  selectedPlaylist: PlaylistDisplayItem?,
  selectedPlaylistId: String?,
  onSelectedPlaylistChange: (PlaylistDisplayItem?) -> Unit,
  playlistDetailLoading: Boolean,
  playlistDetailError: String?,
  playlistDetailTracks: List<MusicTrack>,
  onLoadPlaylistDetail: suspend (PlaylistDisplayItem) -> Unit,
  onChangePlaylistCover: suspend (PlaylistDisplayItem, Uri) -> Boolean,
  onDeletePlaylist: suspend (PlaylistDisplayItem) -> Boolean,
  sharedSelectedPlaylist: PlaylistShareEntry?,
  sharedPlaylistMenuOpen: Boolean,
  onSharedPlaylistMenuOpenChange: (Boolean) -> Unit,
  sharedPlaylistTracks: List<SharedCloudTrack>,
  sharedPlaylistLoading: Boolean,
  sharedPlaylistRefreshing: Boolean,
  sharedPlaylistError: String?,
  sharedByLabel: String?,
  onRefreshSharedPlaylist: (PlaylistShareEntry) -> Unit,
  onDownloadAllSharedPlaylist: suspend () -> Unit,
  cloudPlayBusy: Boolean,
  cloudPlayLabel: String?,
  onResolveTrackForPlayback: suspend (MusicTrack) -> PlaybackResolveResult,
) {
  val scope = rememberCoroutineScope()

  fun playTrackWithResolution(
    selected: MusicTrack,
    queueTracks: List<MusicTrack>,
  ) {
    scope.launch {
      val resolved = onResolveTrackForPlayback(selected)
      val playable = resolved.track
      if (playable == null) {
        onShowMessage(resolved.message ?: "This track isn't playable right now.")
        return@launch
      }

      val queue = ArrayList<MusicTrack>(queueTracks.size + 1)
      for (candidate in queueTracks) {
        val row = if (candidate.id == selected.id) playable else candidate
        if (row.uri.isNotBlank()) queue.add(row)
      }
      if (queue.none { it.id == playable.id }) {
        queue.add(0, playable)
      }

      val currentUri = currentTrackUri?.trim().orEmpty()
      val nextUri = playable.uri.trim()
      val isSameLoadedSource =
        currentTrackId == playable.id &&
          currentUri.isNotBlank() &&
          currentUri == nextUri
      if (isSameLoadedSource) {
        player.togglePlayPause()
      } else {
        player.playTrack(playable, queue)
      }
      onOpenPlayer()
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      when (view) {
        MusicView.Home -> {
          MusicHomeRoute(
            sharedPlaylistCount = sharedPlaylists.size,
            sharedTrackCount = sharedTracks.size,
            sharedUnreadCount = sharedUnreadCount,
            playlistCount = displayPlaylists.size,
            playlists = displayPlaylists,
            newReleases = newReleases,
            newReleasesLoading = newReleasesLoading,
            newReleasesError = newReleasesError,
            liveRooms = liveRooms,
            liveRoomsLoading = liveRoomsLoading,
            liveRoomsError = liveRoomsError,
            onOpenDrawer = onOpenDrawer,
            isAuthenticated = isAuthenticated,
            ownerEthAddress = ownerEthAddress,
            primaryName = primaryName,
            avatarUri = avatarUri,
            onNavigateSearch = { onViewChange(MusicView.DiscoverSearch) },
            onNavigateLibrary = { onViewChange(MusicView.Library) },
            onNavigateShared = { onViewChange(MusicView.Shared) },
            onNavigatePlaylists = { onViewChange(MusicView.Playlists) },
            onOpenPlaylist = onOpenPlaylist,
            onPlayRelease = onPlayRelease,
            onOpenRoom = onOpenRoom,
          )
        }

        MusicView.Library -> {
          MusicLibraryRoute(
            hasPermission = hasPermission,
            tracks = tracks,
            query = librarySearchQuery,
            scanning = scanning,
            error = libraryError,
            currentTrackId = currentTrackId,
            isPlaying = isPlaying,
            onBack = { onViewChange(MusicView.Home) },
            onQueryChange = onLibrarySearchQueryChange,
            onRequestPermission = onRequestPermission,
            showSpotifyAccessPrompt = showSpotifyAccessPrompt,
            onOpenSpotifyAccessSettings = onOpenSpotifyAccessSettings,
            onScan = onScan,
            onPlayTrack = { track, queueTracks ->
              playTrackWithResolution(
                selected = track,
                queueTracks = queueTracks,
              )
            },
            onTrackMenu = onOpenTrackMenu,
          )
        }

        MusicView.DiscoverSearch -> {
          MusicDiscoverSearchRoute(
            query = discoverSearchQuery,
            results = discoverSearchResults,
            loading = discoverSearchLoading,
            error = discoverSearchError,
            warning = discoverSearchWarning,
            onQueryChange = onDiscoverSearchQueryChange,
            onBack = {
              onViewChange(MusicView.Home)
            },
            onOpenResult = onOpenDiscoveryResult,
          )
        }

        MusicView.Shared -> {
          SharedLibraryRoute(
            cloudPlayBusy = cloudPlayBusy,
            sharedLoading = sharedLoading,
            sharedError = sharedError,
            sharedPlaylists = sharedPlaylists,
            sharedTracks = sharedTracks,
            isAuthenticated = isAuthenticated,
            ownerLabelFor = ownerLabelFor,
            onBack = { onViewChange(MusicView.Home) },
            onRefresh = onRefreshShared,
            onOpenPlaylist = onOpenSharedPlaylist,
            onPlayTrack = onPlaySharedTrack,
            onDownloadTrack = onDownloadSharedTrack,
          )
        }

        MusicView.Playlists -> {
          PlaylistsRoute(
            loading = playlistsLoading,
            playlists = displayPlaylists,
            onBack = { onViewChange(MusicView.Home) },
            onRefreshPlaylists = onRefreshPlaylists,
            onCreatePlaylist = onCreatePlaylist,
            onOpenPlaylist = onOpenPlaylist,
          )
        }

        MusicView.PlaylistDetail -> {
          val playlist =
            selectedPlaylist
              ?: displayPlaylists.find { it.id == selectedPlaylistId }?.also { onSelectedPlaylistChange(it) }
          LaunchedEffect(playlist) {
            if (playlist != null && playlistDetailTracks.isEmpty() && !playlistDetailLoading) {
              onLoadPlaylistDetail(playlist)
            }
          }
          PlaylistDetailRoute(
            playlist = playlist,
            ownerEthAddress = ownerEthAddress,
            loading = playlistDetailLoading,
            error = playlistDetailError,
            tracks = playlistDetailTracks,
            currentTrackId = currentTrackId,
            isPlaying = isPlaying,
            onBack = { onViewChange(MusicView.Playlists) },
            onPlayTrack = { track ->
              playTrackWithResolution(
                selected = track,
                queueTracks = playlistDetailTracks,
              )
            },
            onTrackMenu = { track ->
              onOpenTrackMenu(track)
            },
            onChangeCover = onChangePlaylistCover,
            onDeletePlaylist = onDeletePlaylist,
          )
        }

        MusicView.SharedPlaylistDetail -> {
          val share = sharedSelectedPlaylist
          SharedPlaylistDetailRoute(
            share = share,
            sharedPlaylistMenuOpen = sharedPlaylistMenuOpen,
            onSharedPlaylistMenuOpenChange = onSharedPlaylistMenuOpenChange,
            sharedPlaylistTracks = sharedPlaylistTracks,
            sharedPlaylistLoading = sharedPlaylistLoading,
            sharedPlaylistRefreshing = sharedPlaylistRefreshing,
            sharedPlaylistError = sharedPlaylistError,
            sharedByLabel = sharedByLabel,
            currentTrackId = currentTrackId,
            isPlaying = isPlaying,
            onBack = { onViewChange(MusicView.Shared) },
            onRefresh = {
              if (share != null) {
                onRefreshSharedPlaylist(share)
              }
            },
            onDownloadAll = {
              scope.launch { onDownloadAllSharedPlaylist() }
            },
            onPlayTrack = onPlaySharedTrack,
            onDownloadTrack = onDownloadSharedTrack,
            onShowMessage = onShowMessage,
          )
        }
      }
    }

    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
      CloudPlayBusyBanner(
        cloudPlayBusy = cloudPlayBusy,
        cloudPlayLabel = cloudPlayLabel,
      )
    }
  }
}

@Composable
internal fun MusicScreenOverlayHost(
  trackMenuOpen: Boolean,
  selectedTrack: MusicTrack?,
  ownerEthAddress: String?,
  isAuthenticated: Boolean,
  tracks: List<MusicTrack>,
  downloadedTracksByContentId: Map<String, DownloadedTrackEntry>,
  uploadBusy: Boolean,
  turboCreditsCopy: String,
  onUploadBusyChange: (Boolean) -> Unit,
  onTracksChange: (List<MusicTrack>) -> Unit,
  onOpenShare: (MusicTrack) -> Unit,
  onOpenAddToPlaylist: (MusicTrack) -> Unit,
  onCloseTrackMenu: () -> Unit,
  onPromptTurboTopUp: (String) -> Unit,
  onShowMessage: (String) -> Unit,
  onRescanAfterDownload: suspend () -> Unit,
  onOpenSongPage: ((trackId: String, title: String?, artist: String?) -> Unit)? = null,
  onOpenArtistPage: ((artistName: String) -> Unit)? = null,
  createPlaylistOpen: Boolean,
  addToPlaylistOpen: Boolean,
  onCreatePlaylistOpenChange: (Boolean) -> Unit,
  onAddToPlaylistOpenChange: (Boolean) -> Unit,
  onCreatePlaylistSuccess: (playlistId: String, successMessage: String) -> Unit,
  onAddToPlaylistSuccess: (playlistId: String, trackAdded: Boolean) -> Unit,
  shareTrack: MusicTrack?,
  onDismissShare: () -> Unit,
  turboCreditsSheetOpen: Boolean,
  turboCreditsSheetMessage: String,
  onDismissTurboCredits: () -> Unit,
  onGetTurboCredits: () -> Unit,
) {
  MusicTrackMenuOverlay(
    open = trackMenuOpen,
    selectedTrack = selectedTrack,
    ownerEthAddress = ownerEthAddress,
    isAuthenticated = isAuthenticated,
    tracks = tracks,
    downloadedTracksByContentId = downloadedTracksByContentId,
    uploadBusy = uploadBusy,
    turboCreditsCopy = turboCreditsCopy,
    onUploadBusyChange = onUploadBusyChange,
    onTracksChange = onTracksChange,
    onOpenShare = onOpenShare,
    onOpenAddToPlaylist = onOpenAddToPlaylist,
    onClose = onCloseTrackMenu,
    onPromptTurboTopUp = onPromptTurboTopUp,
    onShowMessage = onShowMessage,
    onRescanAfterDownload = onRescanAfterDownload,
    onOpenSongPage = onOpenSongPage,
    onOpenArtistPage = onOpenArtistPage,
  )

  MusicPlaylistSheets(
    createPlaylistOpen = createPlaylistOpen,
    addToPlaylistOpen = addToPlaylistOpen,
    selectedTrack = selectedTrack,
    isAuthenticated = isAuthenticated,
    ownerEthAddress = ownerEthAddress,
    onShowMessage = onShowMessage,
    onCreatePlaylistOpenChange = onCreatePlaylistOpenChange,
    onAddToPlaylistOpenChange = onAddToPlaylistOpenChange,
    onCreatePlaylistSuccess = onCreatePlaylistSuccess,
    onAddToPlaylistSuccess = onAddToPlaylistSuccess,
  )

  MusicShareDialog(
    shareTrack = shareTrack,
    ownerEthAddress = ownerEthAddress,
    onDismiss = onDismissShare,
    onShowMessage = onShowMessage,
  )

  sc.pirate.app.music.ui.TurboCreditsSheet(
    open = turboCreditsSheetOpen,
    message = turboCreditsSheetMessage,
    onDismiss = onDismissTurboCredits,
    onGetCredits = onGetTurboCredits,
  )
}
