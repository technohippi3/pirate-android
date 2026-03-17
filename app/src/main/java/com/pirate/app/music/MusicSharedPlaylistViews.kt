package com.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.util.Log
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
import androidx.compose.material3.HorizontalDivider
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
import com.pirate.app.music.ui.TrackItemRow
import com.pirate.app.theme.PiratePalette

private const val TAG_SHARED = "MusicShared"

@Composable
internal fun SharedView(
  loading: Boolean,
  error: String?,
  sharedPlaylists: List<PlaylistShareEntry>,
  sharedTracks: List<SharedCloudTrack>,
  isAuthenticated: Boolean,
  ownerLabelFor: (String) -> String,
  onOpenPlaylist: (PlaylistShareEntry) -> Unit,
  onPlayTrack: (SharedCloudTrack) -> Unit,
  onDownloadTrack: (SharedCloudTrack) -> Unit,
) {
  val sharedListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }

  if (!isAuthenticated) {
    EmptyState(title = "Sign in to view shared items", actionLabel = null, onAction = null)
    return
  }

  val total = sharedPlaylists.size + sharedTracks.size

  Column(modifier = Modifier.fillMaxSize()) {
    if (!error.isNullOrBlank()) {
      Text(
        text = error,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    if (total == 0) {
      // Avoid flashing an empty state while the initial request is still loading.
      if (!loading) {
        EmptyState(title = "Nothing shared with you yet", actionLabel = null, onAction = null)
      }
      return
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = sharedListState,
      contentPadding = PaddingValues(bottom = 12.dp),
    ) {
      if (sharedPlaylists.isNotEmpty()) {
        item {
          Text(
            text = "Playlists",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
          )
        }

        items(sharedPlaylists, key = { it.id }) { share ->
          SharedPlaylistRow(
            share = share,
            coverUrl = sharedPlaylistCoverUrl(share.playlist.coverCid),
            ownerLabel = ownerLabelFor(share.owner),
            onClick = { onOpenPlaylist(share) },
          )
        }

        item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
      }

      if (sharedTracks.isNotEmpty()) {
        item {
          Text(
            text = "Songs",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
          )
        }

        items(sharedTracks, key = { it.contentId.ifBlank { it.trackId } }) { t ->
          val rowTrack = sharedCloudTrackToRowTrack(t)
          TrackItemRow(
            track = rowTrack,
            isActive = false,
            isPlaying = false,
            onPress = { onPlayTrack(t) },
            onMenuPress = { onDownloadTrack(t) },
          )
        }
      }
    }
  }
}

@Composable
internal fun SharedPlaylistRow(
  share: PlaylistShareEntry,
  coverUrl: String?,
  ownerLabel: String,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(72.dp)
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp),
    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(
      modifier = Modifier.size(48.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = MaterialTheme.shapes.medium,
    ) {
      Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (!coverUrl.isNullOrBlank()) {
          AsyncImage(
            model = coverUrl,
            contentDescription = "Playlist cover",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = { Log.w(TAG_SHARED, "playlist cover failed url=$coverUrl") },
          )
        } else {
          Icon(
            PhosphorIcons.Regular.Playlist,
            contentDescription = null,
            tint = PiratePalette.TextMuted,
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }

    Column(modifier = Modifier.weight(1f)) {
      val name = share.playlist.name.ifBlank { "Shared playlist" }
      Text(
        name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Text(
        "${share.trackCount} track${if (share.trackCount == 1) "" else "s"} · shared by $ownerLabel",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = PiratePalette.TextMuted,
      )
    }

    Icon(PhosphorIcons.Regular.CaretRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
internal fun SharedPlaylistDetailView(
  loading: Boolean,
  error: String?,
  share: PlaylistShareEntry?,
  sharedByLabel: String?,
  tracks: List<SharedCloudTrack>,
  currentTrackId: String?,
  isPlaying: Boolean,
  onPlayTrack: (SharedCloudTrack) -> Unit,
  onDownloadTrack: (SharedCloudTrack) -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val pl = share?.playlist
  if (pl == null) {
    EmptyState(title = "Playlist not found", actionLabel = null, onAction = null)
    return
  }

  val sharedPlaylistTracksListState =
    rememberSaveable(share.id, saver = LazyListState.Saver) { LazyListState(0, 0) }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
      Surface(
        modifier = Modifier.size(72.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
      ) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
          val url = sharedPlaylistCoverUrl(pl.coverCid)
          if (!url.isNullOrBlank()) {
            AsyncImage(
              model = url,
              contentDescription = "Playlist cover",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
              onError = { Log.w(TAG_SHARED, "playlist cover failed url=$url") },
            )
          } else {
            Icon(
              PhosphorIcons.Regular.Playlist,
              contentDescription = null,
              tint = PiratePalette.TextMuted,
              modifier = Modifier.size(28.dp),
            )
          }
        }
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          pl.name.ifBlank { "Shared playlist" },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onBackground,
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          "${share.trackCount} track${if (share.trackCount == 1) "" else "s"} · shared by ${sharedByLabel ?: "unknown"}",
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = PiratePalette.TextMuted,
        )
      }
    }

    if (!error.isNullOrBlank()) {
      Text(
        text = error,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
      )
    }

    if (tracks.isEmpty()) {
      if (loading) {
        SharedPlaylistTracksSkeleton()
        return
      }

      if (!loading) {
        EmptyState(title = "No tracks in this playlist", actionLabel = null, onAction = null)
      }
      return
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = sharedPlaylistTracksListState,
      contentPadding = PaddingValues(bottom = 12.dp),
    ) {
      items(tracks, key = { it.contentId.ifBlank { it.trackId } }) { t ->
        val rowTrack = sharedCloudTrackToRowTrack(t)
        val id = rowTrack.id
        TrackItemRow(
          track = rowTrack,
          isActive = currentTrackId == id,
          isPlaying = currentTrackId == id && isPlaying,
          onPress = { onPlayTrack(t) },
          onMenuPress = { onDownloadTrack(t) },
        )
      }
    }
  }
}

@Composable
internal fun SharedPlaylistTracksSkeleton() {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = 12.dp),
  ) {
    items(6) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(72.dp)
          .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          modifier = Modifier.size(48.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
          shape = MaterialTheme.shapes.medium,
        ) {}
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Surface(
            modifier = Modifier
              .fillMaxWidth(0.7f)
              .height(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = MaterialTheme.shapes.small,
          ) {}
          Surface(
            modifier = Modifier
              .fillMaxWidth(0.45f)
              .height(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = MaterialTheme.shapes.small,
          ) {}
        }
        Surface(
          modifier = Modifier.size(24.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          shape = MaterialTheme.shapes.small,
        ) {}
      }
    }
  }
}
