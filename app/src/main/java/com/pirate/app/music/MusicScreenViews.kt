package com.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pirate.app.music.ui.TrackItemRow
import com.pirate.app.ui.PiratePrimaryButton
import com.pirate.app.ui.PirateSheetTitle

internal enum class LibraryFilterOption {
  All,
  LocalDevice,
  Cloud,
}

private enum class LibrarySortOption {
  Recent,
  Title,
  Artist,
  Duration,
}

private fun isLocalDeviceLibraryTrack(track: MusicTrack): Boolean {
  val uri = track.uri.trim().lowercase()
  if (!uri.startsWith("content://media/")) return false
  return uri.contains("/audio/media/")
}

private fun isPurchasedLibraryTrack(track: MusicTrack): Boolean {
  if (!track.purchaseId.isNullOrBlank()) return true
  val contentId = track.contentId?.trim()?.lowercase().orEmpty()
  return contentId.startsWith("purchase:")
}

private fun isCloudLibraryTrack(track: MusicTrack): Boolean {
  if (isPurchasedLibraryTrack(track)) return true
  if (track.isCloudOnly) return true
  val hasCloudOwnershipMarker = !track.contentId.isNullOrBlank() || !track.pieceCid.isNullOrBlank()
  return hasCloudOwnershipMarker && !isLocalDeviceLibraryTrack(track)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryView(
  hasPermission: Boolean,
  requestPermission: () -> Unit,
  showSpotifyAccessPrompt: Boolean,
  onOpenSpotifyAccessSettings: () -> Unit,
  tracks: List<MusicTrack>,
  scanning: Boolean,
  error: String?,
  currentTrackId: String?,
  isPlaying: Boolean,
  onScan: () -> Unit,
  onPlayTrack: (MusicTrack, List<MusicTrack>) -> Unit,
  onTrackMenu: (MusicTrack) -> Unit,
) {
  if (!hasPermission) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Permission required to read your music library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
      PiratePrimaryButton(text = "Grant Permission", onClick = requestPermission)
      if (showSpotifyAccessPrompt) {
        SpotifyScrobbleAccessPrompt(onOpenSpotifyAccessSettings = onOpenSpotifyAccessSettings)
      }
    }
    return
  }

  var filter by rememberSaveable { mutableStateOf(LibraryFilterOption.All) }
  var sort by rememberSaveable { mutableStateOf(LibrarySortOption.Recent) }
  var sortSheetOpen by rememberSaveable { mutableStateOf(false) }

  val visibleTracks =
    remember(tracks, filter, sort) {
      val filtered =
        when (filter) {
          LibraryFilterOption.All -> tracks
          LibraryFilterOption.LocalDevice -> tracks.filter(::isLocalDeviceLibraryTrack)
          LibraryFilterOption.Cloud -> tracks.filter(::isCloudLibraryTrack)
        }
      when (sort) {
        LibrarySortOption.Recent -> filtered
        LibrarySortOption.Title -> filtered.sortedBy { it.title.lowercase() }
        LibrarySortOption.Artist ->
          filtered.sortedWith(
            compareBy<MusicTrack> { it.artist.lowercase() }.thenBy { it.title.lowercase() },
          )
        LibrarySortOption.Duration ->
          filtered.sortedWith(
            compareByDescending<MusicTrack> { it.durationSec }.thenBy { it.title.lowercase() },
          )
      }
    }
  val libraryListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }

  val sortLabel =
    when (sort) {
      LibrarySortOption.Recent -> "Recent"
      LibrarySortOption.Title -> "Title"
      LibrarySortOption.Artist -> "Artist"
      LibrarySortOption.Duration -> "Duration"
    }
  val emptyFilterTitle =
    when (filter) {
      LibraryFilterOption.Cloud -> "No cloud songs yet"
      else -> "No songs match this filter"
    }
  val emptyFilterBody =
    when (filter) {
      LibraryFilterOption.Cloud ->
        "Cloud shows your purchased songs and other songs available from cloud access."
      else -> null
    }

  Column(modifier = Modifier.fillMaxSize()) {
    if (showSpotifyAccessPrompt) {
      SpotifyScrobbleAccessPrompt(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        onOpenSpotifyAccessSettings = onOpenSpotifyAccessSettings,
      )
    }

    LibraryControlsRow(
      filter = filter,
      sortLabel = sortLabel,
      onFilterChange = { filter = it },
      onOpenSort = { sortSheetOpen = true },
    )

    if (!error.isNullOrBlank()) {
      Text(
        text = error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (tracks.isEmpty() && !scanning) {
      EmptyState(
        title = "No songs in your library yet",
        actionLabel = "Scan device",
        onAction = onScan,
      )
      return
    }

    if (scanning && tracks.isEmpty()) {
      EmptyState(
        title = "Scanning your device...",
        actionLabel = null,
        onAction = null,
      )
      return
    }

    if (tracks.isNotEmpty() && visibleTracks.isEmpty()) {
      EmptyState(
        title = emptyFilterTitle,
        body = emptyFilterBody,
        actionLabel = if (filter != LibraryFilterOption.All) "Show all songs" else null,
        onAction = if (filter != LibraryFilterOption.All) {
          { filter = LibraryFilterOption.All }
        } else {
          null
        },
      )
      return
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = libraryListState,
      contentPadding = PaddingValues(bottom = 12.dp),
    ) {
      items(visibleTracks, key = { libraryTrackKey(it) }) { t ->
        TrackItemRow(
          track = t,
          isActive = currentTrackId == t.id,
          isPlaying = currentTrackId == t.id && isPlaying,
          onPress = { onPlayTrack(t, visibleTracks) },
          onMenuPress = { onTrackMenu(t) },
        )
      }
    }
  }

  if (sortSheetOpen) {
    ModalBottomSheet(onDismissRequest = { sortSheetOpen = false }) {
      PirateSheetTitle(
        text = "Sort Library",
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      )
      SortSheetOptionRow(
        label = "Recent",
        selected = sort == LibrarySortOption.Recent,
        onSelect = {
          sort = LibrarySortOption.Recent
          sortSheetOpen = false
        },
      )
      SortSheetOptionRow(
        label = "Title",
        selected = sort == LibrarySortOption.Title,
        onSelect = {
          sort = LibrarySortOption.Title
          sortSheetOpen = false
        },
      )
      SortSheetOptionRow(
        label = "Artist",
        selected = sort == LibrarySortOption.Artist,
        onSelect = {
          sort = LibrarySortOption.Artist
          sortSheetOpen = false
        },
      )
      SortSheetOptionRow(
        label = "Duration",
        selected = sort == LibrarySortOption.Duration,
        onSelect = {
          sort = LibrarySortOption.Duration
          sortSheetOpen = false
        },
      )
      Spacer(modifier = Modifier.width(1.dp))
    }
  }
}

@Composable
private fun SpotifyScrobbleAccessPrompt(
  modifier: Modifier = Modifier,
  onOpenSpotifyAccessSettings: () -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "Enable Notification Access to scrobble Spotify playback.",
      )
      PiratePrimaryButton(text = "Enable", onClick = onOpenSpotifyAccessSettings, modifier = Modifier.fillMaxWidth())
    }
  }
}

