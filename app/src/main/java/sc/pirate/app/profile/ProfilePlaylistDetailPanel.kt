package sc.pirate.app.profile

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import sc.pirate.app.music.OnChainPlaylist
import sc.pirate.app.theme.PiratePalette

private const val PROFILE_PLAYLIST_SCROLL_DEBUG_TAG = "ProfileScrollDebug"

@Composable
internal fun ProfilePlaylistDetailPanel(
  playlist: OnChainPlaylist,
  onBack: () -> Unit,
  onOpenSong: ((trackId: String, title: String?, artist: String?) -> Unit)? = null,
  onOpenArtist: ((String) -> Unit)? = null,
) {
  val tracksListState =
    rememberSaveable(playlist.id, saver = LazyListState.Saver) { LazyListState(0, 0) }
  var tracks by remember(playlist.id) { mutableStateOf<List<PublishedSongRow>>(emptyList()) }
  var loading by remember(playlist.id) { mutableStateOf(true) }
  var error by remember(playlist.id) { mutableStateOf<String?>(null) }

  DisposableEffect(playlist.id, tracksListState, tracks.size) {
    Log.d(
      PROFILE_PLAYLIST_SCROLL_DEBUG_TAG,
      "PlaylistDetail attach playlistId=${playlist.id} index=${tracksListState.firstVisibleItemIndex} offset=${tracksListState.firstVisibleItemScrollOffset} items=${tracks.size}",
    )
    onDispose {
      Log.d(
        PROFILE_PLAYLIST_SCROLL_DEBUG_TAG,
        "PlaylistDetail dispose playlistId=${playlist.id} index=${tracksListState.firstVisibleItemIndex} offset=${tracksListState.firstVisibleItemScrollOffset} items=${tracks.size}",
      )
    }
  }

  LaunchedEffect(playlist.id) {
    loading = true
    error = null
    runCatching { ProfileMusicApi.fetchPlaylistTracks(playlist.id) }
      .onSuccess { tracks = it; loading = false }
      .onFailure { error = it.message ?: "Failed to load tracks"; loading = false }
  }

  val coverUrl = if (playlist.coverCid.isNotBlank()) {
    ProfileScrobbleApi.coverUrl(playlist.coverCid, 192)
  } else null

  Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // Header row with back button and title
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PirateIconButton(onClick = onBack) {
        Icon(
          PhosphorIcons.Regular.ArrowLeft,
          contentDescription = "Previous screen",
          tint = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.size(26.dp),
        )
      }
      Text(
        playlist.name.ifBlank { "Playlist" },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).padding(end = 16.dp),
      )
    }

    // Cover art
    Box(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier.size(200.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
              model = coverUrl,
              contentDescription = "Playlist cover",
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
            )
          } else {
            Icon(
              PhosphorIcons.Regular.Queue,
              contentDescription = null,
              tint = PiratePalette.TextMuted,
              modifier = Modifier.size(48.dp),
            )
          }
        }
      }
    }

    Text(
      "${playlist.trackCount} track${if (playlist.trackCount != 1) "s" else ""}",
      modifier = Modifier.padding(horizontal = 16.dp),
      style = MaterialTheme.typography.bodyLarge,
      color = PiratePalette.TextMuted,
    )

    Spacer(Modifier.height(8.dp))

    when {
      loading -> CenteredStatus {
        CircularProgressIndicator(Modifier.size(32.dp))
        Spacer(Modifier.height(12.dp))
        Text("Loading tracks...", color = PiratePalette.TextMuted)
      }
      !error.isNullOrBlank() && tracks.isEmpty() -> CenteredStatus {
        Text(error!!, color = MaterialTheme.colorScheme.error)
      }
      tracks.isEmpty() -> CenteredStatus {
        Text("No tracks in this playlist.", color = PiratePalette.TextMuted)
      }
      else -> LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = tracksListState,
        contentPadding = PaddingValues(bottom = 16.dp),
      ) {
        itemsIndexed(tracks, key = { i, t -> "${t.trackId}:$i" }) { _, track ->
          PlaylistDetailTrackRow(
            track = track,
            onOpenSong = onOpenSong,
            onOpenArtist = onOpenArtist,
          )
        }
      }
    }
  }
}

@Composable
private fun PlaylistDetailTrackRow(
  track: PublishedSongRow,
  onOpenSong: ((trackId: String, title: String?, artist: String?) -> Unit)? = null,
  onOpenArtist: ((String) -> Unit)? = null,
) {
  val trackId = track.trackId.trim()
  val artistName = track.artist.ifBlank { "Unknown Artist" }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(72.dp)
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      if (!track.coverCid.isNullOrBlank()) {
        AsyncImage(
          model = ProfileScrobbleApi.coverUrl(track.coverCid, 96),
          contentDescription = "Album art",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      } else {
        Icon(PhosphorIcons.Regular.MusicNote, contentDescription = null, tint = PiratePalette.TextMuted, modifier = Modifier.size(20.dp))
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        track.title.ifBlank { "Unknown Track" },
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onBackground,
      )
      Text(
        artistName,
        style = MaterialTheme.typography.bodyLarge,
        color = PiratePalette.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}
