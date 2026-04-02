package sc.pirate.app.song

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*
import android.util.Log

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import sc.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import sc.pirate.app.music.MusicScreenCache
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.PirateShimmer
import java.util.Locale

private enum class ArtistTab(val icon: ImageVector, val contentDescription: String) {
  Songs(PhosphorIcons.Regular.MusicNote, "Songs"),
  Leaderboard(PhosphorIcons.Regular.Crown, "Leaderboard"),
}

private const val ARTIST_SCREEN_LOG_TAG = "ArtistScreen"

@Composable
fun ArtistScreen(
  artistName: String,
  userAddress: String?,
  onBack: () -> Unit,
  onOpenSong: (trackId: String, title: String?, artist: String?) -> Unit,
  onOpenProfile: (String) -> Unit,
) {
  var selectedTab by remember { mutableIntStateOf(0) }
  var refreshKey by remember { mutableIntStateOf(0) }

  var loading by remember { mutableStateOf(true) }
  var loadError by remember { mutableStateOf<String?>(null) }
  var songs by remember { mutableStateOf<List<ArtistCatalogSongRow>>(emptyList()) }
  var topListeners by remember { mutableStateOf<List<ArtistListenerRow>>(emptyList()) }
  var artistImageUrl by remember { mutableStateOf<String?>(null) }
  var artistImageLoading by remember { mutableStateOf(false) }

  LaunchedEffect(artistName, refreshKey, userAddress) {
    Log.d(
      ARTIST_SCREEN_LOG_TAG,
      "load:start artist=$artistName refreshKey=$refreshKey hasUserAddress=${!userAddress.isNullOrBlank()}",
    )
    loading = true
    loadError = null
    artistImageUrl = null
    artistImageLoading = false
    val songsResult = runCatching { SongArtistApi.fetchArtistCatalogSongs(artistName, maxEntries = 8) }
    val listenersResult = runCatching { SongArtistApi.fetchArtistTopListeners(artistName, maxEntries = 40) }
    songs = songsResult.getOrElse { emptyList() }
    topListeners = listenersResult.getOrElse { emptyList() }
    Log.d(
      ARTIST_SCREEN_LOG_TAG,
      "load:data artist=$artistName songs=${songs.size} listeners=${topListeners.size}",
    )
    if (songs.isEmpty() && topListeners.isEmpty()) {
      val songsError = songsResult.exceptionOrNull()?.message
      val listenersError = listenersResult.exceptionOrNull()?.message
      if (!songsError.isNullOrBlank() && !listenersError.isNullOrBlank()) {
        loadError = songsError
      }
    }
    if (!loadError.isNullOrBlank()) {
      Log.w(ARTIST_SCREEN_LOG_TAG, "load:error artist=$artistName error=$loadError")
    }
    loading = false
  }

  LaunchedEffect(artistName, refreshKey, userAddress) {
    val resolvedArtistName = primaryArtist(artistName).ifBlank { artistName }.trim()
    if (resolvedArtistName.isBlank()) {
      artistImageUrl = null
      artistImageLoading = false
      return@LaunchedEffect
    }
    artistImageLoading = true
    Log.d(
      ARTIST_SCREEN_LOG_TAG,
      "image:start artist=$resolvedArtistName hasUserAddress=${!userAddress.isNullOrBlank()}",
    )
    val resolvedUrl =
      runCatching { ArtistImageApi.resolveArtistImageUrl(null, resolvedArtistName, userAddress) }.getOrNull()
    if (!resolvedUrl.isNullOrBlank()) {
      artistImageUrl = resolvedUrl
    }
    artistImageLoading = false
    Log.d(
      ARTIST_SCREEN_LOG_TAG,
      "image:done artist=$resolvedArtistName resolvedArtistImageUrl=$resolvedUrl",
    )
  }

  val tabs = ArtistTab.entries

  if (loading) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  if (!loadError.isNullOrBlank() && songs.isEmpty() && topListeners.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(loadError ?: "Failed to load", color = MaterialTheme.colorScheme.error)
        PirateOutlinedButton(onClick = { refreshKey += 1 }) { Text("Retry") }
      }
    }
    return
  }

    val fallbackCoverUrl = songs.firstNotNullOfOrNull { it.coverUrl ?: it.artworkUrl }
    val coverUrl = artistImageUrl ?: fallbackCoverUrl
    val listenersLabel =
      when (topListeners.size) {
        1 -> "1 listener"
        else -> "${topListeners.size} listeners"
      }
    val songsLabel =
      when (songs.size) {
        1 -> "1 song"
        else -> "${songs.size} songs"
      }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    // ── Hero: full-width square image, artist name bottom-left, back button top-left ──
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(1f),
    ) {
      // Image or fallback
      if (!coverUrl.isNullOrBlank()) {
        AsyncImage(
          model = coverUrl,
          contentDescription = "Artist cover",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
        if (artistImageLoading && artistImageUrl.isNullOrBlank() && fallbackCoverUrl.isNullOrBlank()) {
          PirateShimmer(modifier = Modifier.fillMaxSize())
        }
      } else {
        Box(
          modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            artistName.take(1).uppercase(Locale.US),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Gradient scrim for text legibility
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(160.dp)
          .align(Alignment.BottomStart)
          .background(
            Brush.verticalGradient(
              colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
            ),
          ),
      )

      // Back button — top-left with status bar padding
      PirateIconButton(
        onClick = onBack,
        modifier = Modifier
          .align(Alignment.TopStart)
          .statusBarsPadding()
          .padding(4.dp),
      ) {
        Icon(
          PhosphorIcons.Regular.ArrowLeft,
          contentDescription = "Previous screen",
          tint = Color.White,
          modifier = Modifier.size(26.dp),
        )
      }

      // Artist name + listener count — bottom-left
      Column(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          primaryArtist(artistName),
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text(
            songsLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.8f),
          )
          if (topListeners.isNotEmpty()) {
            Text(
              listenersLabel,
              style = MaterialTheme.typography.bodyLarge,
              color = Color.White.copy(alpha = 0.8f),
            )
          }
        }
      }
    }

    // ── Icon tab row ──
    TabRow(
      selectedTabIndex = selectedTab,
      containerColor = MaterialTheme.colorScheme.background,
      contentColor = MaterialTheme.colorScheme.onBackground,
      divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
      indicator = { tabPositions ->
        TabRowDefaults.SecondaryIndicator(
          modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
          color = MaterialTheme.colorScheme.primary,
        )
      },
    ) {
      tabs.forEachIndexed { idx, tab ->
        Tab(
          selected = idx == selectedTab,
          onClick = { selectedTab = idx },
          icon = {
            Icon(
              imageVector = tab.icon,
              contentDescription = tab.contentDescription,
              modifier = Modifier.size(26.dp),
              tint = if (idx == selectedTab) Color.White else Color(0xFFA3A3A3),
            )
          },
        )
      }
    }

    when (tabs[selectedTab]) {
      ArtistTab.Songs -> ArtistSongsPanel(rows = songs, onOpenSong = onOpenSong)
      ArtistTab.Leaderboard ->
        SongLeaderboardPanel(
          rows =
            topListeners.map { listener ->
              SongListenerRow(
                userAddress = listener.userAddress,
                scrobbleCount = listener.scrobbleCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                lastScrobbleAtSec = listener.lastScrobbleAtSec,
              )
            },
          onOpenProfile = onOpenProfile,
        )
    }
  }
}

