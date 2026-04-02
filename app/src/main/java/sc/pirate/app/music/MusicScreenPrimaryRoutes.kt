package sc.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.fillMaxWidth
import sc.pirate.app.ui.PirateIconButton
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
  query: String,
  scanning: Boolean,
  error: String?,
  currentTrackId: String?,
  isPlaying: Boolean,
  onBack: () -> Unit,
  onQueryChange: (String) -> Unit,
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
    centerSlot = {
      MusicHeaderSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = "Search your library",
      )
    },
  )
  LibraryView(
    hasPermission = hasPermission,
    requestPermission = onRequestPermission,
    showSpotifyAccessPrompt = showSpotifyAccessPrompt,
    onOpenSpotifyAccessSettings = onOpenSpotifyAccessSettings,
    tracks = tracks,
    searchQuery = query,
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
internal fun MusicDiscoverSearchRoute(
  query: String,
  results: List<MusicDiscoveryResult>,
  loading: Boolean,
  error: String?,
  warning: String?,
  onQueryChange: (String) -> Unit,
  onBack: () -> Unit,
  onOpenResult: (MusicDiscoveryResult) -> Unit,
) {
  PirateMobileHeader(
    title = "Search",
    onBackPress = onBack,
    centerSlot = {
      MusicHeaderSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = "Search songs or artists",
        autofocus = true,
      )
    },
  )
  DiscoverSearchView(
    query = query,
    results = results,
    loading = loading,
    error = error,
    warning = warning,
    onOpenResult = onOpenResult,
  )
}

@Composable
private fun MusicHeaderSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  placeholder: String,
  autofocus: Boolean = false,
) {
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(autofocus) {
    if (autofocus) {
      focusRequester.requestFocus()
    }
  }
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    modifier =
      Modifier
        .fillMaxWidth()
        .focusRequester(focusRequester),
    singleLine = true,
    leadingIcon = {
      Icon(
        PhosphorIcons.Regular.MagnifyingGlass,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
    trailingIcon = {
      if (query.isNotBlank()) {
        PirateIconButton(onClick = { onQueryChange("") }) {
          Icon(
            PhosphorIcons.Regular.X,
            contentDescription = "Clear search",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    placeholder = { Text(placeholder) },
  )
}
