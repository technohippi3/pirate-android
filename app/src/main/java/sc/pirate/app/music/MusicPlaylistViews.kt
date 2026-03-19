package sc.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import sc.pirate.app.music.ui.TrackItemRow
import sc.pirate.app.theme.PiratePalette

@Composable
internal fun PlaylistsView(
  loading: Boolean,
  playlists: List<PlaylistDisplayItem>,
  onOpenPlaylist: (PlaylistDisplayItem) -> Unit,
) {
  val playlistsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }

  if (loading) {
    EmptyState(title = "Loading playlists...", actionLabel = null, onAction = null)
    return
  }

  if (playlists.isEmpty()) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(horizontal = 20.dp, vertical = 28.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text("No playlists yet", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
      Text("Add tracks to a playlist from the track menu", color = PiratePalette.TextMuted)
    }
    return
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = playlistsListState,
    contentPadding = PaddingValues(bottom = 12.dp),
  ) {
    items(playlists, key = { it.id }) { playlist ->
      PlaylistRow(playlist = playlist, onClick = { onOpenPlaylist(playlist) })
    }
  }
}

@Composable
internal fun PlaylistDetailView(
  playlist: PlaylistDisplayItem?,
  loading: Boolean,
  error: String?,
  tracks: List<MusicTrack>,
  currentTrackId: String?,
  isPlaying: Boolean,
  onPlayTrack: (MusicTrack) -> Unit,
  onTrackMenu: (MusicTrack) -> Unit,
  coverUpdating: Boolean = false,
) {
  if (playlist == null) {
    EmptyState(title = "Playlist not found", actionLabel = null, onAction = null)
    return
  }

  val playlistTracksListState =
    rememberSaveable(playlist.id, saver = LazyListState.Saver) { LazyListState(0, 0) }

  Column(modifier = Modifier.fillMaxSize()) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 16.dp),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier.size(220.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (!playlist.coverUri.isNullOrBlank()) {
            AsyncImage(
              model = playlist.coverUri,
              contentDescription = "Playlist cover",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            Icon(
              PhosphorIcons.Regular.Playlist,
              contentDescription = null,
              tint = PiratePalette.TextMuted,
              modifier = Modifier.size(28.dp),
            )
          }
          if (coverUpdating) {
            CircularProgressIndicator(
              modifier = Modifier.size(34.dp),
              strokeWidth = 2.5.dp,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }

    Text(
      text = playlist.name.ifBlank { "Playlist" },
      modifier = Modifier.padding(horizontal = 16.dp),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onBackground,
      fontWeight = FontWeight.Bold,
      style = MaterialTheme.typography.headlineSmall,
    )

    if (!error.isNullOrBlank()) {
      Text(
        text = error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    if (tracks.isEmpty()) {
      if (loading) {
        SharedPlaylistTracksSkeleton()
        return
      }
      EmptyState(title = "No tracks in this playlist", actionLabel = null, onAction = null)
      return
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = playlistTracksListState,
      contentPadding = PaddingValues(bottom = 12.dp),
    ) {
      items(tracks, key = { it.id }) { track ->
        val id = track.id
        TrackItemRow(
          track = track,
          isActive = currentTrackId == id,
          isPlaying = currentTrackId == id && isPlaying,
          onPress = { onPlayTrack(track) },
          onMenuPress = { onTrackMenu(track) },
        )
      }
    }
  }
}

@Composable
internal fun PlaylistRow(
  playlist: PlaylistDisplayItem,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(72.dp)
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(
      modifier = Modifier.size(48.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = MaterialTheme.shapes.medium,
    ) {
      Box(contentAlignment = Alignment.Center) {
        if (!playlist.coverUri.isNullOrBlank()) {
          AsyncImage(
            model = playlist.coverUri,
            contentDescription = "Playlist cover",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(
            PhosphorIcons.Regular.MusicNote,
            contentDescription = null,
            tint = PiratePalette.TextMuted,
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }

    Column(modifier = Modifier.weight(1f)) {
      Text(
        playlist.name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Text(
        "${playlist.trackCount} track${if (playlist.trackCount == 1) "" else "s"}",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = PiratePalette.TextMuted,
      )
    }
  }
}