@Composable
internal fun SearchView(
  query: String,
  onQueryChange: (String) -> Unit,
  tracks: List<MusicTrack>,
  currentTrackId: String?,
  isPlaying: Boolean,
  onPlayTrack: (MusicTrack) -> Unit,
  onTrackMenu: (MusicTrack) -> Unit,
) {
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  val q = query.trim()
  val results =
    remember(tracks, q) {
      if (q.isBlank()) {
        tracks
      } else {
        val needle = q.lowercase()
        tracks.filter { t ->
          t.title.lowercase().contains(needle) ||
            t.artist.lowercase().contains(needle) ||
            t.album.lowercase().contains(needle)
        }
      }
    }
  val searchListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }

  Column(modifier = Modifier.fillMaxSize()) {
    OutlinedTextField(
      value = query,
      onValueChange = onQueryChange,
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 10.dp)
          .focusRequester(focusRequester),
      singleLine = true,
      placeholder = { Text("Search your library") },
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = searchListState,
      contentPadding = PaddingValues(bottom = 12.dp),
    ) {
      items(results, key = { libraryTrackKey(it) }) { t ->
        TrackItemRow(
          track = t,
          isActive = currentTrackId == t.id,
          isPlaying = currentTrackId == t.id && isPlaying,
          onPress = { onPlayTrack(t) },
          onMenuPress = { onTrackMenu(t) },
        )
      }
    }
  }
}

private fun libraryTrackKey(track: MusicTrack): String {
  val id = track.id.trim()
  val contentId = track.contentId?.trim().orEmpty()
  val uri = track.uri.trim()
  if (contentId.isNotBlank()) return "cid:$contentId"
  if (uri.isNotBlank()) return "uri:$uri"
  return "id:$id|${track.title.trim()}|${track.artist.trim()}"
}

@Composable
internal fun LibraryControlsRow(
  filter: LibraryFilterOption,
  sortLabel: String,
  onFilterChange: (LibraryFilterOption) -> Unit,
  onOpenSort: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FilterChip(
      selected = filter == LibraryFilterOption.All,
      onClick = { onFilterChange(LibraryFilterOption.All) },
      label = { Text("All", maxLines = 1) },
    )
    FilterChip(
      selected = filter == LibraryFilterOption.LocalDevice,
      onClick = { onFilterChange(LibraryFilterOption.LocalDevice) },
      label = { Text("Local", maxLines = 1) },
    )
    FilterChip(
      selected = filter == LibraryFilterOption.Cloud,
      onClick = { onFilterChange(LibraryFilterOption.Cloud) },
      label = { Text("Cloud", maxLines = 1) },
    )
    Spacer(modifier = Modifier.weight(1f))
    FilterChip(
      selected = false,
      onClick = onOpenSort,
      label = { Text("Sort: $sortLabel", maxLines = 1) },
    )
  }
}

@Composable
internal fun SortSheetOptionRow(
  label: String,
  selected: Boolean,
  onSelect: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onSelect)
        .padding(horizontal = 20.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, color = MaterialTheme.colorScheme.onSurface)
    RadioButton(selected = selected, onClick = onSelect)
  }
}

@Composable
internal fun EmptyState(
  title: String,
  body: String? = null,
  actionLabel: String?,
  onAction: (() -> Unit)?,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 28.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
    if (!body.isNullOrBlank()) {
      Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!actionLabel.isNullOrBlank() && onAction != null) {
      PirateOutlinedButton(onClick = onAction) {
        Icon(PhosphorIcons.Regular.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.width(8.dp))
        Text(actionLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
      }
    }
  }
}
