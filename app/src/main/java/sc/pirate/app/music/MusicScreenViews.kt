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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import sc.pirate.app.ui.PirateOutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import sc.pirate.app.music.ui.TrackItemRow
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.PirateSheetTitle

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
  searchQuery: String,
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
  val trimmedQuery = searchQuery.trim()

  val visibleTracks =
    remember(tracks, filter, sort, trimmedQuery) {
      val sourceFiltered =
        when (filter) {
          LibraryFilterOption.All -> tracks
          LibraryFilterOption.LocalDevice -> tracks.filter(::isLocalDeviceLibraryTrack)
          LibraryFilterOption.Cloud -> tracks.filter(::isCloudLibraryTrack)
        }
      val textFiltered =
        if (trimmedQuery.isBlank()) {
          sourceFiltered
        } else {
          val needle = trimmedQuery.lowercase()
          sourceFiltered.filter { track ->
            track.title.lowercase().contains(needle) ||
              track.artist.lowercase().contains(needle) ||
              track.album.lowercase().contains(needle)
          }
        }
      when (sort) {
        LibrarySortOption.Recent -> textFiltered
        LibrarySortOption.Title -> textFiltered.sortedBy { it.title.lowercase() }
        LibrarySortOption.Artist ->
          textFiltered.sortedWith(
            compareBy<MusicTrack> { it.artist.lowercase() }.thenBy { it.title.lowercase() },
          )
        LibrarySortOption.Duration ->
          textFiltered.sortedWith(
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
  val emptySearchBody =
    if (trimmedQuery.isBlank()) {
      emptyFilterBody
    } else {
      "Try a different title, artist, or album."
    }
  val emptyTitle =
    if (trimmedQuery.isBlank()) {
      emptyFilterTitle
    } else {
      "No songs match \"$trimmedQuery\""
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
        title = emptyTitle,
        body = emptySearchBody,
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
internal fun DiscoverSearchView(
  query: String,
  results: List<MusicDiscoveryResult>,
  loading: Boolean,
  error: String?,
  warning: String?,
  onOpenResult: (MusicDiscoveryResult) -> Unit,
) {
  val searchListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }
  val trimmedQuery = query.trim()

  Column(modifier = Modifier.fillMaxSize()) {
    if (!warning.isNullOrBlank()) {
      Text(
        text = warning,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (!error.isNullOrBlank() && results.isEmpty()) {
      EmptyState(
        title = error,
        body = "Try again in a moment.",
        actionLabel = null,
        onAction = null,
      )
      return
    }

    if (trimmedQuery.isBlank()) {
      EmptyState(
        title = "Search Pirate and Genius",
        body = "Find published tracks and Genius catalog matches from one place.",
        actionLabel = null,
        onAction = null,
      )
      return
    }

    if (loading && results.isEmpty()) {
      EmptyState(
        title = "Searching...",
        body = null,
        actionLabel = null,
        onAction = null,
      )
      return
    }

    if (!loading && results.isEmpty()) {
      EmptyState(
        title = "No songs found",
        body = "Try a different song title, artist, or album.",
        actionLabel = null,
        onAction = null,
      )
      return
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      state = searchListState,
      contentPadding = PaddingValues(bottom = 12.dp),
    ) {
      items(results, key = { libraryTrackKey(it) }) { t ->
        DiscoveryResultRow(
          result = t,
          onPress = { onOpenResult(t) },
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

private fun libraryTrackKey(result: MusicDiscoveryResult): String =
  "${result.source.name}:${result.trackId.trim()}"

private fun discoverySourceLabel(source: MusicDiscoverySource): String =
  when (source) {
    MusicDiscoverySource.Catalog -> "On Genius"
    MusicDiscoverySource.Published -> "On Pirate"
    MusicDiscoverySource.Both -> "On Pirate + Genius"
  }

private fun discoveryLearnLabel(availability: MusicDiscoveryLearnAvailability?): String? =
  when (availability) {
    MusicDiscoveryLearnAvailability.Available -> "Learn ready"
    MusicDiscoveryLearnAvailability.InsufficientLines -> "Lyrics incomplete"
    MusicDiscoveryLearnAvailability.NoReferents -> "No annotations yet"
    MusicDiscoveryLearnAvailability.Error -> "Learn unavailable"
    null -> null
  }

@Composable
private fun DiscoveryResultRow(
  result: MusicDiscoveryResult,
  onPress: () -> Unit,
) {
  val metaLine =
    buildString {
      append(discoverySourceLabel(result.source))
      discoveryLearnLabel(result.learnAvailability)?.let { label ->
        append(" • ")
        append(label)
      }
    }
  val albumLine =
    buildString {
      append(result.artist)
      result.album?.trim()?.takeIf { it.isNotBlank() }?.let { album ->
        append(" • ")
        append(album)
      }
    }

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onPress)
        .padding(horizontal = 16.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier =
        Modifier
          .size(48.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      if (!result.artworkUrl.isNullOrBlank()) {
        AsyncImage(
          model = result.artworkUrl,
          contentDescription = "Song artwork",
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

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = result.title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.Medium,
        style = MaterialTheme.typography.bodyLarge,
      )
      Text(
        text = albumLine,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = PiratePalette.TextMuted,
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = metaLine,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
    }

    Icon(
      PhosphorIcons.Regular.CaretRight,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
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
