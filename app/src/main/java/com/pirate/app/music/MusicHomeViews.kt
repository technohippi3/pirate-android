package com.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MusicHomeView(
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
  onNavigateLibrary: () -> Unit,
  onNavigateShared: () -> Unit,
  onNavigatePlaylists: () -> Unit,
  onOpenPlaylist: (PlaylistDisplayItem) -> Unit,
  onPlayRelease: (AlbumCardModel) -> Unit,
  onOpenRoom: (LiveRoomCardModel) -> Unit,
) {
  val sharedSubtitle =
    when {
      sharedPlaylistCount > 0 && sharedTrackCount > 0 ->
        "$sharedPlaylistCount playlist${if (sharedPlaylistCount == 1) "" else "s"} · $sharedTrackCount song${if (sharedTrackCount == 1) "" else "s"}"
      sharedPlaylistCount > 0 ->
        "$sharedPlaylistCount playlist${if (sharedPlaylistCount == 1) "" else "s"}"
      else ->
        "$sharedTrackCount song${if (sharedTrackCount == 1) "" else "s"}"
    }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = 16.dp),
  ) {
    item {
      Column(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        EntryRow(
          icon = PhosphorIcons.Regular.List,
          iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
          iconBg = MaterialTheme.colorScheme.secondaryContainer,
          title = "Library",
          subtitle = "Your songs",
          badge = null,
          onClick = onNavigateLibrary,
        )
        EntryRow(
          icon = PhosphorIcons.Regular.Mailbox,
          iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
          iconBg = MaterialTheme.colorScheme.primaryContainer,
          title = "Shared With You",
          subtitle = sharedSubtitle,
          badge = if (sharedUnreadCount > 0) "$sharedUnreadCount" else null,
          onClick = onNavigateShared,
        )
        EntryRow(
          icon = PhosphorIcons.Regular.Playlist,
          iconTint = MaterialTheme.colorScheme.onTertiary,
          iconBg = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
          title = "Playlists",
          subtitle = "$playlistCount playlist${if (playlistCount == 1) "" else "s"}",
          badge = null,
          onClick = onNavigatePlaylists,
        )
      }
    }

    item {
      SectionHeader(title = "New Releases")
      NewReleasesSection(
        newReleases = newReleases,
        newReleasesLoading = newReleasesLoading,
        newReleasesError = newReleasesError,
        onPlayRelease = onPlayRelease,
      )
      Spacer(modifier = Modifier.height(28.dp))
    }

    item {
      SectionHeader(title = "Live")
      LiveRoomsSection(
        rooms = liveRooms,
        roomsLoading = liveRoomsLoading,
        roomsError = liveRoomsError,
        onOpenRoom = onOpenRoom,
      )
    }

    if (playlists.isNotEmpty()) {
      item {
        SectionHeader(title = "Your Playlists", action = "See all", onAction = onNavigatePlaylists)
        LazyRow(
          contentPadding = PaddingValues(horizontal = 20.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(playlists.take(6)) { playlist ->
            PlaylistCard(playlist = playlist, onClick = { onOpenPlaylist(playlist) })
          }
        }
        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  }
}
