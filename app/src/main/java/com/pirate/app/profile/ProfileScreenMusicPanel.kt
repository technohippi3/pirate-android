package com.pirate.app.profile

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pirate.app.music.OnChainPlaylist
import com.pirate.app.theme.PiratePalette

private const val PROFILE_SCROLL_DEBUG_TAG = "ProfileScrollDebug"

@Composable
internal fun SongsPanel(
  publishedSongs: List<PublishedSongRow>,
  loading: Boolean,
  error: String?,
  onPlaySong: ((PublishedSongRow) -> Unit)? = null,
  onOpenSong: ((trackId: String, title: String?, artist: String?) -> Unit)? = null,
  onOpenArtist: ((String) -> Unit)? = null,
) {
  val songsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
  DisposableEffect(songsListState, publishedSongs.size) {
    Log.d(
      PROFILE_SCROLL_DEBUG_TAG,
      "SongsPanel attach index=${songsListState.firstVisibleItemIndex} offset=${songsListState.firstVisibleItemScrollOffset} items=${publishedSongs.size}",
    )
    onDispose {
      Log.d(
        PROFILE_SCROLL_DEBUG_TAG,
        "SongsPanel dispose index=${songsListState.firstVisibleItemIndex} offset=${songsListState.firstVisibleItemScrollOffset} items=${publishedSongs.size}",
      )
    }
  }

  when {
    loading -> CenteredStatus {
      CircularProgressIndicator(Modifier.size(32.dp))
      Spacer(Modifier.height(12.dp))
      Text("Loading songs...", color = PiratePalette.TextMuted)
    }
    publishedSongs.isEmpty() && !error.isNullOrBlank() -> CenteredStatus {
      Text(error, color = MaterialTheme.colorScheme.error)
    }
    publishedSongs.isEmpty() -> CenteredStatus {
      Text("No published songs yet.", color = PiratePalette.TextMuted)
    }
    else -> LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = songsListState,
    ) {
      itemsIndexed(publishedSongs, key = { _, song -> song.contentId }) { _, song ->
        PublishedSongRowItem(
          song = song,
          onPlay = onPlaySong,
          onOpenSong = onOpenSong,
          onOpenArtist = onOpenArtist,
        )
      }
      if (!error.isNullOrBlank()) {
        item {
          Text(
            error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}

@Composable
internal fun PlaylistsPanel(
  playlists: List<OnChainPlaylist>,
  loading: Boolean,
  error: String?,
  onOpenPlaylist: (OnChainPlaylist) -> Unit = {},
) {
  val playlistsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
  DisposableEffect(playlistsListState, playlists.size) {
    Log.d(
      PROFILE_SCROLL_DEBUG_TAG,
      "PlaylistsPanel attach index=${playlistsListState.firstVisibleItemIndex} offset=${playlistsListState.firstVisibleItemScrollOffset} items=${playlists.size}",
    )
    onDispose {
      Log.d(
        PROFILE_SCROLL_DEBUG_TAG,
        "PlaylistsPanel dispose index=${playlistsListState.firstVisibleItemIndex} offset=${playlistsListState.firstVisibleItemScrollOffset} items=${playlists.size}",
      )
    }
  }

  when {
    loading -> CenteredStatus {
      CircularProgressIndicator(Modifier.size(32.dp))
      Spacer(Modifier.height(12.dp))
      Text("Loading playlists...", color = PiratePalette.TextMuted)
    }
    playlists.isEmpty() && !error.isNullOrBlank() -> CenteredStatus {
      Text(error, color = MaterialTheme.colorScheme.error)
    }
    playlists.isEmpty() -> CenteredStatus {
      Text("No playlists yet.", color = PiratePalette.TextMuted)
    }
    else -> LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = playlistsListState,
    ) {
      itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
        PlaylistRowItem(playlist, onClick = { onOpenPlaylist(playlist) })
      }
    }
  }
}

@Composable
internal fun MusicSectionHeader(title: String) {
  Text(
    title,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 4.dp),
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.SemiBold,
    color = PiratePalette.TextMuted,
  )
}

@Composable
internal fun PublishedSongRowItem(
  song: PublishedSongRow,
  onPlay: ((PublishedSongRow) -> Unit)? = null,
  onOpenSong: ((trackId: String, title: String?, artist: String?) -> Unit)? = null,
  onOpenArtist: ((String) -> Unit)? = null,
) {
  val trackId = song.trackId.trim()
  val songClickable = onOpenSong != null && trackId.isNotBlank()
  val rowClickable = songClickable || onPlay != null
  val artistName = song.artist.ifBlank { "Unknown Artist" }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(72.dp)
      .clickable(enabled = rowClickable) {
        if (songClickable) {
          onOpenSong?.invoke(trackId, song.title, song.artist)
        } else {
          onPlay?.invoke(song)
        }
      }
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      if (!song.coverCid.isNullOrBlank()) {
        AsyncImage(
          model = ProfileScrobbleApi.coverUrl(song.coverCid, 96),
          contentDescription = "Album art",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      } else {
        Icon(PhosphorIcons.Regular.MusicNote, contentDescription = null, tint = PiratePalette.TextMuted, modifier = Modifier.size(20.dp))
      }
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        song.title.ifBlank { "Unknown Track" },
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.clickable(enabled = songClickable) {
          onOpenSong?.invoke(trackId, song.title, song.artist)
        },
      )
      Text(
        artistName,
        style = MaterialTheme.typography.bodyLarge,
        color = PiratePalette.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(enabled = onOpenArtist != null && artistName.isNotBlank()) {
          onOpenArtist?.invoke(artistName)
        },
      )
    }
  }
}

@Composable
internal fun PlaylistRowItem(playlist: OnChainPlaylist, onClick: () -> Unit = {}) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (playlist.coverCid.isNotBlank()) {
      AsyncImage(model = ProfileScrobbleApi.coverUrl(playlist.coverCid, 96), contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
    } else {
      Surface(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) { Icon(PhosphorIcons.Regular.Queue, "Playlist", modifier = Modifier.size(24.dp), tint = PiratePalette.TextMuted) }
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(playlist.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
      Text("${playlist.trackCount} track${if (playlist.trackCount != 1) "s" else ""}", style = MaterialTheme.typography.bodyLarge, color = PiratePalette.TextMuted)
    }
  }
}
