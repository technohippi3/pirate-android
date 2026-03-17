package com.pirate.app.profile

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pirate.app.music.OnChainPlaylist

internal enum class ProfileTab(val icon: ImageVector, val contentDescription: String) {
  Songs(PhosphorIcons.Regular.MusicNote, "Songs"),
  Playlists(PhosphorIcons.Regular.Queue, "Playlists"),
  Scrobbles(PhosphorIcons.Regular.Headphones, "Scrobbles"),
  Schedule(PhosphorIcons.Regular.Calendar, "Schedule"),
  About(PhosphorIcons.Regular.User, "About"),
}

@Composable
internal fun ProfileScreenTabBar(
  selectedTab: Int,
  tabs: List<ProfileTab>,
  onTabSelected: (Int) -> Unit,
) {
  TabRow(
    selectedTabIndex = selectedTab,
    containerColor = Color(0xFF1C1C1C),
    contentColor = Color.White,
    indicator = { tabPositions ->
      if (selectedTab < tabPositions.size) {
        TabRowDefaults.SecondaryIndicator(
          modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
          color = MaterialTheme.colorScheme.primary,
        )
      }
    },
    divider = { HorizontalDivider(color = Color(0xFF363636)) },
  ) {
    tabs.forEachIndexed { index, tab ->
      Tab(
        selected = selectedTab == index,
        onClick = { onTabSelected(index) },
        icon = {
          Icon(
            imageVector = tab.icon,
            contentDescription = tab.contentDescription,
            modifier = Modifier.size(26.dp),
            tint = if (selectedTab == index) Color.White else Color(0xFFA3A3A3),
          )
        },
      )
    }
  }
}

@Composable
internal fun ProfileScreenTabContent(
  tab: ProfileTab,
  publishedSongs: List<PublishedSongRow>,
  publishedSongsLoading: Boolean,
  publishedSongsError: String?,
  onPlayPublishedSong: ((PublishedSongRow) -> Unit)?,
  onOpenSong: ((trackId: String, title: String?, artist: String?) -> Unit)?,
  onOpenArtist: ((String) -> Unit)?,
  playlists: List<OnChainPlaylist>,
  playlistsLoading: Boolean,
  playlistsError: String?,
  onOpenPlaylist: (OnChainPlaylist) -> Unit,
  scrobbles: List<ScrobbleRow>,
  scrobblesLoading: Boolean,
  scrobblesError: String?,
  contractProfile: ContractProfileData?,
  contractLoading: Boolean,
  contractError: String?,
  locationRecord: String?,
  schoolRecord: String?,
  isOwnProfile: Boolean,
  onEditProfile: (() -> Unit)?,
  onScrobbleRetry: () -> Unit,
  onContractRetry: () -> Unit,
) {
  when (tab) {
    ProfileTab.Songs -> SongsPanel(
      publishedSongs = publishedSongs,
      loading = publishedSongsLoading,
      error = publishedSongsError,
      onPlaySong = onPlayPublishedSong,
      onOpenSong = onOpenSong,
      onOpenArtist = onOpenArtist,
    )

    ProfileTab.Playlists -> PlaylistsPanel(
      playlists = playlists,
      loading = playlistsLoading,
      error = playlistsError,
      onOpenPlaylist = onOpenPlaylist,
    )

    ProfileTab.Scrobbles -> ScrobblesPanel(
      scrobbles = scrobbles,
      loading = scrobblesLoading,
      error = scrobblesError,
      onOpenSong = onOpenSong,
      onOpenArtist = onOpenArtist,
      onRetry = onScrobbleRetry,
    )

    ProfileTab.Schedule -> EmptyTabPanel("Schedule")

    ProfileTab.About -> AboutPanel(
      profile = contractProfile,
      loading = contractLoading,
      error = contractError,
      locationLabel = locationRecord,
      schoolLabel = schoolRecord,
      isOwnProfile = isOwnProfile,
      onEditProfile = onEditProfile,
      onRetry = onContractRetry,
    )
  }
}