@Composable
private fun ArtistSongsPanel(
  rows: List<ArtistCatalogSongRow>,
  onOpenSong: (trackId: String, title: String?, artist: String?) -> Unit,
) {
  val rowHeight = 72.dp
  val songsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }

  if (rows.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("No songs found.", color = PiratePalette.TextMuted)
    }
    return
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = songsListState,
    contentPadding = PaddingValues(bottom = 20.dp),
  ) {
    item {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    itemsIndexed(rows, key = { _, row -> row.trackId }) { idx, row ->
      val interactionSource = remember { MutableInteractionSource() }
      val pressed by interactionSource.collectIsPressedAsState()
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(rowHeight)
          .background(
            if (pressed) {
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            } else {
              Color.Transparent
            },
          )
          .clickable(
            interactionSource = interactionSource,
            indication = null,
          ) {
            MusicScreenCache.putSongArtworkSeed(row.trackId, row.coverUrl ?: row.artworkUrl)
            onOpenSong(row.trackId, row.title, row.artistLabel)
          }
          .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          "#${idx + 1}",
          style = MaterialTheme.typography.bodyLarge,
          color = PiratePalette.TextMuted,
          modifier = Modifier.width(32.dp),
        )
        val artworkUrl = row.coverUrl ?: row.artworkUrl
        if (!artworkUrl.isNullOrBlank()) {
          AsyncImage(
            model = artworkUrl,
            contentDescription = "${row.title} cover",
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
          )
        } else {
          Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text(
                row.title.take(1).uppercase(Locale.US),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
          val subtitle = row.album.ifBlank { null }
          if (!subtitle.isNullOrBlank()) {
            Text(
              subtitle,
              style = MaterialTheme.typography.bodyMedium,
              color = PiratePalette.TextMuted,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
  }
}
