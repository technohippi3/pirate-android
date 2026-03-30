package sc.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import sc.pirate.app.ui.PirateMobileHeader

@Composable
internal fun MusicHomeRoute(
  sharedPlaylistCount: Int,
  sharedTrackCount: Int,
  sharedUnreadCount: Int,
  playlistCount: Int,
  playlists: List<PlaylistDisplayItem>,
  newReleases: List<AlbumCardModel>,
  newReleasesLoading: Boolean,
  newReleasesError: String?,
  liveRooms: List<LiveRoomCardModel>,
  liveRoomsLoading: Boolean,
  liveRoomsError: String?,
  onOpenDrawer: () -> Unit,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
  primaryName: String?,
  avatarUri: String?,
  onNavigateSearch: () -> Unit,
  onNavigateLibrary: () -> Unit,
  onNavigateShared: () -> Unit,
  onNavigatePlaylists: () -> Unit,
  onOpenPlaylist: (PlaylistDisplayItem) -> Unit,
  onPlayRelease: (AlbumCardModel) -> Unit,
  onOpenRoom: (LiveRoomCardModel) -> Unit,
) {
  PirateMobileHeader(
    title = "Music",
    isAuthenticated = isAuthenticated,
    ethAddress = ownerEthAddress,
    primaryName = primaryName,
    avatarUri = avatarUri,
    onAvatarPress = onOpenDrawer,
    rightSlot = {
      PirateIconButton(onClick = onNavigateSearch) {
        Icon(
          PhosphorIcons.Regular.MagnifyingGlass,
          contentDescription = "Search",
          tint = MaterialTheme.colorScheme.onBackground,
        )
      }
    },
  )
  MusicHomeView(
    sharedPlaylistCount = sharedPlaylistCount,
    sharedTrackCount = sharedTrackCount,
    sharedUnreadCount = sharedUnreadCount,
    playlistCount = playlistCount,
    playlists = playlists,
    newReleases = newReleases,
    newReleasesLoading = newReleasesLoading,
    newReleasesError = newReleasesError,
    liveRooms = liveRooms,
    liveRoomsLoading = liveRoomsLoading,
    liveRoomsError = liveRoomsError,
    onNavigateLibrary = onNavigateLibrary,
    onNavigateShared = onNavigateShared,
    onNavigatePlaylists = onNavigatePlaylists,
    onOpenPlaylist = onOpenPlaylist,
    onPlayRelease = onPlayRelease,
    onOpenRoom = onOpenRoom,
  )
}

@Composable
internal fun MusicLibraryRoute(
  hasPermission: Boolean,
  tracks: List<MusicTrack>,
  scanning: Boolean,
  error: String?,
  currentTrackId: String?,
  isPlaying: Boolean,
  onBack: () -> Unit,
  onNavigateSearch: () -> Unit,
  onRequestPermission: () -> Unit,
  showSpotifyAccessPrompt: Boolean,
  onOpenSpotifyAccessSettings: () -> Unit,
  onScan: () -> Unit,
  onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
  onTrackMenu: (MusicTrack) -> Unit,
) {
  PirateMobileHeader(
    title = "Library",
    onBackPress = onBack,
    rightSlot = {
      PirateIconButton(onClick = onNavigateSearch) {
        Icon(
          PhosphorIcons.Regular.MagnifyingGlass,
          contentDescription = "Search",
          tint = MaterialTheme.colorScheme.onBackground,
        )
      }
    },
  )
  LibraryView(
    hasPermission = hasPermission,
    requestPermission = onRequestPermission,
    showSpotifyAccessPrompt = showSpotifyAccessPrompt,
    onOpenSpotifyAccessSettings = onOpenSpotifyAccessSettings,
    tracks = tracks,
    scanning = scanning,
    error = error,
    currentTrackId = currentTrackId,
    isPlaying = isPlaying,
    onScan = onScan,
    onPlayTrack = onPlayTrack,
    onTrackMenu = onTrackMenu,
  )
}

@Composable
internal fun MusicSearchRoute(
  query: String,
  tracks: List<MusicTrack>,
  currentTrackId: String?,
  isPlaying: Boolean,
  onQueryChange: (String) -> Unit,
  onBack: () -> Unit,
  onPlayTrack: (MusicTrack) -> Unit,
  onTrackMenu: (MusicTrack) -> Unit,
) {
  PirateMobileHeader(
    title = "Search",
    onBackPress = onBack,
  )
  SearchView(
    query = query,
    onQueryChange = onQueryChange,
    tracks = tracks,
    currentTrackId = currentTrackId,
    isPlaying = isPlaying,
    onPlayTrack = onPlayTrack,
    onTrackMenu = onTrackMenu,
  )
}
